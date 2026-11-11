package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.entra.FederatedIdentities.FederatedCredential;
import io.floci.az.services.entra.FederatedIdentities.FederatedIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The federated credential exchange's checks and codes: AADSTS700211 issuer, AADSTS700213 subject, AADSTS700212
 * audience, AADSTS700027 signature, AADSTS90061 unreachable issuer, AADSTS700024 lifetime, AADSTS50027 malformed.
 */
class FederatedAssertionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final String ISSUER = "https://eastus.oic.prod-aks.azure.com/t1/k1/";
    private static final String SUBJECT = "system:serviceaccount:wi:app";
    private static final String AUDIENCE = "api://AzureADTokenExchange";
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER = generate();

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode jwks(KeyPair keys) {
        RSAPublicKey key = (RSAPublicKey) keys.getPublic();
        return MAPPER.valueToTree(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "k1", "alg", "RS256",
                "n", URL.encodeToString(unsigned(key.getModulus().toByteArray())),
                "e", URL.encodeToString(unsigned(key.getPublicExponent().toByteArray()))))));
    }

    private static byte[] unsigned(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static String assertion(Map<String, Object> claims, PrivateKey key) throws Exception {
        String header = URL.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}"
                .getBytes(StandardCharsets.UTF_8));
        String payload = URL.encodeToString(MAPPER.writeValueAsBytes(claims));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + URL.encodeToString(signer.sign());
    }

    private static Map<String, Object> claims(String issuer, String subject, String audience, long exp) {
        return Map.of("iss", issuer, "sub", subject, "aud", List.of(audience), "exp", exp,
                "nbf", NOW.getEpochSecond() - 60, "iat", NOW.getEpochSecond() - 60);
    }

    private static Map<String, Object> valid() {
        return claims(ISSUER, SUBJECT, AUDIENCE, NOW.getEpochSecond() + 3600);
    }

    private static FederatedIdentity identity(FederatedCredential... credentials) {
        return new FederatedIdentity("client", "object", Map.of(), List.of(credentials));
    }

    private static final FederatedIdentity IDENTITY =
            identity(new FederatedCredential(ISSUER, SUBJECT, List.of(AUDIENCE), null));

    private static FederatedAssertions verifier() {
        return new FederatedAssertions(issuer -> ISSUER.equals(issuer) ? Optional.of(jwks(KEYS)) : Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static int code(Optional<FederatedAssertions.Rejection> rejection) {
        assertTrue(rejection.isPresent(), "expected a rejection");
        return rejection.get().error().code();
    }

    @Test
    void aMatchingSignedAssertionIsAccepted() throws Exception {
        assertEquals(Optional.empty(), verifier().verify(assertion(valid(), KEYS.getPrivate()), IDENTITY));
    }

    @Test
    void eachMismatchGivesItsCode() throws Exception {
        long exp = NOW.getEpochSecond() + 3600;
        assertEquals(700211, code(verifier().verify(assertion(claims("https://other/", SUBJECT, AUDIENCE, exp),
                KEYS.getPrivate()), IDENTITY)));
        assertEquals(700213, code(verifier().verify(assertion(claims(ISSUER, "system:serviceaccount:wi:other",
                AUDIENCE, exp), KEYS.getPrivate()), IDENTITY)));
        assertEquals(700212, code(verifier().verify(assertion(claims(ISSUER, SUBJECT, "api://other", exp),
                KEYS.getPrivate()), IDENTITY)));
        assertEquals(700027, code(verifier().verify(assertion(valid(), OTHER.getPrivate()), IDENTITY)));
        assertEquals(700024, code(verifier().verify(assertion(claims(ISSUER, SUBJECT, AUDIENCE,
                NOW.getEpochSecond() - 600), KEYS.getPrivate()), IDENTITY)));
        assertEquals(50027, code(verifier().verify("not-a-jwt", IDENTITY)));
    }

    @Test
    void anIssuerNoSourceRunsFailsLikeAnUnreachableDiscoveryEndpoint() throws Exception {
        String unknown = "https://token.actions.githubusercontent.com";
        FederatedIdentity github = identity(new FederatedCredential(unknown, SUBJECT, List.of(AUDIENCE), null));
        assertEquals(90061, code(verifier().verify(assertion(claims(unknown, SUBJECT, AUDIENCE,
                NOW.getEpochSecond() + 3600), KEYS.getPrivate()), github)));
    }

    @Test
    void anIdentityWithNoCredentialRefusesEveryAssertion() throws Exception {
        assertEquals(700211, code(verifier().verify(assertion(valid(), KEYS.getPrivate()), identity())));
    }

    @Test
    void aClaimsMatchingExpressionMatchesTheSubjectByWildcard() throws Exception {
        FederatedIdentity flexible = identity(new FederatedCredential(ISSUER, null, List.of(AUDIENCE),
                "claims['sub'] matches 'system:serviceaccount:wi:*'"));
        assertEquals(Optional.empty(), verifier().verify(assertion(valid(), KEYS.getPrivate()), flexible));
        assertEquals(700213, code(verifier().verify(assertion(claims(ISSUER, "system:serviceaccount:other:app",
                AUDIENCE, NOW.getEpochSecond() + 3600), KEYS.getPrivate()), flexible)));
    }
}
