package io.floci.az.services.aks;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * The SubjectAccessReview webhook of an Azure RBAC cluster, answering as AKS's guard does
 * ({@code authz/providers/azure/azure.go}, {@code rbac/checkaccessreqhelper.go}): NoOpinion for {@code system:} users
 * and for users without {@code extra.oid}, an allow naming the assignment, or a denial with guard's reason.
 */
@QuarkusTest
@TestProfile(AksHandlerTest.MockedProfile.class)
@DisplayName("AKS authorization webhook: SubjectAccessReview with Azure RBAC")
class AksAccessReviewWebhookTest {

    private static final String SUB = "test-sub-aks-authz";
    private static final String RG = "rg-authz";
    private static final String RG_SCOPE = "/subscriptions/" + SUB + "/resourceGroups/" + RG;
    private static final String CLUSTER = RG_SCOPE + "/providers/Microsoft.ContainerService/managedClusters/aks1";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String READER = "7f6c6a51-bcf8-42ba-9220-52d62157d7db";
    private static final String WRITER = "a7ffa36f-339b-4b5c-8bdf-e2c188b2c0eb";
    private static final String USER = "5d1c3a2b-1111-4222-8333-944455556666";
    private static final String GROUP = "6e2d4b3c-2222-4333-8444-a55566667777";
    private static final String DENIED = "User does not have access to the resource in Azure. Update role assignment "
            + "to allow access.";

    @Inject
    AksHandler handler;

    private String instanceId;

    @BeforeEach
    void createAnAzureRbacCluster() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"properties\": {\"aadProfile\": {\"managed\": true, "
                        + "\"enableAzureRBAC\": true}}}")
                .put(CLUSTER + "?api-version=2026-08-01").then().statusCode(201);
        instanceId = handler.findCluster(SUB, RG, "aks1").orElseThrow().getInstanceId();
        assign(CLUSTER + "/namespaces/ns1", "aaaaaaaa-0000-4000-8000-000000000001", READER, USER);
        assign(CLUSTER, "aaaaaaaa-0000-4000-8000-000000000002", WRITER, GROUP);
    }

    private static void assign(String scope, String name, String role, String principal) {
        given().contentType("application/json")
                .body("{\"properties\": {\"roleDefinitionId\": \"/subscriptions/" + SUB + AUTH + "/roleDefinitions/"
                        + role + "\", \"principalId\": \"" + principal + "\"}}")
                .put(scope + AUTH + "/roleAssignments/" + name + "?api-version=2022-04-01").then().statusCode(201);
    }

    private ValidatableResponse review(String user, String groups, String oid, String attributes) {
        String extra = oid == null ? "" : ", \"extra\": {\"oid\": [\"" + oid + "\"]}";
        return given().contentType("application/json")
                .body("{\"apiVersion\": \"authorization.k8s.io/v1\", \"kind\": \"SubjectAccessReview\", \"spec\": {"
                        + attributes + ", \"user\": \"" + user + "\", \"groups\": " + groups + extra + "}}")
                .post("/_floci/aks/clusters/" + instanceId + "/subject-access-review")
                .then().statusCode(200)
                .body("apiVersion", equalTo("authorization.k8s.io/v1"))
                .body("kind", equalTo("SubjectAccessReview"));
    }

    private static String pods(String namespace, String verb) {
        return "\"resourceAttributes\": {\"namespace\": \"" + namespace + "\", \"verb\": \"" + verb
                + "\", \"group\": \"\", \"resource\": \"pods\"}";
    }

    @Test
    void aReaderAtANamespaceListsItsPods() {
        review("alice@contoso.test", "[]", USER, pods("ns1", "list"))
                .body("status.allowed", equalTo(true))
                .body("status.reason", startsWith("Access allowed by Azure RBAC Role Assignment "));
    }

    @Test
    void aReaderIsDeniedInAnotherNamespaceAndOnSecrets() {
        review("alice@contoso.test", "[]", USER, pods("ns2", "list"))
                .body("status.allowed", equalTo(false))
                .body("status.denied", equalTo(true))
                .body("status.reason", equalTo(DENIED));
        review("alice@contoso.test", "[]", USER,
                "\"resourceAttributes\": {\"namespace\": \"ns1\", \"verb\": \"list\", \"resource\": \"secrets\"}")
                .body("status.denied", equalTo(true));
    }

    @Test
    void aGroupAssignmentGrantsItsMembers() {
        review("bob@contoso.test", "[\"" + GROUP + "\", \"system:authenticated\"]", "aaaaaaaa-0000-4000-8000-00000000000b",
                "\"resourceAttributes\": {\"namespace\": \"ns2\", \"verb\": \"create\", \"group\": \"apps\", "
                        + "\"resource\": \"deployments\"}")
                .body("status.allowed", equalTo(true));
    }

    @Test
    void systemUsersAndUsersWithoutAnOidGetNoOpinion() {
        review("system:kube-controller-manager", "[\"system:authenticated\"]", null, pods("ns1", "list"))
                .body("status.allowed", equalTo(false))
                .body("status.denied", nullValue());
        review("k3s-cloud-controller-manager", "[]", null, pods("ns1", "list"))
                .body("status.allowed", equalTo(false))
                .body("status.denied", nullValue());
    }
}
