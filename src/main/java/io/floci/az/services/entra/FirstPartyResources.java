package io.floci.az.services.entra;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Microsoft first-party resources whose application manifest shapes the access tokens issued for them. A token's
 * version follows the resource's {@code requestedAccessTokenVersion}, not the endpoint the client called
 * ({@code access-tokens.md}, {@code reference-app-manifest.md}), and the {@code groups} claim follows the resource's
 * {@code groupMembershipClaims}.
 *
 * <p>The AKS server application (the audience of every kubelogin login mode, {@code kubelogin-authentication.md})
 * takes v1.0 tokens: AKS's token webhook verifies them against the v1.0 issuer and reads {@code upn} and
 * {@code groups} from them.
 */
public final class FirstPartyResources {

    /** The Azure Kubernetes Service AAD server application, the same in every cloud. */
    public static final String AKS_SERVER_APP_ID = "6dae42f8-4368-4678-94ff-3960e28e3630";

    /** The Azure Kubernetes Service AAD client application kubelogin's public-client modes use. */
    public static final String AKS_CLIENT_APP_ID = "80faf920-1908-4b52-b5ef-a8e7bedfc67a";

    private static final Map<String, String> ACCESS_TOKEN_VERSIONS = Map.of(AKS_SERVER_APP_ID, "1.0");
    private static final Set<String> GROUP_CLAIMS = Set.of(AKS_SERVER_APP_ID);

    private FirstPartyResources() {
    }

    /** The token version {@code audience} asks for, or empty when it takes the endpoint's. */
    static Optional<String> accessTokenVersion(String audience) {
        return Optional.ofNullable(audience == null ? null : ACCESS_TOKEN_VERSIONS.get(audience));
    }

    /** Whether tokens for {@code audience} carry the subject's group object ids. */
    static boolean emitsGroups(String audience) {
        return audience != null && GROUP_CLAIMS.contains(audience);
    }
}
