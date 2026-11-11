package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** {@code grant_type=client_credentials}: an app-only token for the client's service principal. */
@ApplicationScoped
public class ClientCredentialsGrant implements GrantHandler {

    private final AccessTokens accessTokens;
    private final EntraResponses responses;

    @Inject
    public ClientCredentialsGrant(AccessTokens accessTokens, EntraResponses responses) {
        this.accessTokens = accessTokens;
        this.responses = responses;
    }

    @Override
    public String grantType() {
        return "client_credentials";
    }

    @Override
    public boolean requiresClientCredential() {
        return true;
    }

    @Override
    public Response handle(TokenRequest request) {
        String audience = Scopes.clientCredentialsAudience(request.scope(), request.baseUrl());
        String token = accessTokens.app(request, audience);
        return responses.token(token, null, request.lifetimeSeconds(), null);
    }
}
