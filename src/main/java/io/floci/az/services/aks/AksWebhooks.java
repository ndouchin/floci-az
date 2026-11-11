package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.floci.az.services.authorization.AccessEvaluator;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The webhooks an Entra cluster's API server calls, standing in for the AKS-managed webhook (kubeguard/guard in its
 * AKS modes). {@link #tokenReview} authenticates Microsoft Entra tokens as guard's {@code auth/providers/azure} does;
 * {@link #subjectAccessReview} authorizes requests against Azure role assignments as its {@code authz/providers/azure}
 * does.
 */
@ApplicationScoped
public class AksWebhooks {

    private static final Logger LOG = Logger.getLogger(AksWebhooks.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final String AUTHENTICATION_API = "authentication.k8s.io/v1";
    static final String AUTHORIZATION_API = "authorization.k8s.io/v1";
    static final String ACCESS_ALLOWED = "Access allowed by Azure RBAC Role Assignment %s of Role %s to user %s";
    static final String ACCESS_DENIED =
            "User does not have access to the resource in Azure. Update role assignment to allow access.";
    static final String NO_OPINION = "Azure does not have opinion for this user.";
    static final String NON_AAD_NO_OPINION = "Azure does not have opinion for this non AAD user. If you are an AAD user,"
            + " please set Extra:oid parameter for impersonated user in the kubeconfig";
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final AksHandler clusters;
    private final TokenValidator tokens;
    private final DirectoryReader directory;
    private final TenantResolver tenants;
    private final AccessEvaluator access;

    @Inject
    public AksWebhooks(AksHandler clusters, TokenValidator tokens, DirectoryReader directory, TenantResolver tenants,
                       AccessEvaluator access) {
        this.clusters = clusters;
        this.tokens = tokens;
        this.directory = directory;
        this.tenants = tenants;
        this.access = access;
    }

    /**
     * Answers a SubjectAccessReview with Azure RBAC: NoOpinion for {@code system:} users and for users without
     * {@code extra.oid} (service accounts, certificate users), which Kubernetes RBAC then decides; otherwise the
     * request's data action and scope ({@link AksAccessReviews}) are checked against the role assignments of the user's
     * {@code oid} and of its groups that are object ids. A denial carries guard's reason, which kubectl prints.
     */
    public Map<String, Object> subjectAccessReview(String instanceId, JsonNode review) {
        JsonNode spec = review.path("spec");
        String user = spec.path("user").asText("");
        Optional<ManagedCluster> cluster = clusters.clusterByInstanceId(instanceId)
                .filter(found -> found.entraEnabled() && found.getAadProfile().azureRbac());
        if (cluster.isEmpty() || user.startsWith("system:")) {
            return noOpinion(NO_OPINION);
        }
        String oid = spec.path("extra").path("oid").path(0).asText("");
        if (oid.isBlank()) {
            return noOpinion(NON_AAD_NO_OPINION);
        }
        Set<String> principals = new LinkedHashSet<>();
        principals.add(oid);
        spec.path("groups").forEach(group -> {
            if (UUID.matcher(group.asText()).matches()) {
                principals.add(group.asText());
            }
        });
        AksAccessReviews.AzureCheck check = AksAccessReviews.check(cluster.get().armId(), spec);
        AccessEvaluator.Decision decision = access.checkDataAction(principals, check.scope(), check.dataAction());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("allowed", decision.allowed());
        if (decision.allowed()) {
            status.put("reason", ACCESS_ALLOWED.formatted(decision.assignment().id(),
                    decision.assignment().roleDefinitionId(), user));
        } else {
            status.put("denied", true);
            status.put("reason", ACCESS_DENIED);
        }
        LOG.debugv("AKS authorization webhook: {0} {1} at {2}: {3}", user, check.dataAction(), check.scope(),
                decision.allowed() ? "allowed" : "denied");
        return review(AUTHORIZATION_API, "SubjectAccessReview", status);
    }

    private static Map<String, Object> noOpinion(String reason) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("allowed", false);
        status.put("reason", reason);
        return review(AUTHORIZATION_API, "SubjectAccessReview", status);
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
