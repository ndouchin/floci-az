package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A delegated access token names the API it was requested for as {@code aud}, as Entra does
 * ({@code access-token-claims-reference.md}: "In v2.0 tokens, this value is always the client ID of the API"),
 * and keeps the client id only when the request names no resource.
 */
@QuarkusTest
class DelegatedAudienceTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String V2_TOKEN = "/" + TENANT + "/oauth2/v2.0/token";
    private static final String V1_TOKEN = "/" + TENANT + "/oauth2/token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RequestSpecification password() {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("username", EntraStore.DEV_USER_UPN)
                .formParam("password", "ignored");
    }

    private static JsonNode claims(String token) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    @Test
    void aDefaultScopeOnAnApiGivesThatApiAsAudience() throws Exception {
        String token = password().formParam("scope", "api://floci-test-api/.default openid")
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
        assertEquals("api://floci-test-api", claims(token).path("aud").asText());
    }

    @Test
    void aNamedScopeOnAnApiGivesThatApiAsAudience() throws Exception {
        String token = password().formParam("scope", "openid https://floci-test.example/data.read")
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
        assertEquals("https://floci-test.example", claims(token).path("aud").asText());
    }

    @Test
    void onlyOidcScopesKeepTheClientIdAsAudience() throws Exception {
        String token = password().formParam("scope", "openid profile")
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
        assertEquals(EntraStore.DEV_CLIENT_ID, claims(token).path("aud").asText());
    }

    @Test
    void theV1ResourceWinsOverTheScopeForTheAudience() throws Exception {
        String token = password().formParam("resource", "api://floci-test-api")
                .formParam("scope", "openid")
                .post(V1_TOKEN).then().statusCode(200).extract().path("access_token");
        assertEquals("api://floci-test-api", claims(token).path("aud").asText());
    }

    @Test
    void userInfoStillAcceptsATokenWhoseAudienceIsTheClient() {
        String token = password().formParam("scope", "openid profile")
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
        given().auth().oauth2(token).get("/oidc/userinfo").then().statusCode(200);
    }

    @Test
    void aRefreshedTokenKeepsTheApiAudience() throws Exception {
        String refresh = password().formParam("scope", "api://floci-test-api/read offline_access")
                .post(V2_TOKEN).then().statusCode(200).extract().path("refresh_token");
        String token = given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "refresh_token")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("refresh_token", refresh)
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
        assertEquals("api://floci-test-api", claims(token).path("aud").asText());
    }
}
