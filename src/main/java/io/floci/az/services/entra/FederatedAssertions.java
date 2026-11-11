package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.entra.FederatedIdentities.FederatedCredential;
import io.floci.az.services.entra.FederatedIdentities.FederatedIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies a {@code client_assertion} against a client's federated identity credentials, the exchange Workload
 * Identity relies on ({@code workload-identity-federation.md}): issuer, subject and audience must match one credential
 * case-sensitively, the assertion must be RS256-signed by the issuer's keys and within its lifetime. The checks run in
 * that order so the answer names the most specific mismatch, with the code Entra returns for it.
 *
 * <p>Issuer keys come from the {@link OidcIssuerKeys} sources (the emulator's own clusters); an issuer no source runs
 * is not fetched, since an emulator often runs offline, and fails like an unreachable discovery endpoint.
 */
@ApplicationScoped
public class FederatedAssertions {

    /** Why an assertion was refused: the catalogue entry and its message arguments. */
    public record Rejection(AadstsError error, Object[] args) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Pattern EXPRESSION = Pattern.compile("claims\\['sub'\\]\\s+matches\\s+'([^']*)'");

    private final Function<String, Optional<JsonNode>> issuerKeys;
    private final Clock clock;

    @Inject
    public FederatedAssertions(Instance<OidcIssuerKeys> sources) {
        this(issuer -> {
            for (OidcIssuerKeys source : sources) {
                Optional<JsonNode> keys = source.jwks(issuer);
                if (keys.isPresent()) {
                    return keys;
                }
            }
            return Optional.empty();
        }, Clock.systemUTC());
    }

    FederatedAssertions(Function<String, Optional<JsonNode>> issuerKeys, Clock clock) {
        this.issuerKeys = issuerKeys;
        this.clock = clock;
    }

    /** Checks {@code assertion} for {@code identity}; empty when it is accepted. */
    public Optional<Rejection> verify(String assertion, FederatedIdentity identity) {
        String[] parts = assertion == null ? new String[0] : assertion.trim().split("\\.", -1);
        JsonNode header;
        JsonNode claims;
        try {
            if (parts.length != 3) {
                return reject(AadstsError.ASSERTION_MALFORMED);
            }
            header = decode(parts[0]);
            claims = decode(parts[1]);
        } catch (IOException | IllegalArgumentException malformed) {
            return reject(AadstsError.ASSERTION_MALFORMED);
        }
        String issuer = claims.path("iss").asText("");
        String subject = claims.path("sub").asText("");
        List<String> audiences = audiences(claims.path("aud"));
        if (issuer.isBlank() || subject.isBlank()) {
            return reject(AadstsError.FEDERATED_NO_MATCH, issuer, subject, String.join(",", audiences));
        }

        List<FederatedCredential> byIssuer = identity.credentials().stream()
                .filter(credential -> issuer.equals(credential.issuer())).toList();
        if (byIssuer.isEmpty()) {
            return reject(AadstsError.FEDERATED_ISSUER_MISMATCH, issuer);
        }
        List<FederatedCredential> bySubject = byIssuer.stream()
                .filter(credential -> subjectMatches(credential, subject)).toList();
        if (bySubject.isEmpty()) {
            return reject(AadstsError.FEDERATED_SUBJECT_MISMATCH, subject);
        }
        boolean audienceMatches = bySubject.stream()
                .anyMatch(credential -> credential.audiences().stream().anyMatch(audiences::contains));
        if (!audienceMatches) {
            return reject(AadstsError.FEDERATED_AUDIENCE_MISMATCH, String.join(",", audiences));
        }
        if (!"RS256".equals(header.path("alg").asText())) {
            return reject(AadstsError.ASSERTION_SIGNATURE_INVALID);
        }
        Optional<JsonNode> keys = issuerKeys.apply(issuer);
        if (keys.isEmpty()) {
            return reject(AadstsError.EXTERNAL_OIDC_UNREACHABLE);
        }
        if (!signatureMatches(parts, header.path("kid").asText(""), keys.get())) {
            return reject(AadstsError.ASSERTION_SIGNATURE_INVALID);
        }
        return lifetimeRejection(claims);
    }

    /**
     * A credential's subject must equal {@code sub}; a {@code claimsMatchingExpression} of the form
     * {@code claims['sub'] matches '<pattern>'} matches it with {@code *} standing for any run of characters.
     */
    static boolean subjectMatches(FederatedCredential credential, String subject) {
        if (credential.subject() != null) {
            return credential.subject().equals(subject);
        }
        if (credential.expression() == null) {
            return false;
        }
        Matcher matcher = EXPRESSION.matcher(credential.expression());
        if (!matcher.find()) {
            return false;
        }
        StringBuilder regex = new StringBuilder();
        for (String literal : matcher.group(1).split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        return subject.matches(regex.toString());
    }

    private Optional<Rejection> lifetimeRejection(JsonNode claims) {
        Instant now = clock.instant();
        if (!claims.path("exp").isNumber()
                || now.isAfter(Instant.ofEpochSecond(claims.path("exp").asLong()).plus(CLOCK_SKEW))) {
            return reject(AadstsError.ASSERTION_OUT_OF_TIME_RANGE);
        }
        if (claims.path("nbf").isNumber()
                && now.plus(CLOCK_SKEW).isBefore(Instant.ofEpochSecond(claims.path("nbf").asLong()))) {
            return reject(AadstsError.ASSERTION_OUT_OF_TIME_RANGE);
        }
        return Optional.empty();
    }

    private static boolean signatureMatches(String[] parts, String kid, JsonNode jwks) {
        for (JsonNode key : jwks.path("keys")) {
            if (!"RSA".equals(key.path("kty").asText()) || (!kid.isEmpty() && key.has("kid")
                    && !kid.equals(key.path("kid").asText()))) {
                continue;
            }
            try {
                PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                        new BigInteger(1, Base64.getUrlDecoder().decode(key.path("n").asText())),
                        new BigInteger(1, Base64.getUrlDecoder().decode(key.path("e").asText()))));
                Signature verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(publicKey);
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                if (verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                    return true;
                }
            } catch (GeneralSecurityException | IllegalArgumentException rejected) {
                // A key that can't be built or a signature of the wrong length is a key that does not verify it.
                continue;
            }
        }
        return false;
    }

    private static List<String> audiences(JsonNode aud) {
        List<String> audiences = new ArrayList<>();
        if (aud.isArray()) {
            aud.forEach(value -> audiences.add(value.asText()));
        } else if (aud.isTextual()) {
            audiences.add(aud.asText());
        }
        return audiences;
    }

    private static JsonNode decode(String part) throws IOException {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(part));
    }

    private static Optional<Rejection> reject(AadstsError error, Object... args) {
        return Optional.of(new Rejection(error, args));
    }
}
