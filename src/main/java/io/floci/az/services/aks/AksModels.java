package io.floci.az.services.aks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AksModels {

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ManagedCluster {
        /** Short unique ID used for Docker container naming: floci-az-aks-{instanceId}. */
        private String instanceId;
        private String subscriptionId;
        private String resourceGroup;
        private String name;
        private String location;
        private String kubernetesVersion;
        /** The full {@code major.minor.patch} the control plane runs, from the emulator's version map. */
        private String currentKubernetesVersion;
        private String provisioningState;
        /** {@code Running} or {@code Stopped}: what the cluster should be, set by start and stop. */
        private String powerState;
        private String dnsPrefix;
        private String fqdn;
        private String nodeResourceGroup;
        private Boolean disableLocalAccounts;
        private NetworkProfile networkProfile;
        /** Request objects the emulator stores and echoes without acting on them (aadProfile, oidcIssuerProfile...). */
        private Map<String, Object> echoedProperties;
        /** Public API server endpoint (container-DNS resolvable inside Docker, or localhost outside). */
        private String endpoint;
        /** Internal endpoint used for readiness polling (IP-based, works on default bridge). */
        private String internalEndpoint;
        private String containerId;
        /** Host port the API server is published on, kept so a restored container keeps its port. */
        private Integer hostPort;
        /** Image the k3s server container was created from. */
        private String serverImage;
        /** Shared secret agents join the server with; kept so a restored or upgraded server accepts them. */
        private String clusterToken;
        /** Base64-encoded kubeconfig extracted from k3s (or a mock kubeconfig). */
        private String kubeconfig;
        /** Base64-encoded certificate authority data from the cluster. */
        private String caData;
        private String clientCertificateData;
        private String clientKeyData;
        /** Why the last operation failed, reported by the operation status and cleared by the next one. */
        private String lastError;
        /** Bumped by every request that changes what the cluster should be, so a reconcile can tell it is stale. */
        private long generation;
        private Instant createdAt;
        private Map<String, String> tags;
        private List<AgentPoolProfile> agentPoolProfiles;

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

        public String getKubernetesVersion() { return kubernetesVersion; }
        public void setKubernetesVersion(String kubernetesVersion) { this.kubernetesVersion = kubernetesVersion; }

        public String getCurrentKubernetesVersion() { return currentKubernetesVersion; }
        public void setCurrentKubernetesVersion(String currentKubernetesVersion) {
            this.currentKubernetesVersion = currentKubernetesVersion;
        }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public String getPowerState() { return powerState; }
        public void setPowerState(String powerState) { this.powerState = powerState; }

        public String getDnsPrefix() { return dnsPrefix; }
        public void setDnsPrefix(String dnsPrefix) { this.dnsPrefix = dnsPrefix; }

        public String getFqdn() { return fqdn; }
        public void setFqdn(String fqdn) { this.fqdn = fqdn; }

        public String getNodeResourceGroup() { return nodeResourceGroup; }
        public void setNodeResourceGroup(String nodeResourceGroup) { this.nodeResourceGroup = nodeResourceGroup; }

        public Boolean getDisableLocalAccounts() { return disableLocalAccounts; }
        public void setDisableLocalAccounts(Boolean disableLocalAccounts) {
            this.disableLocalAccounts = disableLocalAccounts;
        }

        public NetworkProfile getNetworkProfile() { return networkProfile; }
        public void setNetworkProfile(NetworkProfile networkProfile) { this.networkProfile = networkProfile; }

        public Map<String, Object> getEchoedProperties() { return echoedProperties; }
        public void setEchoedProperties(Map<String, Object> echoedProperties) {
            this.echoedProperties = echoedProperties;
        }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getInternalEndpoint() { return internalEndpoint; }
        public void setInternalEndpoint(String internalEndpoint) { this.internalEndpoint = internalEndpoint; }

        public String getContainerId() { return containerId; }
        public void setContainerId(String containerId) { this.containerId = containerId; }

        public Integer getHostPort() { return hostPort; }
        public void setHostPort(Integer hostPort) { this.hostPort = hostPort; }

        public String getServerImage() { return serverImage; }
        public void setServerImage(String serverImage) { this.serverImage = serverImage; }

        public String getClusterToken() { return clusterToken; }
        public void setClusterToken(String clusterToken) { this.clusterToken = clusterToken; }

        public String getKubeconfig() { return kubeconfig; }
        public void setKubeconfig(String kubeconfig) { this.kubeconfig = kubeconfig; }

        public String getCaData() { return caData; }
        public void setCaData(String caData) { this.caData = caData; }

        public String getClientCertificateData() { return clientCertificateData; }
        public void setClientCertificateData(String clientCertificateData) {
            this.clientCertificateData = clientCertificateData;
        }

        public String getClientKeyData() { return clientKeyData; }
        public void setClientKeyData(String clientKeyData) { this.clientKeyData = clientKeyData; }

        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }

        public long getGeneration() { return generation; }
        public void setGeneration(long generation) { this.generation = generation; }

        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

        public Map<String, String> getTags() { return tags; }
        public void setTags(Map<String, String> tags) { this.tags = tags; }

        public List<AgentPoolProfile> getAgentPoolProfiles() { return agentPoolProfiles; }
        public void setAgentPoolProfiles(List<AgentPoolProfile> agentPoolProfiles) {
            this.agentPoolProfiles = agentPoolProfiles;
        }

        public Optional<AgentPoolProfile> pool(String poolName) {
            if (agentPoolProfiles == null) {
                return Optional.empty();
            }
            return agentPoolProfiles.stream().filter(p -> poolName.equals(p.getName())).findFirst();
        }

        public List<AgentPoolProfile> pools() {
            if (agentPoolProfiles == null) {
                agentPoolProfiles = new ArrayList<>();
            }
            return agentPoolProfiles;
        }

        public boolean localAccountsDisabled() {
            return Boolean.TRUE.equals(disableLocalAccounts);
        }

        /** ARM resource ID for this cluster. */
        public String armId() {
            return String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.ContainerService/managedClusters/%s",
                subscriptionId, resourceGroup, name);
        }
    }

    /** The networkProfile fields the emulator applies to k3s, plus the ones it echoes. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NetworkProfile {
        private String networkPlugin;
        private String networkPluginMode;
        private String networkPolicy;
        private String podCidr;
        private String serviceCidr;
        private String dnsServiceIP;
        private String outboundType;
        private String loadBalancerSku;

        public String getNetworkPlugin() { return networkPlugin; }
        public void setNetworkPlugin(String networkPlugin) { this.networkPlugin = networkPlugin; }

        public String getNetworkPluginMode() { return networkPluginMode; }
        public void setNetworkPluginMode(String networkPluginMode) { this.networkPluginMode = networkPluginMode; }

        public String getNetworkPolicy() { return networkPolicy; }
        public void setNetworkPolicy(String networkPolicy) { this.networkPolicy = networkPolicy; }

        public String getPodCidr() { return podCidr; }
        public void setPodCidr(String podCidr) { this.podCidr = podCidr; }

        public String getServiceCidr() { return serviceCidr; }
        public void setServiceCidr(String serviceCidr) { this.serviceCidr = serviceCidr; }

        public String getDnsServiceIP() { return dnsServiceIP; }
        public void setDnsServiceIP(String dnsServiceIP) { this.dnsServiceIP = dnsServiceIP; }

        public String getOutboundType() { return outboundType; }
        public void setOutboundType(String outboundType) { this.outboundType = outboundType; }

        public String getLoadBalancerSku() { return loadBalancerSku; }
        public void setLoadBalancerSku(String loadBalancerSku) { this.loadBalancerSku = loadBalancerSku; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentPoolProfile {
        private String name;
        private int count;
        private String vmSize;
        private String osType;
        private String osSku;
        private String mode;
        private String orchestratorVersion;
        private String currentOrchestratorVersion;
        private String provisioningState;
        private String powerState;
        private Integer maxPods;
        private Boolean enableAutoScaling;
        private Integer minCount;
        private Integer maxCount;
        private Map<String, String> nodeLabels;
        private List<String> nodeTaints;
        private List<String> availabilityZones;
        /** The pool's nodes as they run: one k3s agent container each. */
        private List<AgentNode> nodes;
        /** User labels and taints last applied to the running nodes, so a change removes the old ones. */
        private Map<String, String> appliedNodeLabels;
        private List<String> appliedNodeTaints;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public String getVmSize() { return vmSize; }
        public void setVmSize(String vmSize) { this.vmSize = vmSize; }

        public String getOsType() { return osType; }
        public void setOsType(String osType) { this.osType = osType; }

        public String getOsSku() { return osSku; }
        public void setOsSku(String osSku) { this.osSku = osSku; }

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }

        public String getOrchestratorVersion() { return orchestratorVersion; }
        public void setOrchestratorVersion(String orchestratorVersion) {
            this.orchestratorVersion = orchestratorVersion;
        }

        public String getCurrentOrchestratorVersion() { return currentOrchestratorVersion; }
        public void setCurrentOrchestratorVersion(String currentOrchestratorVersion) {
            this.currentOrchestratorVersion = currentOrchestratorVersion;
        }

        public String getProvisioningState() { return provisioningState; }
        public void setProvisioningState(String provisioningState) { this.provisioningState = provisioningState; }

        public String getPowerState() { return powerState; }
        public void setPowerState(String powerState) { this.powerState = powerState; }

        public Integer getMaxPods() { return maxPods; }
        public void setMaxPods(Integer maxPods) { this.maxPods = maxPods; }

        public Boolean getEnableAutoScaling() { return enableAutoScaling; }
        public void setEnableAutoScaling(Boolean enableAutoScaling) { this.enableAutoScaling = enableAutoScaling; }

        public Integer getMinCount() { return minCount; }
        public void setMinCount(Integer minCount) { this.minCount = minCount; }

        public Integer getMaxCount() { return maxCount; }
        public void setMaxCount(Integer maxCount) { this.maxCount = maxCount; }

        public Map<String, String> getNodeLabels() { return nodeLabels; }
        public void setNodeLabels(Map<String, String> nodeLabels) { this.nodeLabels = nodeLabels; }

        public List<String> getNodeTaints() { return nodeTaints; }
        public void setNodeTaints(List<String> nodeTaints) { this.nodeTaints = nodeTaints; }

        public List<String> getAvailabilityZones() { return availabilityZones; }
        public void setAvailabilityZones(List<String> availabilityZones) {
            this.availabilityZones = availabilityZones;
        }

        public List<AgentNode> getNodes() { return nodes; }
        public void setNodes(List<AgentNode> nodes) { this.nodes = nodes; }

        public Map<String, String> getAppliedNodeLabels() { return appliedNodeLabels; }
        public void setAppliedNodeLabels(Map<String, String> appliedNodeLabels) {
            this.appliedNodeLabels = appliedNodeLabels;
        }

        public List<String> getAppliedNodeTaints() { return appliedNodeTaints; }
        public void setAppliedNodeTaints(List<String> appliedNodeTaints) { this.appliedNodeTaints = appliedNodeTaints; }

        public List<AgentNode> nodes() {
            if (nodes == null) {
                nodes = new ArrayList<>();
            }
            return nodes;
        }

        public Map<String, String> labels() {
            return nodeLabels == null ? Map.of() : nodeLabels;
        }

        public List<String> taints() {
            return nodeTaints == null ? List.of() : nodeTaints;
        }

        public boolean isSystem() {
            return "System".equalsIgnoreCase(mode);
        }

        public static AgentPoolProfile defaultPool() {
            AgentPoolProfile p = new AgentPoolProfile();
            p.name = "nodepool1";
            p.count = 1;
            p.vmSize = "Standard_DS2_v2";
            p.osType = "Linux";
            p.osSku = "Ubuntu";
            p.mode = "System";
            p.provisioningState = "Succeeded";
            return p;
        }
    }

    /** One node of an agent pool: a k3s agent container registered under an AKS node name. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AgentNode {
        private String name;
        private int index;
        private String containerId;
        private String image;

        public AgentNode() {
        }

        public AgentNode(String name, int index, String containerId, String image) {
            this.name = name;
            this.index = index;
            this.containerId = containerId;
            this.image = image;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }

        public String getContainerId() { return containerId; }
        public void setContainerId(String containerId) { this.containerId = containerId; }

        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
    }
}
