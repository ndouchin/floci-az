package io.floci.az.services.managedidentity;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * Federated identity credential rules and errors as Entra documents them
 * ({@code workload-identity-federation-considerations.md}, Errors; {@code federated-credential-configuration-considerations.md}),
 * and {@code claimsMatchingExpression} from {@code 2025-01-31-preview} (MSI spec).
 */
@QuarkusTest
@DisplayName("Federated identity credentials: names, audiences, uniqueness and limits")
class FederatedIdentityCredentialFidelityTest {

    private static final String BASE = "/subscriptions/test-sub-fic/resourceGroups/rg-fic"
            + "/providers/Microsoft.ManagedIdentity/userAssignedIdentities";
    private static final String API = "?api-version=2024-11-30";
    private static final String PREVIEW = "?api-version=2025-01-31-preview";
    private static final String ISSUER = "https://eastus.oic.prod-aks.azure.com/t/u/";

    @BeforeEach
    void createAnIdentity() {
        given().post("/_admin/reset").then().statusCode(204);
        given().contentType("application/json").body("{\"location\": \"eastus\"}")
                .put(BASE + "/id1" + API).then().statusCode(201);
    }

    private static ValidatableResponse put(String name, String properties, String api) {
        return given().contentType("application/json").body("{\"properties\": " + properties + "}")
                .put(BASE + "/id1/federatedIdentityCredentials/" + name + api).then();
    }

    private static String credential(String subject) {
        return "{\"issuer\": \"" + ISSUER + "\", \"subject\": \"" + subject
                + "\", \"audiences\": [\"api://AzureADTokenExchange\"]}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-abc", "_abc", "abc.def", "abc!"})
    void aNameOutsideTheRuleIsRefused(String name) {
        put(name, credential("system:serviceaccount:wi:app"), API).statusCode(400)
                .body("error.message", equalTo("Federated Identity Credential name '" + name + "' is invalid."));
    }

    @Test
    void aNameOf120AlphanumericsDashesAndUnderscoresIsAccepted() {
        put("a" + "b-_".repeat(39) + "cd", credential("system:serviceaccount:wi:app"), API).statusCode(201);
    }

    @Test
    void exactlyOneAudienceIsAccepted() {
        put("fic1", "{\"issuer\": \"" + ISSUER + "\", \"subject\": \"s\", \"audiences\": [\"a\", \"b\"]}", API)
                .statusCode(400)
                .body("error.message", equalTo("Federated identity credentials must have exactly one audience."));
        put("fic1", "{\"issuer\": \"" + ISSUER + "\", \"subject\": \"s\", \"audiences\": []}", API)
                .statusCode(400)
                .body("error.message", equalTo("Federated identity credentials must have exactly one audience."));
    }

    @Test
    void missingPropertiesAreRefused() {
        put("fic1", "{\"issuer\": \"" + ISSUER + "\"}", API).statusCode(400)
                .body("error.message", equalTo("Federated Identity Credential from HTTP body has empty properties"));
    }

    @Test
    void theParentIdentityMustExist() {
        given().contentType("application/json").body("{\"properties\": " + credential("s") + "}")
                .put(BASE + "/missing/federatedIdentityCredentials/fic1" + API).then().statusCode(404)
                .body("error.message", equalTo("The parent user-assigned identity doesn't exist."));
    }

    @Test
    void anIssuerAndSubjectPairIsUniquePerIdentity() {
        put("fic1", credential("system:serviceaccount:wi:app"), API).statusCode(201);
        put("fic1", credential("system:serviceaccount:wi:app"), API).statusCode(200);
        put("fic2", credential("system:serviceaccount:wi:app"), API).statusCode(400)
                .body("error.message", equalTo("Issuer and subject combination already exists for this Managed Identity."));
    }

    @Test
    void anIdentityHoldsAtMostTwentyCredentials() {
        for (int i = 0; i < 20; i++) {
            put("fic" + i, credential("system:serviceaccount:wi:app" + i), API).statusCode(201);
        }
        put("fic20", credential("system:serviceaccount:wi:app20"), API).statusCode(400);
        put("fic3", credential("system:serviceaccount:wi:other"), API).statusCode(200);
    }

    @Test
    void aClaimsMatchingExpressionReplacesTheSubjectFromThePreviewVersion() {
        String expression = "{\"issuer\": \"" + ISSUER + "\", \"audiences\": [\"api://AzureADTokenExchange\"], "
                + "\"claimsMatchingExpression\": {\"value\": \"claims['sub'] matches 'system:serviceaccount:wi:*'\", "
                + "\"languageVersion\": 1}}";
        put("fic1", expression, PREVIEW).statusCode(201)
                .body("properties.claimsMatchingExpression.languageVersion", equalTo(1))
                .body("properties", not(hasKey("subject")));
        put("fic2", expression, API).statusCode(400);
        put("fic3", expression.replace("\"audiences\"", "\"subject\": \"s\", \"audiences\""), PREVIEW)
                .statusCode(400)
                .body("error.message", equalTo("Either 'subject' or 'claimsMatchingExpression' must be defined, but not both."));
    }
}
