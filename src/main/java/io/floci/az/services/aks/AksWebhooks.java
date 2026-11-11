package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.floci.az.services.entra.DirectoryReader;
import io.floci.az.services.entra.TenantResolver;
import io.floci.az.services.entra.TokenValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The webhooks an Entra cluster's API server calls, standing in for the AKS-managed webhook (kubeguard/guard in its
 * AKS modes). {@link #tokenReview} authenticates Microsoft Entra tokens as guard's {@code auth/providers/azure} does.
 */
@ApplicationScoped
public class AksWebhooks {

    private static final Logger LOG = Logger.getLogger(AksWebhooks.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String AUTHENTICATION_API = "authentication.k8s.io/v1";

    private final AksHandler clusters;
    private final TokenValidator tokens;
    private final DirectoryReader directory;
    private final TenantResolver tenants;

    @Inject
    public AksWebhooks(AksHandler clusters, TokenValidator tokens, DirectoryReader directory, TenantResolver tenants) {
        this.clusters = clusters;
        this.tokens = tokens;
        this.directory = directory;
        this.tenants = tenants;
    }

    /**
     * Answers a TokenReview: the token must be one floci-az's Entra issued, within its lifetime, for the AKS server
     * application, in the cluster's tenant. The user is named by {@code upn}, else by {@code oid} (service principals
     * and managed identities), with the {@code groups} claim, else the directory's direct memberships, and
     * {@code extra.oid}.
     */
    public Map<String, Object> tokenReview(String instanceId, JsonNode review) {
        Optional<ManagedCluster> cluster = clusters.clusterByInstanceId(instanceId).filter(ManagedCluster::entraEnabled);
        if (cluster.isEmpty()) {
            return unauthenticated("no Microsoft Entra enabled cluster has instance " + instanceId);
        }
        String token = review.path("spec").path("token").asText("");
        Optional<TokenValidator.Rejection> rejection = tokens.validate("Bearer " + token, AksEntra.SERVER_APP_ID::equals);
        if (rejection.isPresent()) {
            return unauthenticated(rejection.get().message());
        }
        JsonNode claims = claims(token);
        String tenant = claims.path("tid").asText("");
        String clusterTenant = cluster.get().getAadProfile().getTenantID();
        if (clusterTenant != null && !clusterTenant.equalsIgnoreCase(tenant)) {
            return unauthenticated("the token was issued in tenant " + tenant + ", not the cluster's tenant "
                    + clusterTenant);
        }
        String oid = claims.path("oid").asText("");
        if (oid.isBlank()) {
            return unauthenticated("the token carries no oid claim");
        }
        String upn = claims.path("upn").asText("");
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", upn.isBlank() ? oid : upn);
        user.put("groups", groups(claims, tenant, oid));
        user.put("extra", Map.of("oid", List.of(oid)));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("authenticated", true);
        status.put("user", user);
        return review(AUTHENTICATION_API, "TokenReview", status);
    }

    private List<String> groups(JsonNode claims, String tenant, String oid) {
        if (claims.path("groups").isArray()) {
            List<String> groups = new ArrayList<>();
            claims.path("groups").forEach(group -> groups.add(group.asText()));
            return groups;
        }
        return new ArrayList<>(new TreeSet<>(directory.memberGroupIds(
                tenants.resolve(tenant, null).directoryTenantId(), oid)));
    }

    private static JsonNode claims(String token) {
        try {
            return MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        } catch (IOException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            LOG.debugv("AKS token webhook: unreadable claims: {0}", e.getMessage());
            return MAPPER.createObjectNode();
        }
    }

    private static Map<String, Object> unauthenticated(String reason) {
        LOG.debugv("AKS token webhook refused a token: {0}", reason);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("authenticated", false);
        status.put("error", reason);
        return review(AUTHENTICATION_API, "TokenReview", status);
    }

    static Map<String, Object> review(String apiVersion, String kind, Map<String, Object> status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiVersion", apiVersion);
        body.put("kind", kind);
        body.put("status", status);
        return body;
    }
}
