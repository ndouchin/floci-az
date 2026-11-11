package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.entra.EntraStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An Entra cluster on real k3s: its API server authenticates Microsoft Entra tokens through floci-az's token webhook.
 * Skipped when no Docker daemon is reachable.
 */
@QuarkusTest
@TestProfile(AksEntraDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AKS Entra ID on real k3s (Docker required)")
class AksEntraDockerTest {

    /** The cluster calls floci-az back, so the test server listens on floci-az's own port. */
    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aks.mocked", "false",
                    "quarkus.http.test-port", "4577");
        }
    }

    private static final String SUB = "test-sub-aks-entra";
    private static final String RG = "test-rg-entra";
    private static final String NAME = "entra-cluster";
    private static final String API = "?api-version=2026-08-01";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String CLUSTER_PATH = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.ContainerService/managedClusters/" + NAME;
    private static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(5);

    private String adminKubeconfig;

    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available, skipping real k3s tests");
    }

    @AfterAll
    void cleanup() {
        try {
            given().delete(CLUSTER_PATH + API);
            AksTestPolling.until(Duration.ofMinutes(2), () -> given().get(CLUSTER_PATH + API).statusCode() == 404);
        } catch (Throwable ignored) {
            // best effort: a failed test may already have removed the cluster
        }
    }

    private static void awaitOperation(Response accepted) {
        URI uri = URI.create(accepted.header("Azure-AsyncOperation"));
        String operation = uri.getRawPath() + "?" + uri.getRawQuery();
        AksTestPolling.until(OPERATION_TIMEOUT, () -> !"InProgress".equals(given().get(operation).path("status")));
        given().get(operation).then().statusCode(200).body("status", equalTo("Succeeded"));
    }

    private static Response putCluster(String aadProfile) {
        return given().contentType("application/json").body("""
                {"location": "eastus",
                 "properties": {"kubernetesVersion": "1.36", "dnsPrefix": "entra",
                   "aadProfile": %s,
                   "agentPoolProfiles": [{"name": "nodepool1", "count": 1, "vmSize": "Standard_DS2_v2",
                                          "mode": "System"}]}}""".formatted(aadProfile)).put(CLUSTER_PATH + API);
    }

    private static String token(String grant, String clientId, Map<String, String> extra) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", grant)
                .formParam("client_id", clientId)
                .formParam("scope", AksEntra.SERVER_APP_ID + "/.default")
                .formParams(extra)
                .post("/" + TENANT + "/oauth2/v2.0/token").then().statusCode(200).extract().path("access_token");
    }

    private static String servicePrincipalToken() {
        return token("client_credentials", EntraStore.DEV_CLIENT_ID,
                Map.of("client_secret", EntraStore.DEV_CLIENT_SECRET));
    }

    private KubeconfigClient as(String token) throws Exception {
        return KubeconfigClient.withBearer(adminKubeconfig, token);
    }

    @Test
    @Order(1)
    @DisplayName("an Entra cluster is created and its admin credential reaches the API server")
    void createCluster() throws Exception {
        given().post("/_admin/reset").then().statusCode(204);
        Response created = putCluster("{\"managed\": true}");
        created.then().statusCode(201);
        awaitOperation(created);
        adminKubeconfig = given().post(CLUSTER_PATH + "/listClusterAdminCredential" + API)
                .then().statusCode(200).extract().path("kubeconfigs[0].value");
        assertEquals(200, KubeconfigClient.fromBase64(adminKubeconfig).exchange("GET", "/version", null).status());
    }

    @Test
    @Order(2)
    @DisplayName("a service principal's Entra token authenticates as its object id, with extra.oid")
    void aServicePrincipalTokenAuthenticates() throws Exception {
        KubeconfigClient.Reply review = as(servicePrincipalToken()).exchange("POST",
                "/apis/authentication.k8s.io/v1/selfsubjectreviews",
                "{\"apiVersion\": \"authentication.k8s.io/v1\", \"kind\": \"SelfSubjectReview\"}");
        assertEquals(201, review.status(), review.body());
        JsonNode user = review.json().path("status").path("userInfo");
        assertEquals(EntraStore.DEV_SP_OBJECT_ID, user.path("username").asText(), user.toString());
        assertEquals(EntraStore.DEV_SP_OBJECT_ID, user.path("extra").path("oid").path(0).asText(), user.toString());
    }

    @Test
    @Order(3)
    @DisplayName("a token the webhook refuses gets 401")
    void anUnknownTokenIsUnauthorized() throws Exception {
        assertEquals(401, as("not-a-token").exchange("GET", "/api/v1/namespaces", null).status());
    }

    private static String userToken() {
        return token("password", AksEntra.CLIENT_APP_ID, Map.of("username", EntraStore.DEV_USER_UPN,
                "password", "ignored"));
    }

    private int createNamespace(String name) throws Exception {
        return as(userToken()).exchange("POST", "/api/v1/namespaces",
                "{\"apiVersion\": \"v1\", \"kind\": \"Namespace\", \"metadata\": {\"name\": \"" + name + "\"}}")
                .status();
    }

    private void awaitStatus(int expected, ThrowingSupplier<Integer> call) {
        AksTestPolling.until(Duration.ofSeconds(90), () -> {
            try {
                return call.get() == expected;
            } catch (Exception e) {
                return false;
            }
        });
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @Test
    @Order(4)
    @DisplayName("a member of an admin group is cluster-admin through the generated ClusterRoleBinding")
    void anAdminGroupMemberIsClusterAdmin() throws Exception {
        assertEquals(403, createNamespace("before-admin"));
        Response updated = putCluster("{\"managed\": true, \"adminGroupObjectIDs\": [\""
                + EntraStore.DEV_GROUP_OBJECT_ID + "\"]}");
        updated.then().statusCode(200);
        awaitOperation(updated);
        awaitStatus(201, () -> createNamespace("admin-one"));
    }

    @Test
    @Order(5)
    @DisplayName("removing the group from adminGroupObjectIDs takes cluster-admin away")
    void removingTheGroupRevokesClusterAdmin() throws Exception {
        Response updated = putCluster("{\"managed\": true, \"adminGroupObjectIDs\": []}");
        updated.then().statusCode(200);
        awaitOperation(updated);
        awaitStatus(403, () -> createNamespace("admin-two"));
    }
}
