package io.floci.az.services.managedidentity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.entra.OidcIssuerKeys;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An OIDC issuer the tests sign assertions with, resolved in-process like a cluster's issuer. */
@ApplicationScoped
public class TestOidcIssuer implements OidcIssuerKeys {

    static final String ISSUER = "https://issuer.floci.test/wi/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final KeyPair KEYS = generate();

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Optional<JsonNode> jwks(String issuer) {
        if (!ISSUER.equals(issuer)) {
            return Optional.empty();
        }
        RSAPublicKey key = (RSAPublicKey) KEYS.getPublic();
        return Optional.of(MAPPER.valueToTree(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "test",
                "n", URL.encodeToString(unsigned(key.getModulus().toByteArray())),
                "e", URL.encodeToString(unsigned(key.getPublicExponent().toByteArray())))))));
    }

    /** A service account token of this issuer for {@code subject}, audience {@code api://AzureADTokenExchange}. */
    static String token(String subject) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = URL.encodeToString("{\"alg\":\"RS256\",\"kid\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        String payload = URL.encodeToString(MAPPER.writeValueAsBytes(Map.of("iss", ISSUER, "sub", subject,
                "aud", List.of("api://AzureADTokenExchange"), "iat", now, "nbf", now, "exp", now + 3600)));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(KEYS.getPrivate());
        signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + URL.encodeToString(signer.sign());
    }

    private static byte[] unsigned(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }
}
