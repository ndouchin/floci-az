package io.floci.az.services.entra;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * Instance discovery as MSAL asks it of {@code login.microsoftonline.com} before trusting an authority: the pods of an
 * emulated AKS cluster reach floci-az under that name.
 */
@QuarkusTest
class InstanceDiscoveryEndpointTest {

    @Test
    void theAnswerNamesTheRequestHostAndTheTenantDiscoveryDocument() {
        given().header("Host", "login.microsoftonline.com")
                .queryParam("api-version", "1.1")
                .queryParam("authorization_endpoint",
                        "https://login.microsoftonline.com/00000000-0000-0000-0000-000000000002/oauth2/v2.0/authorize")
                .get("/common/discovery/instance")
                .then().statusCode(200)
                .body("api-version", equalTo("1.1"))
                .body("tenant_discovery_endpoint", equalTo("http://login.microsoftonline.com/"
                        + "00000000-0000-0000-0000-000000000002/v2.0/.well-known/openid-configuration"))
                .body("metadata[0].preferred_network", equalTo("login.microsoftonline.com"))
                .body("metadata[0].aliases", contains("login.microsoftonline.com"));
    }
}
