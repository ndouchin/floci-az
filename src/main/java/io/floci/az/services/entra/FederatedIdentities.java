package io.floci.az.services.entra;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The clients whose {@code client_assertion} is checked against federated identity credentials: services that hold
 * federated credentials implement it (user-assigned managed identities), so the token service never depends on them
 * directly.
 */
public interface FederatedIdentities {

    /** The federated identity {@code clientId} names in {@code tenantId}, when this source holds it. */
    Optional<FederatedIdentity> byClientId(String tenantId, String clientId);

    /**
     * A client with federated identity credentials, and what its tokens carry.
     *
     * @param clientId    the client (application) id, {@code appid} or {@code azp} in its tokens
     * @param objectId    its object id ({@code oid} and {@code sub} of its tokens)
     * @param extraClaims claims its tokens carry besides the standard ones ({@code xms_mirid} for a managed identity)
     * @param credentials its federated identity credentials
     */
    record FederatedIdentity(String clientId, String objectId, Map<String, Object> extraClaims,
                             List<FederatedCredential> credentials) {
    }

    /**
     * One federated identity credential.
     *
     * @param issuer     the issuer an assertion's {@code iss} must equal
     * @param subject    the subject its {@code sub} must equal, or null when {@code expression} matches it
     * @param audiences  the audiences its {@code aud} must be one of
     * @param expression a {@code claimsMatchingExpression} value, or null
     */
    record FederatedCredential(String issuer, String subject, List<String> audiences, String expression) {
    }
}
