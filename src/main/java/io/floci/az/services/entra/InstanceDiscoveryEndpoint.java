package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /common/discovery/instance?api-version=1.1&authorization_endpoint=...}: instance discovery, which MSAL
 * asks of {@code login.microsoftonline.com} before it trusts an authority. Served for the pods of an emulated AKS
 * cluster whose DNS sends that name to floci-az: the answer names the host the request came to as the only alias, and
 * the tenant discovery document of the tenant {@code authorization_endpoint} names.
 */
@ApplicationScoped
public class InstanceDiscoveryEndpoint implements EntraEndpoint {

    private static final String PATH = "discovery/instance";

    private final EntraResponses responses;

    @Inject
    public InstanceDiscoveryEndpoint(EntraResponses responses) {
        this.responses = responses;
    }

    @Override
    public boolean matches(String path) {
        return path.endsWith(PATH);
    }

    @Override
    public Response handle(EntraRequest request) {
        String host = URI.create(request.baseUrl()).getHost();
        String tenant = tenantOf(request.request().queryParams() == null ? null
                : request.request().queryParams().get("authorization_endpoint"), request.tenantSegment());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("preferred_network", host);
        metadata.put("preferred_cache", host);
        metadata.put("aliases", List.of(host));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant_discovery_endpoint", request.baseUrl() + "/" + tenant + "/v2.0/.well-known/openid-configuration");
        body.put("api-version", "1.1");
        body.put("metadata", List.of(metadata));
        return responses.json(body);
    }

    /** The tenant segment of {@code https://{host}/{tenant}/oauth2/v2.0/authorize}, else the request's own. */
    private static String tenantOf(String authorizationEndpoint, String fallback) {
        if (authorizationEndpoint != null) {
            String[] segments = URI.create(authorizationEndpoint).getPath().split("/");
            if (segments.length > 1 && !segments[1].isBlank()) {
                return segments[1];
            }
        }
        return fallback;
    }
}
