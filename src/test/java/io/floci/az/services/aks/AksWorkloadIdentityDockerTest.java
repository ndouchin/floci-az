package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.tls.TlsConfigSource;
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

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Workload Identity on real k3s, with floci-az's TLS on as Workload Identity needs: the cluster signs service account
 * tokens under its AKS-format issuer, floci-az serves that issuer's discovery document and keys, and pods reach
 * floci-az's Entra as {@code login.microsoftonline.com}. Skipped when no Docker daemon is reachable.
 */
@QuarkusTest
@TestProfile(AksWorkloadIdentityDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AKS Workload Identity on real k3s (Docker required)")
class AksWorkloadIdentityDockerTest {

    /**
     * floci-az with TLS: its TLS proxy takes floci-az's port and hands HTTP and HTTPS to the internal ports, which the
     * test server listens on. The TLS config source reads system properties, so the switch is set as one.
     */
    public static class RealModeProfile implements QuarkusTestProfile {
        static {
            System.setProperty(TLS_ENABLED, "true");
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aks.mocked", "false",
                    TLS_ENABLED, "true",
                    "floci-az.tls.https-port", "0",
                    "quarkus.http.test-port", "4570",
                    "quarkus.http.test-ssl-port", "4571");
        }
    }

    private static final String TLS_ENABLED = "floci-az.tls.enabled";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String CURL_IMAGE = "curlimages/curl:8.11.1";
    private static final String SUB = "test-sub-aks-wi";
    private static final String RG = "test-rg-wi";
    private static final String NAME = "wi-cluster";
    private static final String API = "?api-version=2026-08-01";
    private static final String CLUSTER_PATH = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.ContainerService/managedClusters/" + NAME;
    private static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(5);

    private String adminKubeconfig;
    private String issuer;

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
        } finally {
            System.clearProperty(TLS_ENABLED);
            TlsConfigSource.currentCertPem = null;
        }
    }

    private KubeconfigClient admin() throws Exception {
        return KubeconfigClient.fromBase64(adminKubeconfig);
    }

    /**
     * Runs {@code script} in a pod that mounts floci-az's CA from the ConfigMap {@code floci-az-ca} of its namespace,
     * labelled for Workload Identity when {@code labelled}, and returns its log.
     */
    private String runPod(String name, String script, String serviceAccount, String namespace, boolean labelled)
            throws Exception {
        Map<String, Object> metadata = labelled
                ? Map.of("name", name, "labels", Map.of("azure.workload.identity/use", "true"))
                : Map.of("name", name);
        String pod = MAPPER.writeValueAsString(Map.of("apiVersion", "v1", "kind", "Pod",
                "metadata", metadata,
                "spec", Map.of("restartPolicy", "Never", "serviceAccountName", serviceAccount,
                        "containers", List.of(Map.of("name", "probe", "image", CURL_IMAGE,
                                "command", List.of("sh", "-c", script),
                                "volumeMounts", List.of(Map.of("name", "ca", "mountPath", "/ca")))),
                        "volumes", List.of(Map.of("name", "ca", "configMap", Map.of("name", "floci-az-ca"))))));
        KubeconfigClient.Reply created = admin().exchange("POST", "/api/v1/namespaces/" + namespace + "/pods", pod);
        assertEquals(201, created.status(), created.body());
        AksTestPolling.until(Duration.ofMinutes(4), () -> {
            try {
                String phase = admin().get("/api/v1/namespaces/" + namespace + "/pods/" + name)
                        .path("status").path("phase").asText();
                return "Succeeded".equals(phase) || "Failed".equals(phase);
            } catch (Exception e) {
                return false;
            }
        });
        return admin().text("/api/v1/namespaces/" + namespace + "/pods/" + name + "/log");
    }

    private static void awaitOperation(Response accepted) {
        URI uri = URI.create(accepted.header("Azure-AsyncOperation"));
        String operation = uri.getRawPath() + "?" + uri.getRawQuery();
        AksTestPolling.until(OPERATION_TIMEOUT, () -> !"InProgress".equals(given().get(operation).path("status")));
        given().get(operation).then().statusCode(200).body("status", equalTo("Succeeded"));
    }

    private static JsonNode decode(String segment) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
    }

    /** The issuer URL without its scheme and host: the path floci-az serves the issuer's documents under. */
    private String issuerPath() {
        return URI.create(issuer).getRawPath();
    }

    private String issuerHost() {
        return URI.create(issuer).getHost();
    }

    /** floci-az's own path for the issuer's documents: {@code /_floci/aks/oidc/{region}/{tenant}/{uuid}/}. */
    private String flociIssuerPath() {
        return "/_floci/aks/oidc/" + issuerHost().substring(0, issuerHost().indexOf('.')) + issuerPath();
    }

    @Test
    @Order(1)
    @DisplayName("a new cluster gets the OIDC issuer and Workload Identity")
    void createCluster() {
        given().post("/_admin/reset").then().statusCode(204);
        Response created = given().contentType("application/json").body("""
                {"location": "eastus",
                 "properties": {"kubernetesVersion": "1.36", "dnsPrefix": "wi",
                   "securityProfile": {"workloadIdentity": {"enabled": true}},
                   "agentPoolProfiles": [{"name": "nodepool1", "count": 1, "vmSize": "Standard_DS2_v2",
                                          "mode": "System"}]}}""").put(CLUSTER_PATH + API);
        created.then().statusCode(201);
        awaitOperation(created);
        issuer = given().get(CLUSTER_PATH + API).then().statusCode(200)
                .body("properties.oidcIssuerProfile.enabled", equalTo(true))
                .extract().path("properties.oidcIssuerProfile.issuerURL");
        adminKubeconfig = given().post(CLUSTER_PATH + "/listClusterAdminCredential" + API)
                .then().statusCode(200).extract().path("kubeconfigs[0].value");
    }

    @Test
    @Order(2)
    @DisplayName("a service account token for api://AzureADTokenExchange carries the issuer and verifies against the"
            + " keys floci-az serves for it")
    void projectedTokensCarryTheIssuer() throws Exception {
        KubeconfigClient.Reply reply = KubeconfigClient.fromBase64(adminKubeconfig).exchange("POST",
                "/api/v1/namespaces/default/serviceaccounts/default/token",
                "{\"apiVersion\": \"authentication.k8s.io/v1\", \"kind\": \"TokenRequest\", "
                        + "\"spec\": {\"audiences\": [\"api://AzureADTokenExchange\"], \"expirationSeconds\": 3600}}");
        assertEquals(201, reply.status(), reply.body());
        String token = reply.json().path("status").path("token").asText();
        String[] parts = token.split("\\.");
        JsonNode claims = decode(parts[1]);
        assertEquals(issuer, claims.path("iss").asText());
        assertEquals("system:serviceaccount:default:default", claims.path("sub").asText());
        assertEquals("api://AzureADTokenExchange", claims.path("aud").path(0).asText());

        String kid = decode(parts[0]).path("kid").asText();
        JsonNode keys = MAPPER.readTree(given().get(flociIssuerPath() + "openid/v1/jwks")
                .then().statusCode(200).extract().asString()).path("keys");
        JsonNode key = null;
        for (JsonNode candidate : keys) {
            if (kid.equals(candidate.path("kid").asText())) {
                key = candidate;
            }
        }
        assertTrue(key != null, "no key " + kid + " in " + keys);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, Base64.getUrlDecoder().decode(key.path("n").asText())),
                new BigInteger(1, Base64.getUrlDecoder().decode(key.path("e").asText()))));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    @Test
    @Order(3)
    @DisplayName("the issuer's discovery document is served on the AKS host and on floci-az's own path")
    void theDiscoveryDocumentIsServed() {
        given().header("Host", issuerHost()).get(issuerPath() + ".well-known/openid-configuration")
                .then().statusCode(200)
                .body("issuer", equalTo(issuer))
                .body("jwks_uri", equalTo(issuer + "openid/v1/jwks"))
                .body("response_types_supported[0]", equalTo("id_token"))
                .body("subject_types_supported[0]", equalTo("public"))
                .body("id_token_signing_alg_values_supported[0]", equalTo("RS256"));
        given().header("Host", issuerHost()).get(issuerPath() + "openid/v1/jwks")
                .then().statusCode(200).body("keys[0].kty", equalTo("RSA"));
        given().get(flociIssuerPath() + ".well-known/openid-configuration")
                .then().statusCode(200).body("issuer", equalTo(issuer));
    }

    @Test
    @Order(4)
    @DisplayName("a pod trusts floci-az's CA from floci-az-ca and reaches its Entra as login.microsoftonline.com")
    void podsReachEntraAsMicrosoft() throws Exception {
        String log = runPod("entra-probe", "for i in $(seq 1 60); do curl -sf --cacert /ca/ca.crt "
                + "https://login.microsoftonline.com/" + TENANT + "/v2.0/.well-known/openid-configuration "
                + "&& echo && curl -sf --cacert /ca/ca.crt " + issuer + ".well-known/openid-configuration && exit 0; "
                + "sleep 2; done; exit 1", "default", "kube-system", false);
        assertTrue(log.contains("\"issuer\":\"https://login.microsoftonline.com/" + TENANT + "/v2.0\""), log);
        assertTrue(log.contains("\"issuer\":\"" + issuer + "\""), log);
    }

    private static final String CLIENT_ID = "aaaaaaaa-0000-4000-8000-0000000000c1";

    private static String podJson(String name, boolean labelled) throws Exception {
        Map<String, Object> metadata = labelled
                ? Map.of("name", name, "labels", Map.of("azure.workload.identity/use", "true"))
                : Map.of("name", name);
        return MAPPER.writeValueAsString(Map.of("apiVersion", "v1", "kind", "Pod", "metadata", metadata,
                "spec", Map.of("serviceAccountName", "app", "containers", List.of(Map.of("name", "app",
                        "image", CURL_IMAGE, "command", List.of("sleep", "3600"))))));
    }

    private static Map<String, String> variables(JsonNode container) {
        Map<String, String> variables = new HashMap<>();
        container.path("env").forEach(variable -> variables.put(variable.path("name").asText(),
                variable.path("value").asText()));
        return variables;
    }

    @Test
    @Order(5)
    @DisplayName("the upstream webhook is Ready and injects the Workload Identity variables and token volume into"
            + " labelled pods only")
    void theWebhookMutatesLabelledPods() throws Exception {
        long start = System.nanoTime();
        AksTestPolling.until(Duration.ofMinutes(2), () -> {
            try {
                return admin().get("/apis/apps/v1/namespaces/kube-system/deployments/"
                        + "azure-wi-webhook-controller-manager").path("status").path("readyReplicas").asInt() > 0;
            } catch (Exception e) {
                return false;
            }
        });
        long readySeconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();
        assertEquals(201, admin().send("POST", "/api/v1/namespaces", "{\"apiVersion\": \"v1\", \"kind\": "
                + "\"Namespace\", \"metadata\": {\"name\": \"wi\"}}"));
        assertEquals(201, admin().send("POST", "/api/v1/namespaces/wi/serviceaccounts", MAPPER.writeValueAsString(
                Map.of("apiVersion", "v1", "kind", "ServiceAccount", "metadata", Map.of("name", "app",
                        "annotations", Map.of("azure.workload.identity/client-id", CLIENT_ID))))));
        AksTestPolling.until(Duration.ofSeconds(60), () -> {
            try {
                return admin().send("POST", "/api/v1/namespaces/wi/pods", podJson("labelled", true)) == 201;
            } catch (Exception e) {
                return false;
            }
        });
        JsonNode labelled = admin().get("/api/v1/namespaces/wi/pods/labelled").path("spec");
        Map<String, String> injected = variables(labelled.path("containers").path(0));
        assertEquals(CLIENT_ID, injected.get("AZURE_CLIENT_ID"), labelled.toString());
        assertEquals(TENANT, injected.get("AZURE_TENANT_ID"));
        assertEquals("https://login.microsoftonline.com/", injected.get("AZURE_AUTHORITY_HOST"));
        assertTrue(injected.get("AZURE_FEDERATED_TOKEN_FILE")
                .matches("/var/run/secrets/azure/wi/[0-9a-f]+/token/azure-identity-token"), injected.toString());
        boolean projected = false;
        for (JsonNode volume : labelled.path("volumes")) {
            for (JsonNode source : volume.path("projected").path("sources")) {
                if ("api://AzureADTokenExchange".equals(source.path("serviceAccountToken").path("audience").asText())) {
                    projected = true;
                }
            }
        }
        assertTrue(projected, labelled.path("volumes").toString());
        assertTrue(readySeconds <= 30, "the webhook took " + readySeconds + " s after the cluster was ready");

        assertEquals(201, admin().send("POST", "/api/v1/namespaces/wi/pods", podJson("plain", false)));
        JsonNode plain = admin().get("/api/v1/namespaces/wi/pods/plain").path("spec");
        assertTrue(variables(plain.path("containers").path(0)).isEmpty(), plain.toString());
    }
}
