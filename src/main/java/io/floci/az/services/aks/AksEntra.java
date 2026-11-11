package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aks.AksModels.AadProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.floci.az.services.entra.FirstPartyResources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Microsoft Entra integration of a managed cluster: the typed {@code aadProfile}, how a request changes it and how
 * it is answered. The AKS applications are the same in every cloud: server {@code 6dae42f8-...} is the audience of
 * every kubelogin login mode and public client {@code 80faf920-...} signs users in
 * ({@code kubelogin-authentication.md}).
 */
final class AksEntra {

    static final String SERVER_APP_ID = FirstPartyResources.AKS_SERVER_APP_ID;
    static final String CLIENT_APP_ID = FirstPartyResources.AKS_CLIENT_APP_ID;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AksEntra() {
    }

    /**
     * The profile after a request's {@code aadProfile}: sent fields replace stored ones, {@code tenantID} defaults to
     * the subscription's tenant, and managed integration stays on once on, since AKS can't disable it.
     */
    static AadProfile apply(AadProfile previous, JsonNode request, String subscriptionTenant) {
        AadProfile profile = previous == null ? new AadProfile() : previous;
        boolean wasManaged = profile.managed();
        if (request.has("managed")) {
            profile.setManaged(request.path("managed").asBoolean() || wasManaged);
        }
        if (request.has("enableAzureRBAC")) {
            profile.setEnableAzureRBAC(request.path("enableAzureRBAC").asBoolean());
        }
        if (request.has("adminGroupObjectIDs")) {
            JsonNode groups = request.path("adminGroupObjectIDs");
            List<String> ids = new ArrayList<>();
            groups.forEach(id -> ids.add(id.asText()));
            profile.setAdminGroupObjectIDs(groups.isArray() && !ids.isEmpty() ? ids : null);
        }
        if (request.hasNonNull("tenantID") && !request.path("tenantID").asText().isBlank()) {
            profile.setTenantID(request.path("tenantID").asText());
        }
        for (String legacy : List.of("clientAppID", "serverAppID", "serverAppSecret")) {
            if (request.hasNonNull(legacy)) {
                String value = request.path(legacy).asText();
                switch (legacy) {
                    case "clientAppID" -> profile.setClientAppID(value);
                    case "serverAppID" -> profile.setServerAppID(value);
                    default -> profile.setServerAppSecret(value);
                }
            }
        }
        if (profile.getTenantID() == null) {
            profile.setTenantID(subscriptionTenant);
        }
        if (profile.getManaged() == null && profile.getServerAppID() == null) {
            profile.setManaged(false);
        }
        return profile;
    }

    /** The {@code aadProfile} of an ARM response: {@code adminGroupObjectIDs} null when none, no server secret. */
    static Map<String, Object> toArm(AadProfile profile) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("managed", profile.managed());
        out.put("enableAzureRBAC", profile.azureRbac());
        out.put("adminGroupObjectIDs", profile.getAdminGroupObjectIDs());
        out.put("tenantID", profile.getTenantID());
        if (profile.getClientAppID() != null) {
            out.put("clientAppID", profile.getClientAppID());
        }
        if (profile.getServerAppID() != null) {
            out.put("serverAppID", profile.getServerAppID());
        }
        return out;
    }

    /**
     * Moves an {@code aadProfile} a record kept among its echoed properties, from before the profile was typed, into
     * the typed field. Returns whether the record changed.
     */
    static boolean migrate(ManagedCluster cluster) {
        Map<String, Object> echoed = cluster.getEchoedProperties();
        if (echoed == null || !echoed.containsKey("aadProfile")) {
            return false;
        }
        Object legacy = echoed.remove("aadProfile");
        if (cluster.getAadProfile() == null && legacy instanceof Map<?, ?>) {
            cluster.setAadProfile(apply(null, MAPPER.valueToTree(legacy), null));
        }
        return true;
    }
}
