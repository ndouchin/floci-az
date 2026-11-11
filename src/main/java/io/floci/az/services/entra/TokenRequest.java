package io.floci.az.services.entra;

import java.util.Map;

/**
 * A token endpoint request after the parts every grant shares have been resolved.
 *
 * @param form            the decoded form body
 * @param tenantId        the tenant the token is issued in
 * @param directoryTenantId the tenant whose directory serves the request's lookups
 * @param baseUrl         the request base URL, no trailing slash
 * @param v2              whether the v2.0 endpoint was called (controls token shape and audience)
 * @param clientId        the {@code client_id} from the form or HTTP Basic, or null
 * @param appId           the application id the token is issued to
 * @param clientObjectId  the object id of the client's service principal
 * @param v1Issuer        the {@code iss} claim of v1.0 tokens
 * @param v2Issuer        the {@code iss} claim of v2.0 tokens
 * @param scope           {@code scope}, or the v1 {@code resource} when no scope was sent
 * @param lifetimeSeconds the access token lifetime
 * @param clientCredentialPresent whether the form or Basic header supplied a secret or assertion
 */
public record TokenRequest(Map<String, String> form, String tenantId, String directoryTenantId, String baseUrl,
                           boolean v2,
                           String clientId, String appId, String clientObjectId, String v1Issuer, String v2Issuer,
                           String scope, long lifetimeSeconds, boolean clientCredentialPresent) {

    /** The {@code iss} claim of the endpoint's token version. */
    public String issuer() {
        return v2 ? v2Issuer : v1Issuer;
    }

    /** The {@code ver} of an access token for {@code audience}: the resource's requested version, else the endpoint's. */
    public String accessTokenVersion(String audience) {
        return FirstPartyResources.accessTokenVersion(audience).orElse(tokenVersion());
    }

    /** The {@code iss} of an access token for {@code audience}, matching {@link #accessTokenVersion}. */
    public String accessTokenIssuer(String audience) {
        return "2.0".equals(accessTokenVersion(audience)) ? v2Issuer : v1Issuer;
    }

    /**
     * The {@code aud} of a delegated access token for {@code requestedScope}: the v1.0 {@code resource} when the
     * v1.0 endpoint got one, else the API the scope names; with no API named, the client id at v2.0 and the base
     * URL at v1.0.
     */
    public String delegatedAudience(String requestedScope) {
        String resource = form.get("resource");
        if (!v2 && resource != null && !resource.isBlank()) {
            return resource;
        }
        return Scopes.delegatedAudience(requestedScope, v2 ? appId : baseUrl);
    }

    /** The {@code ver} claim: {@code "2.0"} or {@code "1.0"}. */
    public String tokenVersion() {
        return v2 ? "2.0" : "1.0";
    }
}
