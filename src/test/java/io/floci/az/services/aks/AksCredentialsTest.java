package io.floci.az.services.aks;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The user and admin credentials AKS hands out, and disableLocalAccounts (mocked mode). */
@QuarkusTest
@TestProfile(AksHandlerTest.MockedProfile.class)
@DisplayName("AksHandler: clusterUser and clusterAdmin credentials, disableLocalAccounts (mocked mode)")
class AksCredentialsTest {

    private static final String CLUSTERS = "/subscriptions/test-sub-aks-creds/resourceGroups/rg-creds"
            + "/providers/Microsoft.ContainerService/managedClusters/";
    private static final String API = "?api-version=2025-10-01";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static void put(String name, String properties) {
        given().contentType("application/json").body("{\"location\":\"eastus\",\"properties\":" + properties + "}")
                .put(CLUSTERS + name + API).then().statusCode(anyOf(is(200), is(201)));
    }

    private static String kubeconfig(String name, String operation, String expectedName) {
        String value = given().contentType("application/json").post(CLUSTERS + name + "/" + operation + API)
                .then().statusCode(200)
                .body("kubeconfigs", hasSize(1))
                .body("kubeconfigs[0].name", equalTo(expectedName))
                .extract().path("kubeconfigs[0].value");
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the user credential is clusterUser, its user clusterUser_<rg>_<cluster>, its context the cluster")
    void userCredential() {
        put("c1", "{}");

        String yaml = kubeconfig("c1", "listClusterUserCredential", "clusterUser");

        assertTrue(yaml.contains("  name: c1\n"), yaml);
        assertTrue(yaml.contains("current-context: c1\n"), yaml);
        assertTrue(yaml.contains("    user: clusterUser_rg-creds_c1\n"), yaml);
        assertTrue(yaml.contains("- name: clusterUser_rg-creds_c1\n"), yaml);
    }

    @Test
    @DisplayName("the admin credential is clusterAdmin, its user clusterAdmin_<rg>_<cluster>")
    void adminCredential() {
        put("c2", "{}");

        String yaml = kubeconfig("c2", "listClusterAdminCredential", "clusterAdmin");

        assertTrue(yaml.contains("current-context: c2\n"), yaml);
        assertTrue(yaml.contains("    user: clusterAdmin_rg-creds_c2\n"), yaml);
    }

    @Test
    @DisplayName("disableLocalAccounts refuses the admin credential with AKS's BadRequest, the Entra user one still works")
    void disableLocalAccountsRefusesTheAdminCredential() {
        put("c3", "{\"disableLocalAccounts\":true,\"aadProfile\":{\"managed\":true}}");

        given().contentType("application/json").post(CLUSTERS + "c3/listClusterAdminCredential" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("BadRequest"))
                .body("error.message", equalTo("Getting static credential is not allowed because this cluster is"
                        + " set to disable local accounts."));
        kubeconfig("c3", "listClusterUserCredential", "clusterUser");
    }

    @Test
    @DisplayName("enabling local accounts again gives the admin credential back")
    void enablingLocalAccountsAgain() {
        put("c4", "{\"disableLocalAccounts\":true}");
        put("c4", "{\"disableLocalAccounts\":false}");

        kubeconfig("c4", "listClusterAdminCredential", "clusterAdmin");
    }
}
