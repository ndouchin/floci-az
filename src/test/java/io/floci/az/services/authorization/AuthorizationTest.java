package io.floci.az.services.authorization;

import io.floci.az.services.entra.EntraStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;

/** The az CLI and azurerm calls for role definitions and role assignments, at the scopes AKS uses. */
@QuarkusTest
@DisplayName("Microsoft.Authorization role definitions and role assignments")
class AuthorizationTest {

    private static final String SUB = "00000000-0000-0000-0000-0000000000a1";
    private static final String API = "?api-version=2022-04-01";
    private static final String AUTH = "/providers/Microsoft.Authorization";
    private static final String SUB_SCOPE = "/subscriptions/" + SUB;
    private static final String RG_SCOPE = SUB_SCOPE + "/resourceGroups/rg-authz";
    private static final String CLUSTER = RG_SCOPE + "/providers/Microsoft.ContainerService/managedClusters/aks1";
    private static final String NS1 = CLUSTER + "/namespaces/ns1";
    private static final String READER = "7f6c6a51-bcf8-42ba-9220-52d62157d7db";
    private static final String WRITER = "a7ffa36f-339b-4b5c-8bdf-e2c188b2c0eb";
    private static final String CLUSTER_ADMIN = "b1ff04bb-8a4e-4dc4-8eb5-8693973ce19b";
    private static final String PRINCIPAL = "5d1c3a2b-1111-4222-8333-944455556666";
    private static final String A1 = "aaaaaaaa-0000-4000-8000-000000000001";
    private static final String A2 = "aaaaaaaa-0000-4000-8000-000000000002";
    private static final String A3 = "aaaaaaaa-0000-4000-8000-000000000003";
    private static final String A4 = "aaaaaaaa-0000-4000-8000-000000000004";

    @BeforeEach
    void reset() {
        given().when().post("/_admin/reset").then().statusCode(204);
    }

    private static String roleDefinitionId(String guid) {
        return SUB_SCOPE + AUTH + "/roleDefinitions/" + guid;
    }

    private static ValidatableResponse assign(String scope, String name, String role, String principal) {
        return given().contentType("application/json")
                .body("{\"properties\": {\"roleDefinitionId\": \"" + roleDefinitionId(role) + "\", \"principalId\": \""
                        + principal + "\", \"principalType\": \"User\"}}")
                .when().put(scope + AUTH + "/roleAssignments/" + name + API)
                .then();
    }

