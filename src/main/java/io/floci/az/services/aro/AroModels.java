package io.floci.az.services.aro;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.floci.az.services.aro.oauth.OAuthToken;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class AroModels {

    private AroModels() {
    }

    /**
     * One {@code Microsoft.RedHatOpenShift/openShiftClusters} resource. {@code properties} holds the writable
     * properties as the client last set them, with ARO's defaults filled in and the secrets removed; the read-only
     * properties are derived when a response is built.
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OpenShiftCluster {
        /** Short unique id naming the cluster's container and volumes. */
        private String instanceId;
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private Map<String, String> tags;
        private Map<String, Object> identity;
        private Map<String, Object> properties = new LinkedHashMap<>();
        private String provisioningState;
        /** The state a {@code Failed} cluster failed in: {@code Creating}, {@code Updating} or {@code Deleting}. */
        private String failedProvisioningState;
        /** The asynchronous operation the cluster's current or last state change belongs to. */
        private String asyncOperationId;
        /** {@code <name>-<5 random characters>}, ARO's infrastructure id; node and machine set names start with it. */
        private String infraId;
        /** The {@code spec.clusterID} of the cluster's {@code ClusterVersion}. */
        private String clusterUuid;
        private String kubeadminPassword;
        /**
         * Whether finalize created Secret {@code kube-system/kubeadmin}; it is created once per cluster, so a kubeadmin
         * the user deleted stays deleted when the container is recreated on its volume.
         */
        private boolean kubeadminSecretCreated;
        /** The MicroShift image tag the cluster runs, without its architecture suffix. */
        private String imageTag;
        private String containerId;
        /** Where clients reach the API server. */
        private String endpoint;
        /** Where floci-az reaches the API server for readiness probes. */
        private String internalEndpoint;
        private int apiHostPort;
        private int httpHostPort;
        private int httpsHostPort;
        /**
         * Whether the cluster gets the API proxy and the OAuth server: {@code oauth.enabled} when it was created, false
         * for clusters stored before the proxy existed, which keep their direct API URL.
         */
        private boolean oauthEnabled;
        /** The host port of the API proxy floci-az runs in front of MicroShift; 0 for a cluster without one. */
        private int proxyPort;
        /** The access tokens the cluster's OAuth server issued, by object name. */
        private Map<String, OAuthToken> oauthTokens;
        /** Whether the cluster runs the OpenShift web console, decided when it was created. */
        private boolean consoleEnabled;
        /** The secret of the OAuth client {@code console}, for a cluster with the console and the OAuth server. */
        private String consoleClientSecret;
        /** The address reported as {@code apiserverProfile.ip} and the ingress profile's {@code ip}. */
        private String publicIp;
        /** Base64 admin kubeconfig, with the server set to {@link #endpoint}. */
        private String kubeconfig;
        private Instant createdAt;
        /** When a {@code Creating} cluster that is still not ready ends {@code Failed}. */
        private Instant readyDeadline;

        public String getInstanceId() { return instanceId; }
        public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }

        public Map<String, Object> getIdentity() { return identity; }
        public void setIdentity(Map<String, Object> identity) { this.identity = identity; }

        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public String getFailedProvisioningState() { return failedProvisioningState; }
        public void setFailedProvisioningState(String failedProvisioningState) {
            this.failedProvisioningState = failedProvisioningState;
        }

        public String getAsyncOperationId() { return asyncOperationId; }
        public void setAsyncOperationId(String asyncOperationId) { this.asyncOperationId = asyncOperationId; }

        public String getInfraId() { return infraId; }
        public void setInfraId(String infraId) { this.infraId = infraId; }

        public String getClusterUuid() { return clusterUuid; }
        public void setClusterUuid(String clusterUuid) { this.clusterUuid = clusterUuid; }

        public String getKubeadminPassword() { return kubeadminPassword; }
        public void setKubeadminPassword(String kubeadminPassword) { this.kubeadminPassword = kubeadminPassword; }

        public boolean isKubeadminSecretCreated() { return kubeadminSecretCreated; }
        public void setKubeadminSecretCreated(boolean kubeadminSecretCreated) {
            this.kubeadminSecretCreated = kubeadminSecretCreated;
        }

        public String getImageTag() { return imageTag; }
        public void setImageTag(String imageTag) { this.imageTag = imageTag; }

        public String getContainerId() { return containerId; }
        public void setContainerId(String containerId) { this.containerId = containerId; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getInternalEndpoint() { return internalEndpoint; }
        public void setInternalEndpoint(String internalEndpoint) { this.internalEndpoint = internalEndpoint; }

        public int getApiHostPort() { return apiHostPort; }
        public void setApiHostPort(int apiHostPort) { this.apiHostPort = apiHostPort; }

        public int getHttpHostPort() { return httpHostPort; }
        public void setHttpHostPort(int httpHostPort) { this.httpHostPort = httpHostPort; }

        public int getHttpsHostPort() { return httpsHostPort; }
        public void setHttpsHostPort(int httpsHostPort) { this.httpsHostPort = httpsHostPort; }

        public boolean isOauthEnabled() { return oauthEnabled; }
        public void setOauthEnabled(boolean oauthEnabled) { this.oauthEnabled = oauthEnabled; }

        public int getProxyPort() { return proxyPort; }
        public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

        public Map<String, OAuthToken> getOauthTokens() { return oauthTokens; }
        public void setOauthTokens(Map<String, OAuthToken> oauthTokens) { this.oauthTokens = oauthTokens; }

        public boolean isConsoleEnabled() { return consoleEnabled; }
        public void setConsoleEnabled(boolean consoleEnabled) { this.consoleEnabled = consoleEnabled; }

        public String getConsoleClientSecret() { return consoleClientSecret; }
        public void setConsoleClientSecret(String consoleClientSecret) {
            this.consoleClientSecret = consoleClientSecret;
        }

        public String getPublicIp() { return publicIp; }
        public void setPublicIp(String publicIp) { this.publicIp = publicIp; }

        public String getKubeconfig() { return kubeconfig; }
        public void setKubeconfig(String kubeconfig) { this.kubeconfig = kubeconfig; }

        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

        public Instant getReadyDeadline() { return readyDeadline; }
        public void setReadyDeadline(Instant readyDeadline) { this.readyDeadline = readyDeadline; }

        /** ARM resource id, in the casing the cluster was created with. */
        public String armId() {
            return "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroup
                    + "/providers/" + AroArmSurface.NAMESPACE + "/openShiftClusters/" + name;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> profile(String key) {
            Object value = properties == null ? null : properties.get(key);
            return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }

        String clusterProfileValue(String key) {
            Object value = profile("clusterProfile").get(key);
            return value instanceof String s ? s : null;
        }

        String networkProfileValue(String key) {
            Object value = profile("networkProfile").get(key);
            return value instanceof String s ? s : null;
        }
    }
}
