package io.floci.az.services.aks;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code aadProfile} and {@code disableLocalAccounts} as the AKS spec and docs define them: the RP fills
 * {@code tenantID} from the subscription and returns {@code adminGroupObjectIDs: null} when none were sent
 * ({@code ManagedClustersCreate_UpdateWithEnableAzureRBAC.json}), Entra integration can't be disabled once enabled
 * ({@code entra-id-control-plane-authentication.md}), and without Entra, disabling local accounts makes both
 * credentials fail ({@code local-accounts.md}).
 */
@QuarkusTest
@TestProfile(AksHandlerTest.MockedProfile.class)
@DisplayName("AksHandler: aadProfile and disableLocalAccounts (mocked mode)")
class AksAadProfileTest {

    private static final String SUB = "test-sub-aks-aad";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/rg-aad/providers/Microsoft.ContainerService";
    private static final String API = "?api-version=2026-08-01";
    private static final String DEFAULT_TENANT = "00000000-0000-0000-0000-000000000002";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static ValidatableResponse put(String name, String properties) {
        return given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"properties\": " + properties + "}")
                .when().put(BASE + "/managedClusters/" + name + API).then();
    }

    @Test
    void theRpFillsTheTenantAndReturnsNullAdminGroups() {
        put("aad1", "{\"aadProfile\": {\"managed\": true, \"enableAzureRBAC\": true}}").statusCode(201)
                .body("properties.aadProfile.managed", equalTo(true))
                .body("properties.aadProfile.enableAzureRBAC", equalTo(true))
                .body("properties.aadProfile.tenantID", equalTo(DEFAULT_TENANT))
                .body("properties.aadProfile", hasKey("adminGroupObjectIDs"))
                .body("properties.aadProfile.adminGroupObjectIDs", nullValue());
    }

    @Test
    void aLaterPutWithoutTheProfileKeepsIt() {
        put("aad2", "{\"aadProfile\": {\"managed\": true, \"adminGroupObjectIDs\": "
                + "[\"44444444-4444-4444-4444-444444444444\"]}}").statusCode(201);
        put("aad2", "{\"dnsPrefix\": \"aad2-dns\"}").statusCode(200)
                .body("properties.aadProfile.managed", equalTo(true))
                .body("properties.aadProfile.enableAzureRBAC", equalTo(false))
                .body("properties.aadProfile.adminGroupObjectIDs", contains("44444444-4444-4444-4444-444444444444"));
        given().when().get(BASE + "/managedClusters/aad2" + API).then().statusCode(200)
                .body("properties.aadProfile.managed", equalTo(true));
    }

    @Test
    void entraIntegrationCannotBeTurnedOff() {
        put("aad3", "{\"aadProfile\": {\"managed\": true}}").statusCode(201);
        put("aad3", "{\"aadProfile\": {\"managed\": false}}").statusCode(200)
                .body("properties.aadProfile.managed", equalTo(true));
    }

    @Test
    void theLegacyServerSecretIsNeverReturned() {
        put("aad4", "{\"aadProfile\": {\"clientAppID\": \"c\", \"serverAppID\": \"s\", \"serverAppSecret\": \"x\"}}")
                .statusCode(201)
                .body("properties.aadProfile.clientAppID", equalTo("c"))
                .body("properties.aadProfile.serverAppID", equalTo("s"))
                .body("properties.aadProfile", not(hasKey("serverAppSecret")));
    }

    @Test
    void withoutEntraDisablingLocalAccountsRefusesBothCredentials() {
        put("aad5", "{\"disableLocalAccounts\": true}").statusCode(201);
        for (String credential : new String[]{"listClusterUserCredential", "listClusterAdminCredential"}) {
            given().when().post(BASE + "/managedClusters/aad5/" + credential + API).then().statusCode(400)
                    .body("error.code", equalTo("BadRequest"));
        }
    }

    @Test
    void withEntraDisablingLocalAccountsRefusesOnlyTheAdminCredential() {
        put("aad6", "{\"disableLocalAccounts\": true, \"aadProfile\": {\"managed\": true}}").statusCode(201);
        given().when().post(BASE + "/managedClusters/aad6/listClusterAdminCredential" + API).then().statusCode(400)
                .body("error.code", equalTo("BadRequest"));
        given().when().post(BASE + "/managedClusters/aad6/listClusterUserCredential" + API).then().statusCode(200)
                .body("kubeconfigs[0].name", equalTo("clusterUser"));
    }

    @Test
    void anEntraClusterUserCredentialIsAKubeloginExecAndFormatAzureTheAuthProvider() {
        put("aad7", "{\"aadProfile\": {\"managed\": true}}").statusCode(201);
        String exec = given().when().post(BASE + "/managedClusters/aad7/listClusterUserCredential" + API)
                .then().statusCode(200).extract().path("kubeconfigs[0].value");
        String execYaml = new String(Base64.getDecoder().decode(exec), StandardCharsets.UTF_8);
        assertTrue(execYaml.contains("command: kubelogin"), execYaml);
        assertTrue(execYaml.contains("- " + DEFAULT_TENANT), execYaml);
        assertTrue(execYaml.contains("server: "), execYaml);
        String azure = given().queryParam("format", "azure")
                .when().post(BASE + "/managedClusters/aad7/listClusterUserCredential" + API)
                .then().statusCode(200).extract().path("kubeconfigs[0].value");
        String azureYaml = new String(Base64.getDecoder().decode(azure), StandardCharsets.UTF_8);
        assertTrue(azureYaml.contains("name: azure"), azureYaml);
        assertTrue(azureYaml.contains("apiserver-id: " + AksEntra.SERVER_APP_ID), azureYaml);
        String admin = given().when().post(BASE + "/managedClusters/aad7/listClusterAdminCredential" + API)
                .then().statusCode(200).extract().path("kubeconfigs[0].value");
        assertFalse(new String(Base64.getDecoder().decode(admin), StandardCharsets.UTF_8).contains("kubelogin"));
    }
}