    @Test
    void theAzCliRoleNameLookupAtAClusterScopeFindsTheBuiltInRole() {
        given().queryParam("$filter", "roleName eq 'Azure Kubernetes Service RBAC Reader'")
                .when().get(CLUSTER + AUTH + "/roleDefinitions" + API)
                .then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo(READER))
                .body("value[0].id", equalTo(roleDefinitionId(READER)))
                .body("value[0].type", equalTo("Microsoft.Authorization/roleDefinitions"))
                .body("value[0].properties.roleName", equalTo("Azure Kubernetes Service RBAC Reader"))
                .body("value[0].properties.type", equalTo("BuiltInRole"))
                .body("value[0].properties.assignableScopes", contains("/"))
                .body("value[0].properties.permissions[0].dataActions",
                        hasItem("Microsoft.ContainerService/managedClusters/pods/read"));
    }

    @Test
    void aBuiltInRoleIsReadableByIdAtSubscriptionAndTenantScope() {
        given().when().get(roleDefinitionId(CLUSTER_ADMIN) + API)
                .then().statusCode(200)
                .body("id", equalTo(roleDefinitionId(CLUSTER_ADMIN)))
                .body("properties.roleName", equalTo("Azure Kubernetes Service RBAC Cluster Admin"));
        given().when().get(AUTH + "/roleDefinitions/" + CLUSTER_ADMIN + API)
                .then().statusCode(200)
                .body("id", equalTo(AUTH + "/roleDefinitions/" + CLUSTER_ADMIN));
        given().when().get(AUTH + "/roleDefinitions/" + PRINCIPAL + API)
                .then().statusCode(404)
                .body("error.code", equalTo("RoleDefinitionDoesNotExist"));
    }

    @Test
    void anAssignmentAtANamespaceScopeIsCreatedReadAndDeleted() {
        assign(NS1, A1, READER, PRINCIPAL).statusCode(201)
                .body("id", equalTo(NS1 + AUTH + "/roleAssignments/" + A1))
                .body("name", equalTo(A1))
                .body("type", equalTo("Microsoft.Authorization/roleAssignments"))
                .body("properties.scope", equalTo(NS1))
                .body("properties.roleDefinitionId", equalTo(roleDefinitionId(READER)))
                .body("properties.principalId", equalTo(PRINCIPAL))
                .body("properties.principalType", equalTo("User"))
                .body("properties.createdOn", notNullValue());
        assign(NS1, A1, READER, PRINCIPAL).statusCode(201);
        given().when().get(NS1 + AUTH + "/roleAssignments/" + A1 + API)
                .then().statusCode(200).body("properties.scope", equalTo(NS1));
        given().when().delete(NS1 + AUTH + "/roleAssignments/" + A1 + API)
                .then().statusCode(200).body("name", equalTo(A1));
        given().when().delete(NS1 + AUTH + "/roleAssignments/" + A1 + API).then().statusCode(204);
        given().when().get(NS1 + AUTH + "/roleAssignments/" + A1 + API)
                .then().statusCode(404).body("error.code", equalTo("RoleAssignmentNotFound"));
    }

    @Test
    void theSamePrincipalRoleAndScopeUnderANewNameConflicts() {
        assign(NS1, A1, READER, PRINCIPAL).statusCode(201);
        assign(NS1, A2, READER, PRINCIPAL).statusCode(409)
                .body("error.code", equalTo("RoleAssignmentExists"))
                .body("error.message", equalTo("The role assignment already exists."));
    }

    @Test
    void anAssignmentCannotBeRepointed() {
        assign(NS1, A1, READER, PRINCIPAL).statusCode(201);
        assign(NS1, A1, WRITER, PRINCIPAL).statusCode(400)
                .body("error.code", equalTo("RoleAssignmentUpdateNotPermitted"));
    }

    @Test
    void anAssignmentNameMustBeAGuidAndItsRoleMustExist() {
        assign(NS1, "not-a-guid", READER, PRINCIPAL).statusCode(400)
                .body("error.code", equalTo("InvalidRoleAssignmentId"))
                .body("error.message", equalTo("The role assignment ID 'not-a-guid' is not valid. "
                        + "The role assignment ID must be a GUID."));
        assign(NS1, A1, PRINCIPAL, PRINCIPAL).statusCode(400)
                .body("error.code", equalTo("RoleDefinitionDoesNotExist"));
        given().contentType("application/json").body("{\"properties\": {\"principalId\": \"" + PRINCIPAL + "\"}}")
                .when().put(NS1 + AUTH + "/roleAssignments/" + A1 + API)
                .then().statusCode(400);
    }

    @Test
    void listFiltersFollowTheirScopeAndPrincipalRules() {
        assign(RG_SCOPE, A1, CLUSTER_ADMIN, PRINCIPAL).statusCode(201);
        assign(CLUSTER, A2, WRITER, PRINCIPAL).statusCode(201);
        assign(NS1, A3, READER, PRINCIPAL).statusCode(201);
        assign(SUB_SCOPE, A4, READER, EntraStore.DEV_GROUP_OBJECT_ID).statusCode(201);

        given().queryParam("$filter", "atScope()").when().get(CLUSTER + AUTH + "/roleAssignments" + API)
                .then().statusCode(200).body("value.name", containsInAnyOrder(A1, A2, A4));
        given().when().get(CLUSTER + AUTH + "/roleAssignments" + API)
                .then().statusCode(200).body("value.name", containsInAnyOrder(A1, A2, A3, A4));
        given().queryParam("$filter", "principalId eq '" + PRINCIPAL + "'")
                .when().get(SUB_SCOPE + AUTH + "/roleAssignments" + API)
                .then().statusCode(200).body("value.name", containsInAnyOrder(A1, A2, A3));
        given().queryParam("$filter", "assignedTo('" + EntraStore.DEV_USER_OBJECT_ID + "')")
                .when().get(SUB_SCOPE + AUTH + "/roleAssignments" + API)
                .then().statusCode(200).body("value.name", contains(A4));
        given().queryParam("$filter", "atScope() and assignedTo('" + PRINCIPAL + "')")
                .when().get(RG_SCOPE + AUTH + "/roleAssignments" + API)
                .then().statusCode(200).body("value.name", contains(A1));
    }

    @Test
    void customRolesAreCreatedListedAndNamedUniquely() {
        String guid = "cccccccc-0000-4000-8000-000000000001";
        String body = """
                {"properties": {"roleName": "Pod reader", "description": "Reads pods", "type": "CustomRole",
                  "permissions": [{"actions": [], "notActions": [],
                                   "dataActions": ["Microsoft.ContainerService/managedClusters/pods/read"],
                                   "notDataActions": []}],
                  "assignableScopes": ["%s"]}}""".formatted(SUB_SCOPE);
        given().contentType("application/json").body(body)
                .when().put(SUB_SCOPE + AUTH + "/roleDefinitions/" + guid + API)
                .then().statusCode(201)
                .body("id", equalTo(roleDefinitionId(guid)))
                .body("properties.type", equalTo("CustomRole"))
                .body("properties.roleName", equalTo("Pod reader"));
        given().queryParam("$filter", "type eq 'CustomRole'")
                .when().get(SUB_SCOPE + AUTH + "/roleDefinitions" + API)
                .then().statusCode(200).body("value.name", contains(guid));
        given().queryParam("$filter", "type eq 'BuiltInRole'")
                .when().get(SUB_SCOPE + AUTH + "/roleDefinitions" + API)
                .then().statusCode(200).body("value.properties.type", everyItem(equalTo("BuiltInRole")))
                .body("value.name", not(hasItem(guid)));
        given().contentType("application/json").body(body)
                .when().put(SUB_SCOPE + AUTH + "/roleDefinitions/cccccccc-0000-4000-8000-000000000002" + API)
                .then().statusCode(409).body("error.code", equalTo("RoleDefinitionWithSameNameExists"));
        assign(NS1, A1, guid, PRINCIPAL).statusCode(201);
        given().when().delete(SUB_SCOPE + AUTH + "/roleDefinitions/" + guid + API)
                .then().statusCode(409).body("error.code", equalTo("RoleDefinitionHasAssignments"));
        given().when().delete(NS1 + AUTH + "/roleAssignments/" + A1 + API).then().statusCode(200);
        given().when().delete(SUB_SCOPE + AUTH + "/roleDefinitions/" + guid + API).then().statusCode(200);
        given().queryParam("$filter", "type eq 'CustomRole'")
                .when().get(SUB_SCOPE + AUTH + "/roleDefinitions" + API)
                .then().statusCode(200).body("value", empty());
    }
}
