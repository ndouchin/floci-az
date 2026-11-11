package io.floci.az.services.entra;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reads the audience and the {@code scp} claim out of a {@code scope} (or v1 {@code resource}) value. */
final class Scopes {

    /** OIDC scopes that ask for an id token or a refresh token, never for resource access. */
    private static final Set<String> OIDC_RESERVED = Set.of("openid", "profile", "email", "offline_access");

    private Scopes() {
    }

    static Set<String> values(String scope) {
        return scope == null || scope.isBlank() ? Set.of()
                : new LinkedHashSet<>(Arrays.asList(scope.trim().split("\\s+")));
    }

    /** v2.0 client_credentials scope is {@code {resource}/.default}; the audience is the resource. */
    static String clientCredentialsAudience(String scope, String baseUrl) {
        String first = firstScope(scope, baseUrl);
        if (first.endsWith("/.default")) {
            return first.substring(0, first.length() - "/.default".length());
        }
        return first;
    }

    /**
     * The audience of a delegated access token: the API named by the first scope that is not an OIDC one
     * ({@code {resource}/.default} or {@code {resource}/{permission}} gives {@code {resource}}), or {@code fallback}
     * when no scope names a resource (only OIDC scopes, or a bare permission such as {@code User.Read}).
     */
    static String delegatedAudience(String scope, String fallback) {
        for (String value : values(scope)) {
            if (OIDC_RESERVED.contains(value)) {
                continue;
            }
            String resource = resourceOf(value);
            if (resource != null) {
                return resource;
            }
        }
        return fallback;
    }

    /** {@code {resource}/{permission}} gives {@code {resource}}; a value with no permission segment is the resource. */
    private static String resourceOf(String scope) {
        int slash = scope.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        int scheme = scope.indexOf("://");
        if (scheme >= 0 && slash <= scheme + 2) {
            return scope;
        }
        return scope.substring(0, slash);
    }

    /** Delegated access tokens retain OIDC consent so UserInfo can enforce it. */
    static String delegatedScopes(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        return String.join(" ", Arrays.stream(scope.trim().split("\\s+"))
                .filter(value -> !"offline_access".equals(value)).toList());
    }

    /** The first requested scope, or the base URL when none was sent. */
    static String firstScope(String scope, String baseUrl) {
        if (scope == null || scope.isBlank()) {
            return baseUrl;
        }
        return scope.split("\\s+")[0];
    }

    /** The {@code scp} claim: the requested scopes minus the OIDC reserved ones, or null when none remain. */
    static String normalize(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String s : scope.split("\\s+")) {
            if (OIDC_RESERVED.contains(s)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
