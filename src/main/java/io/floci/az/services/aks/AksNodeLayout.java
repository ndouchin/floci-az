package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.AgentPoolProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How AKS names, labels and places agent pool nodes: VMSS instance names, the {@code azure://} provider ID, the
 * reserved {@code kubernetes.azure.com/} labels, zones, and how a per-cluster cap on node containers is shared out.
 */
final class AksNodeLayout {

    private static final Pattern TAINT = Pattern.compile("[^=:\\s]+(=[^=:\\s]*)?:(NoSchedule|PreferNoSchedule|NoExecute)");
    private static final Set<String> RESERVED_LABEL_DOMAINS = Set.of("kubernetes.azure.com", "kubernetes.io",
            "k8s.io");

    private AksNodeLayout() {
    }

    /** The 8-digit number AKS puts in every node name of a cluster, derived here from the cluster's instance. */
    static String clusterHash(String instanceId) {
        long value = Long.parseUnsignedLong(AksHashes.sha256Hex(instanceId).substring(0, 12), 16);
        return String.format("%08d", value % 100_000_000L);
    }

    static String vmssName(String pool, String hash) {
        return "aks-" + pool + "-" + hash + "-vmss";
    }

    /** A scale set instance's computer name: the VMSS name and the instance index in base 36 over six characters. */
    static String nodeName(String pool, String hash, int index) {
        String suffix = Integer.toString(index, 36);
        return vmssName(pool, hash) + "0".repeat(Math.max(0, 6 - suffix.length())) + suffix;
    }

    static String providerId(ManagedCluster cluster, AgentPoolProfile pool, String hash, int index) {
        return "azure:///subscriptions/" + cluster.getSubscriptionId() + "/resourceGroups/"
                + cluster.getNodeResourceGroup() + "/providers/Microsoft.Compute/virtualMachineScaleSets/"
                + vmssName(pool.getName(), hash) + "/virtualMachines/" + index;
    }

    /** {@code <region>-<zone>} for a zonal pool, spreading nodes round-robin, and {@code 0} without zones. */
    static String zone(ManagedCluster cluster, AgentPoolProfile pool, int index) {
        List<String> zones = pool.getAvailabilityZones();
        if (zones == null || zones.isEmpty()) {
            return "0";
        }
        return cluster.getLocation() + "-" + zones.get(index % zones.size());
    }

    static Map<String, String> labels(ManagedCluster cluster, AgentPoolProfile pool, int index,
                                      AksVmSizes.VmSize size) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("agentpool", pool.getName());
        labels.put("kubernetes.azure.com/agentpool", pool.getName());
        labels.put("kubernetes.azure.com/cluster", cluster.getNodeResourceGroup());
        labels.put("kubernetes.azure.com/managedby", "aks");
        labels.put("kubernetes.azure.com/mode", pool.isSystem() ? "system" : "user");
        labels.put("kubernetes.azure.com/role", "agent");
        labels.put("kubernetes.azure.com/os-sku", pool.getOsSku() == null ? "Ubuntu" : pool.getOsSku());
        labels.put("kubernetes.azure.com/nodepool-type", "VirtualMachineScaleSets");
        labels.put("kubernetes.azure.com/sku-cpu", Integer.toString(size.vcpus()));
        labels.put("kubernetes.azure.com/sku-memory",
                BigDecimal.valueOf(size.memoryGiB()).stripTrailingZeros().toPlainString());
        labels.put("node.kubernetes.io/instance-type", pool.getVmSize());
        labels.put("beta.kubernetes.io/instance-type", pool.getVmSize());
        labels.put("topology.kubernetes.io/region", cluster.getLocation());
        labels.put("topology.kubernetes.io/zone", zone(cluster, pool, index));
        if (size.gpus() > 0) {
            labels.put("kubernetes.azure.com/accelerator", "nvidia");
            labels.put("accelerator", "nvidia");
        }
        labels.putAll(pool.labels());
        return labels;
    }

    /**
     * Node containers per pool under a per-cluster cap: System pools are served first, then the others in creation
     * order, each getting what is left up to its count. A pool with count 0 or being deleted gets none. The map
     * keeps the pools' order.
     */
    static Map<String, Integer> allocate(List<AgentPoolProfile> pools, int cap) {
        Map<String, Integer> nodes = new LinkedHashMap<>();
        pools.forEach(p -> nodes.put(p.getName(), 0));
        int remaining = Math.max(0, cap);
        for (boolean system : new boolean[] {true, false}) {
            for (AgentPoolProfile pool : pools) {
                if (pool.isSystem() != system || "Deleting".equals(pool.getProvisioningState())) {
                    continue;
                }
                int granted = Math.min(Math.max(0, pool.getCount()), remaining);
                nodes.put(pool.getName(), granted);
                remaining -= granted;
            }
        }
        return nodes;
    }

    /** @throws IllegalArgumentException for a taint that is not {@code key[=value]:Effect} */
    static void validateTaints(List<String> taints) {
        for (String taint : taints) {
            if (taint == null || !TAINT.matcher(taint).matches()) {
                throw new IllegalArgumentException("Taint '" + taint + "' is invalid: a taint is key=value:Effect"
                        + " or key:Effect, Effect being NoSchedule, PreferNoSchedule or NoExecute.");
            }
        }
    }

    /** @throws IllegalArgumentException for a label under a prefix AKS reserves */
    static void validateLabels(Map<String, String> labels) {
        for (String key : labels.keySet()) {
            int slash = key.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String domain = key.substring(0, slash);
            boolean reserved = RESERVED_LABEL_DOMAINS.stream()
                    .anyMatch(r -> domain.equals(r) || domain.endsWith("." + r));
            if (reserved) {
                throw new IllegalArgumentException("Label '" + key + "' is invalid: the kubernetes.azure.com/ and"
                        + " kubernetes.io/ prefixes are reserved.");
            }
        }
    }
}
