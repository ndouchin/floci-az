package io.floci.az.services.aro.oauth;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/**
 * An access token the OAuth server issued, as it is stored: never the token itself, only what its object name
 * ({@code sha256~} and the base64url SHA-256 of the token) stands for, like an {@code OAuthAccessToken}.
 */
@RegisterForReflection
public record OAuthToken(String userName, String userUid, String provider, String clientId, List<String> scopes,
                         Instant createdAt, long expiresIn) {

    public OAuthToken {
        userUid = userUid == null ? "" : userUid;
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public boolean expiredAt(Instant now) {
        return expiresIn > 0 && !now.isBefore(createdAt.plusSeconds(expiresIn));
    }
}
