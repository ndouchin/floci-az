package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An access token's shape follows the resource's {@code requestedAccessTokenVersion}, not the endpoint the client
 * called ({@code access-tokens.md}). The AKS server application asks for v1.0 tokens: AKS's token webhook reads the
 * v1.0 issuer, {@code upn} and the {@code groups} claim.
 */
@QuarkusTest
class FirstPartyTokenVersionTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String V2_TOKEN = "/" + TENANT + "/oauth2/v2.0/token";
    private static final String AKS_SERVER = "6dae42f8-4368-4678-94ff-3960e28e3630";
    private static final String KUBELOGIN_CLIENT = "80faf920-1908-4b52-b5ef-a8e7bedfc67a";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode claims(String token) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    @Test
    void aUserTokenForTheAksServerHasTheV1ShapeAndGroups() throws Exception {
        String token = given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", KUBELOGIN_CLIENT)
                .formParam("username", EntraStore.DEV_USER_UPN)
                .formParam("password", "ignored")
                .formParam("scope", AKS_SERVER + "/.default openid profile offline_access")
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
        JsonNode claims = claims(token);
        assertEquals(AKS_SERVER, claims.path("aud").asText());
        assertEquals("1.0", claims.path("ver").asText());
        assertTrue(claims.path("iss").asText().endsWith("/" + TENANT + "/"), claims.path("iss").asText());
        assertEquals(KUBELOGIN_CLIENT, claims.path("appid").asText());
        assertFalse(claims.has("azp"));
        assertEquals(EntraStore.DEV_USER_UPN, claims.path("upn").asText());
        assertEquals(EntraStore.DEV_USER_UPN, claims.path("unique_name").asText());
        assertEquals(EntraStore.DEV_USER_OBJECT_ID, claims.path("oid").asText());
        assertTrue(texts(claims.path("groups")).contains(EntraStore.DEV_GROUP_OBJECT_ID), claims.toString());
    }

    @Test
    void anAppTokenForTheAksServerHasTheV1ShapeAndNamesTheServicePrincipal() throws Exception {
        String token = given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET)
                .formParam("scope", AKS_SERVER + "/.default")
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
        JsonNode claims = claims(token);
        assertEquals(AKS_SERVER, claims.path("aud").asText());
        assertEquals("1.0", claims.path("ver").asText());
        assertEquals("app", claims.path("idtyp").asText());
        assertEquals(EntraStore.DEV_SP_OBJECT_ID, claims.path("oid").asText());
        assertEquals(EntraStore.DEV_CLIENT_ID, claims.path("appid").asText());
        assertTrue(claims.path("iss").asText().endsWith("/" + TENANT + "/"), claims.path("iss").asText());
    }

    @Test
    void otherResourcesKeepTheEndpointVersion() throws Exception {
        String token = given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET)
                .formParam("scope", "api://floci-test-api/.default")
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
        JsonNode claims = claims(token);
        assertEquals("2.0", claims.path("ver").asText());
        assertFalse(claims.has("groups"));
    }
}
