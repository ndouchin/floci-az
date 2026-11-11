package io.floci.az.services.managedidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The exchange Workload Identity makes: a client credentials request whose {@code client_id} is a user-assigned
 * identity and whose {@code client_assertion} is a token of a trusted issuer gets an access token for that identity,
 * shaped like the ones IMDS mints; a mismatch gets the AADSTS code Entra answers with.
 */
@QuarkusTest
@DisplayName("Entra: federated credential exchange for user-assigned identities")
class WorkloadIdentityTokenExchangeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String IDENTITY = "/subscriptions/test-sub-wi-exchange/resourceGroups/rg-wi"
            + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities/app-identity";
    private static final String API = "?api-version=2024-11-30";
    private static final String SUBJECT = "system:serviceaccount:wi:app";

    private String clientId;
    private String principalId;

    @BeforeEach
    void createAnIdentityWithACredential() {
        given().post("/_admin/reset").then().statusCode(204);
        ValidatableResponse identity = given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .put(IDENTITY + API).then().statusCode(201);
        clientId = identity.extract().path("properties.clientId");
        principalId = identity.extract().path("properties.principalId");
        given().contentType("application/json")
                .body("{\"properties\": {\"issuer\": \"" + TestOidcIssuer.ISSUER + "\", \"subject\": \"" + SUBJECT
                        + "\", \"audiences\": [\"api://AzureADTokenExchange\"]}}")
                .put(IDENTITY + "/federatedIdentityCredentials/wi-app" + API).then().statusCode(201);
    }

    private ValidatableResponse exchange(String client, String assertion) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", client)
                .formParam("scope", "https://vault.azure.net/.default")
                .formParam("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                .formParam("client_assertion", assertion)
                .post("/" + TENANT + "/oauth2/v2.0/token").then();
    }

    @Test
    void aTrustedAssertionGetsATokenForTheIdentity() throws Exception {
        String token = exchange(clientId, TestOidcIssuer.token(SUBJECT)).statusCode(200)
                .extract().path("access_token");
        JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertEquals(principalId, claims.path("oid").asText());
        assertEquals(principalId, claims.path("sub").asText());
        assertEquals(clientId, claims.path("azp").asText());
        assertEquals("app", claims.path("idtyp").asText());
        assertEquals("https://vault.azure.net", claims.path("aud").asText());
        assertEquals(IDENTITY, claims.path("xms_mirid").asText());
    }

    @Test
    void anAssertionThatIsNotAJwtIsRefusedForAKnownIdentity() {
        exchange(clientId, "not-a-jwt").statusCode(400).body("error_codes", contains(50027));
    }

    @Test
    void anotherSubjectIsRefused() throws Exception {
        exchange(clientId, TestOidcIssuer.token("system:serviceaccount:wi:other")).statusCode(400)
                .body("error", equalTo("invalid_client")).body("error_codes", contains(700213));
    }

    @Test
    void withTheCredentialDeletedTheIssuerMatchesNoRecord() throws Exception {
        given().delete(IDENTITY + "/federatedIdentityCredentials/wi-app" + API).then().statusCode(200);
        exchange(clientId, TestOidcIssuer.token(SUBJECT)).statusCode(400).body("error_codes", contains(700211));
    }

    @Test
    void anUnknownClientKeepsThePermissivePath() {
        exchange("aaaaaaaa-0000-4000-8000-0000000000ff", "not-a-jwt").statusCode(200);
    }
}
