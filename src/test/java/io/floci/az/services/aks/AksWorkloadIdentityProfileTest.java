package io.floci.az.services.aks;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code oidcIssuerProfile} and {@code securityProfile.workloadIdentity}: new clusters get the OIDC issuer from
 * Kubernetes 1.34, it can't be disabled once enabled ({@code use-oidc-issuer.md}), its URL is
 * {@code https://{region}.oic.prod-aks.azure.com/{tenant}/{uuid}/} with an immutable uuid per cluster
 * ({@code workload-identity-deploy-cluster.md}), and Workload Identity needs it.
 */
@QuarkusTest
@TestProfile(AksHandlerTest.MockedProfile.class)
@DisplayName("AksHandler: OIDC issuer and Workload Identity profiles (mocked mode)")
class AksWorkloadIdentityProfileTest {

    private static final String BASE = "/subscriptions/test-sub-aks-wi/resourceGroups/rg-wi"
            + "/providers/Microsoft.ContainerService/managedClusters/";
    private static final String API = "?api-version=2026-08-01";
    private static final String ISSUER = "https://eastus\\.oic\\.prod-aks\\.azure\\.com/"
            + "00000000-0000-0000-0000-000000000002/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static ValidatableResponse put(String name, String properties) {
        return given().contentType("application/json")
                .body("{\"location\": \"eastus\", \"properties\": " + properties + "}")
                .when().put(BASE + name + API).then();
    }

    @Test
    void aNewClusterGetsTheIssuerAndKeepsItsUrl() {
        String url = put("wi1", "{}").statusCode(201)
                .body("properties.oidcIssuerProfile.enabled", equalTo(true))
                .body("properties.oidcIssuerProfile.issuerURL", matchesPattern(ISSUER))
                .extract().path("properties.oidcIssuerProfile.issuerURL");
        String again = put("wi1", "{\"oidcIssuerProfile\": {\"enabled\": false}}").statusCode(200)
                .body("properties.oidcIssuerProfile.enabled", equalTo(true))
                .extract().path("properties.oidcIssuerProfile.issuerURL");
        assertEquals(url, again);
    }

    @Test
    void anIssuerTurnedOffAtCreationCanBeTurnedOnLater() {
        put("wi2", "{\"oidcIssuerProfile\": {\"enabled\": false}}").statusCode(201)
                .body("properties.oidcIssuerProfile.enabled", equalTo(false))
                .body("properties.oidcIssuerProfile", not(hasKey("issuerURL")));
        put("wi2", "{\"oidcIssuerProfile\": {\"enabled\": true}}").statusCode(200)
                .body("properties.oidcIssuerProfile.issuerURL", matchesPattern(ISSUER));
    }

    @Test
    void workloadIdentityIsStoredWithTheRestOfTheSecurityProfile() {
        put("wi3", "{\"securityProfile\": {\"workloadIdentity\": {\"enabled\": true}, "
                + "\"imageCleaner\": {\"enabled\": true, \"intervalHours\": 24}}}").statusCode(201)
                .body("properties.securityProfile.workloadIdentity.enabled", equalTo(true))
                .body("properties.securityProfile.imageCleaner.intervalHours", equalTo(24));
        put("wi3", "{\"dnsPrefix\": \"wi3\"}").statusCode(200)
                .body("properties.securityProfile.workloadIdentity.enabled", equalTo(true));
    }

    @Test
    void workloadIdentityNeedsTheIssuer() {
        put("wi4", "{\"oidcIssuerProfile\": {\"enabled\": false}, "
                + "\"securityProfile\": {\"workloadIdentity\": {\"enabled\": true}}}").statusCode(400)
                .body("error.message", equalTo("Enabling workload identity requires enabling OIDC issuer."));
    }
}
