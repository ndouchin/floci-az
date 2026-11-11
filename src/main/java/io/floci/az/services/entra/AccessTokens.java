package io.floci.az.services.entra;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mints the access tokens of the token endpoint's grants. The shape follows the resource ({@link FirstPartyResources}):
 * its token version picks {@code ver}, the issuer, {@code appid} or {@code azp} and the user-name claims, and a
 * resource that asks for group claims gets the subject's direct group object ids in {@code groups}.
 */
@ApplicationScoped
public class AccessTokens {

    private final TokenIssuer issuer;
    private final DirectoryReader directory;

    @Inject
    public AccessTokens(TokenIssuer issuer, DirectoryReader directory) {
        this.issuer = issuer;
        this.directory = directory;
    }

    /** A delegated token for the user {@code userOid}, carrying {@code scp}. */
    public String delegated(TokenRequest request, String audience, String userOid, String scp,
                            String userPrincipalName) {
        String version = request.accessTokenVersion(audience);
        Map<String, Object> claims = new LinkedHashMap<>(TokenIssuer.userClaims(version, userPrincipalName));
        putGroups(claims, request, audience, userOid);
        return issuer.issue(new TokenIssuer.TokenSpec(request.tenantId(), request.accessTokenIssuer(audience), audience,
                userOid, userOid, request.appId(), scp, version, null, request.lifetimeSeconds()), claims);
    }

    /** An app-only token for the client's service principal. */
    public String app(TokenRequest request, String audience) {
        String version = request.accessTokenVersion(audience);
        Map<String, Object> claims = new LinkedHashMap<>();
        putGroups(claims, request, audience, request.clientObjectId());
        return issuer.issue(new TokenIssuer.TokenSpec(request.tenantId(), request.accessTokenIssuer(audience), audience,
                request.clientObjectId(), request.clientObjectId(), request.appId(), null, version, "app",
                request.lifetimeSeconds()), claims);
    }

    private void putGroups(Map<String, Object> claims, TokenRequest request, String audience, String objectId) {
        if (!FirstPartyResources.emitsGroups(audience)) {
            return;
        }
        List<String> groups = new ArrayList<>(directory.memberGroupIds(request.directoryTenantId(), objectId));
        if (!groups.isEmpty()) {
            groups.sort(null);
            claims.put("groups", groups);
        }
    }
}
