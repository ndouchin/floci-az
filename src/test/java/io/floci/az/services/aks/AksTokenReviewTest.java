package io.floci.az.services.aks;

import io.floci.az.services.entra.EntraStore;
import io.floci.az.services.entra.TokenIssuer;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * The TokenReview webhook an Entra cluster's API server calls, doing what AKS's token webhook (kubeguard/guard,
 * {@code auth/providers/azure/azure.go}) does: verify the signature, lifetime, tenant and audience (the AKS server
 * application), then answer username = {@code upn}, else {@code oid}, the token's groups, and {@code extra.oid}.
 */
@QuarkusTest
@TestProfile(AksHandlerTest.MockedProfile.class)
@DisplayName("AKS token webhook: TokenReview for Entra tokens")
class AksTokenReviewTest {

    private static final String SUB = "test-sub-aks-authn";
    private static final String RG = "rg-authn";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String V2_TOKEN = "/" + TENANT + "/oauth2/v2.0/token";
    private static final String AKS_SCOPE = AksEntra.SERVER_APP_ID + "/.default";

    @Inject
    AksHandler handler;

    @Inject
    TokenIssuer issuer;

    @TestHTTPResource("/")
    URL root;

    private String instanceId;

    @BeforeEach
    void createAnEntraCluster() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"properties\": {\"aadProfile\": {\"managed\": true}}}")
                .put("/subscriptions/" + SUB + "/resourceGroups/" + RG
                        + "/providers/Microsoft.ContainerService/managedClusters/aks1?api-version=2026-08-01")
                .then().statusCode(201);
        instanceId = handler.findCluster(SUB, RG, "aks1").orElseThrow().getInstanceId();
    }

    private ValidatableResponse review(String token) {
        return given().contentType("application/json")
                .body("{\"apiVersion\": \"authentication.k8s.io/v1\", \"kind\": \"TokenReview\", "
                        + "\"spec\": {\"token\": \"" + token + "\"}}")
                .post("/_floci/aks/clusters/" + instanceId + "/token-review")
                .then().statusCode(200)
                .body("apiVersion", equalTo("authentication.k8s.io/v1"))
                .body("kind", equalTo("TokenReview"));
    }

    private static String appToken(String scope) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "client_credentials")
                .formParam("client_id", EntraStore.DEV_CLIENT_ID)
                .formParam("client_secret", EntraStore.DEV_CLIENT_SECRET)
                .formParam("scope", scope)
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
    }

    private String base() {
        String url = root.toString();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Test
    void aServicePrincipalIsNamedByItsObjectId() {
        review(appToken(AKS_SCOPE))
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo(EntraStore.DEV_SP_OBJECT_ID))
                .body("status.user.extra.oid", contains(EntraStore.DEV_SP_OBJECT_ID));
    }

    @Test
    void aUserIsNamedByItsUpnWithItsGroups() {
        String token = given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", AksEntra.CLIENT_APP_ID)
                .formParam("username", EntraStore.DEV_USER_UPN)
                .formParam("password", "ignored")
                .formParam("scope", AKS_SCOPE + " openid offline_access")
                .post(V2_TOKEN).then().statusCode(200).extract().path("access_token");
        review(token)
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo(EntraStore.DEV_USER_UPN))
                .body("status.user.groups", hasItem(EntraStore.DEV_GROUP_OBJECT_ID))
                .body("status.user.extra.oid", contains(EntraStore.DEV_USER_OBJECT_ID));
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() {
        review(appToken("api://another-api/.default")).body("status.authenticated", equalTo(false));
    }

    @Test
    void aTokenFromAnotherTenantIsRefused() {
        String other = "aaaaaaaa-0000-4000-8000-00000000000f";
        String token = issuer.issue(new TokenIssuer.TokenSpec(other, base() + "/" + other + "/",
                AksEntra.SERVER_APP_ID, EntraStore.DEV_SP_OBJECT_ID, EntraStore.DEV_SP_OBJECT_ID,
                EntraStore.DEV_CLIENT_ID, null, "1.0", "app", 3600));
        review(token).body("status.authenticated", equalTo(false));
    }

    @Test
    void anExpiredTokenIsRefused() {
        String token = issuer.issue(new TokenIssuer.TokenSpec(TENANT, base() + "/" + TENANT + "/",
                AksEntra.SERVER_APP_ID, EntraStore.DEV_SP_OBJECT_ID, EntraStore.DEV_SP_OBJECT_ID,
                EntraStore.DEV_CLIENT_ID, null, "1.0", "app", -900));
        review(token).body("status.authenticated", equalTo(false));
    }

    @Test
    void aTokenSignedByAnotherKeyIsRefused() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        long now = Instant.now().getEpochSecond();
        String header = url.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"other\"}"
                .getBytes(StandardCharsets.UTF_8));
        String payload = url.encodeToString(("{\"aud\":\"" + AksEntra.SERVER_APP_ID + "\",\"iss\":\"" + base() + "/"
                + TENANT + "/\",\"tid\":\"" + TENANT + "\",\"oid\":\"x\",\"exp\":" + (now + 600) + ",\"nbf\":" + now
                + "}").getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        review(header + "." + payload + "." + url.encodeToString(signer.sign()))
                .body("status.authenticated", equalTo(false));
    }

    @Test
    void aServiceAccountTokenIsRefused() {
        review("not-a-jwt").body("status.authenticated", equalTo(false));
    }
}
