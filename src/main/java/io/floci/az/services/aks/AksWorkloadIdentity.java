package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.aks.AksModels.ManagedCluster;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The OIDC issuer and Workload Identity of a managed cluster. The issuer is on by default for new clusters from
 * Kubernetes 1.34, the oldest version the emulator runs, can't be turned off once on ({@code use-oidc-issuer.md}),
 * and its URL is {@code https://{region}.oic.prod-aks.azure.com/{tenant}/{uuid}/}, where the uuid is the cluster's
 * immutable OIDC key ({@code workload-identity-deploy-cluster.md}).
 */
final class AksWorkloadIdentity {

    static final String ISSUER_HOST_SUFFIX = ".oic.prod-aks.azure.com"; // cloud-literal: sovereign-cloud issuer hosts unverified

    private AksWorkloadIdentity() {
    }

    /**
     * Applies a request's {@code oidcIssuerProfile} and {@code securityProfile.workloadIdentity}: a new cluster
     * without the profile gets the issuer; {@code enabled: false} on an issuer that is on keeps it on.
     *
     * @throws AksHandler.AksRequestException when Workload Identity is asked for without the issuer
     */
    static void apply(ManagedCluster cluster, boolean exists, JsonNode props, String tenant) {
        JsonNode issuer = props.path("oidcIssuerProfile");
        boolean wanted = issuer.has("enabled") ? issuer.path("enabled").asBoolean() : !exists;
        if (wanted) {
            enableIssuer(cluster, tenant);
        } else if (!exists) {
            cluster.setOidcIssuerEnabled(false);
        }
        JsonNode workloadIdentity = props.path("securityProfile").path("workloadIdentity");
        if (workloadIdentity.has("enabled")) {
            cluster.setWorkloadIdentityEnabled(workloadIdentity.path("enabled").asBoolean());
        }
        if (Boolean.TRUE.equals(cluster.getWorkloadIdentityEnabled()) && !cluster.oidcIssuerEnabled()) {
            throw new AksHandler.AksRequestException(400, "BadRequest",
                    "Enabling workload identity requires enabling OIDC issuer.");
        }
        ensureWebhookCertificates(cluster);
    }

    /** Generates the webhook's certificates the first time Workload Identity is on. */
    static void ensureWebhookCertificates(ManagedCluster cluster) {
        if (cluster.workloadIdentityEnabled() && cluster.getWorkloadIdentityWebhookCertificates() == null) {
            cluster.setWorkloadIdentityWebhookCertificates(AksWorkloadIdentityWebhook.certificates());
        }
    }

    /** The tenant of the cluster's issuer URL ({@code https://{region}.oic.prod-aks.azure.com/{tenant}/{uuid}/}). */
    static String issuerTenant(ManagedCluster cluster) {
        String[] segments = URI.create(cluster.getOidcIssuerUrl()).getPath().split("/");
        return segments.length > 1 ? segments[1] : "";
    }

    /** Turns the issuer on, generating its URL the first time. */
    static void enableIssuer(ManagedCluster cluster, String tenant) {
        cluster.setOidcIssuerEnabled(true);
        if (cluster.getOidcIssuerUrl() == null) {
            cluster.setOidcIssuerUrl("https://" + cluster.getLocation() + ISSUER_HOST_SUFFIX + "/" + tenant + "/"
                    + UUID.randomUUID() + "/");
        }
    }

    /** The {@code oidcIssuerProfile} of an ARM response. */
    static Map<String, Object> issuerProfile(ManagedCluster cluster) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("enabled", cluster.oidcIssuerEnabled());
        if (cluster.oidcIssuerEnabled()) {
            profile.put("issuerURL", cluster.getOidcIssuerUrl());
        }
        return profile;
    }

    /** The {@code securityProfile} of an ARM response: the echoed fields plus {@code workloadIdentity}. */
    static Map<String, Object> securityProfile(ManagedCluster cluster, Object echoed) {
        Map<String, Object> profile = new LinkedHashMap<>();
        if (echoed instanceof Map<?, ?> fields) {
            fields.forEach((key, value) -> profile.put(String.valueOf(key), value));
        }
        if (cluster.getWorkloadIdentityEnabled() != null) {
            profile.put("workloadIdentity", Map.of("enabled", cluster.workloadIdentityEnabled()));
        } else {
            profile.remove("workloadIdentity");
        }
        return profile;
    }

    /**
     * Types the profiles a record kept among its echoed properties from before they were typed. Returns whether the
     * record changed.
     */
    static boolean migrate(ManagedCluster cluster, String tenant) {
        Map<String, Object> echoed = cluster.getEchoedProperties();
        if (echoed == null) {
            return false;
        }
        boolean changed = false;
        if (echoed.remove("oidcIssuerProfile") instanceof Map<?, ?> issuer) {
            changed = true;
            if (Boolean.TRUE.equals(issuer.get("enabled")) && cluster.getOidcIssuerEnabled() == null) {
                enableIssuer(cluster, tenant);
            }
        }
        if (echoed.get("securityProfile") instanceof Map<?, ?> security && security.containsKey("workloadIdentity")) {
            Map<String, Object> rest = new LinkedHashMap<>();
            security.forEach((key, value) -> rest.put(String.valueOf(key), value));
            if (rest.remove("workloadIdentity") instanceof Map<?, ?> workloadIdentity
                    && cluster.getWorkloadIdentityEnabled() == null) {
                cluster.setWorkloadIdentityEnabled(Boolean.TRUE.equals(workloadIdentity.get("enabled")));
            }
            echoed.put("securityProfile", rest);
            changed = true;
        }
        ensureWebhookCertificates(cluster);
        return changed;
    }
}
