package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.floci.az.services.entra.OidcIssuerKeys;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The OIDC issuers of the clusters: which cluster an issuer URL names, and the discovery document and JWKS AKS
 * publishes for it ({@code use-oidc-issuer.md}). Entra resolves an issuer here in-process instead of fetching it, as
 * floci's EKS does for IRSA; outside verifiers read the same documents on the AKS host or on floci-az's own path.
 */
@ApplicationScoped
public class AksOidcIssuers implements OidcIssuerKeys {

    private static final Logger LOG = Logger.getLogger(AksOidcIssuers.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AksHandler clusters;

    @Inject
    public AksOidcIssuers(AksHandler clusters) {
        this.clusters = clusters;
    }

    @Override
    public Optional<JsonNode> jwks(String issuer) {
        return cluster(issuer).filter(cluster -> cluster.getServiceAccountJwks() != null).map(AksOidcIssuers::jwks);
    }

    /** The cluster whose issuer is {@code issuer}, compared with and without a trailing slash. */
    public Optional<ManagedCluster> cluster(String issuer) {
        return cluster(clusters.clusters(), issuer);
    }

    static Optional<ManagedCluster> cluster(List<ManagedCluster> clusters, String issuer) {
        if (issuer == null) {
            return Optional.empty();
        }
        String wanted = issuer.endsWith("/") ? issuer : issuer + "/";
        return clusters.stream()
                .filter(cluster -> cluster.oidcIssuerEnabled() && wanted.equals(cluster.getOidcIssuerUrl()))
                .findFirst();
    }

    /** The discovery document AKS serves at {@code {issuerURL}.well-known/openid-configuration}. */
    static Map<String, Object> discovery(ManagedCluster cluster) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", cluster.getOidcIssuerUrl());
        document.put("jwks_uri", cluster.getOidcIssuerUrl() + "openid/v1/jwks");
        document.put("response_types_supported", List.of("id_token"));
        document.put("subject_types_supported", List.of("public"));
        document.put("id_token_signing_alg_values_supported", List.of("RS256"));
        return document;
    }

    /** The cluster's service account JWKS; no keys before the API server first answered. */
    public static JsonNode jwks(ManagedCluster cluster) {
        if (cluster.getServiceAccountJwks() != null) {
            try {
                return MAPPER.readTree(cluster.getServiceAccountJwks());
            } catch (IOException e) {
                LOG.warnv("AKS cluster {0}: unreadable service account JWKS: {1}", cluster.getName(), e.getMessage());
            }
        }
        return MAPPER.createObjectNode().set("keys", MAPPER.createArrayNode());
    }

    /**
     * The document a path under an issuer names: {@code {tenant}/{key}/.well-known/openid-configuration} or
     * {@code {tenant}/{key}/openid/v1/jwks}; empty for another path or an unknown issuer.
     */
    Optional<Object> document(String region, String path) {
        return document(clusters.clusters(), region, path);
    }

    static Optional<Object> document(List<ManagedCluster> clusters, String region, String path) {
        String[] segments = path.replaceAll("^/+", "").split("/", 3);
        if (segments.length < 3) {
            return Optional.empty();
        }
        Optional<ManagedCluster> cluster = cluster(clusters, "https://" + region + AksWorkloadIdentity.ISSUER_HOST_SUFFIX
                + "/" + segments[0] + "/" + segments[1] + "/");
        return switch (segments[2]) {
            case ".well-known/openid-configuration" -> cluster.map(AksOidcIssuers::discovery);
            case "openid/v1/jwks" -> cluster.map(AksOidcIssuers::jwks);
            default -> Optional.empty();
        };
    }
}
