package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.AgentNode;
import io.floci.az.services.aks.AksModels.AgentPoolProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.floci.az.services.aks.AksVersions.Version;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What runs inside a cluster's containers: an agentless k3s server playing the managed control plane, and one k3s
 * agent per agent pool node, registered under its AKS node name with AKS labels, taints and provider ID.
 */
final class AksClusterRuntime {

    private static final Logger LOG = Logger.getLogger(AksClusterRuntime.class);

    private final AksClusterManager manager;
    private final AksVersions versions;
    private final int maxNodesPerCluster;

    AksClusterRuntime(AksClusterManager manager, AksVersions versions, int maxNodesPerCluster) {
        this.manager = manager;
        this.versions = versions;
        this.maxNodesPerCluster = maxNodesPerCluster;
    }

    /**
     * k3s server arguments. The server runs no kubelet, so only agent pool nodes register, and reaches the kubelets
     * through the agents' tunnels. k3s's own local-path storage gives way to the AKS storage classes. The API
     * certificate must be valid for every host the cluster is addressed by: {@code localhost} always, the container
     * name agents join by, plus the Docker daemon's host in published mode.
     */
    List<String> serverArgs(ManagedCluster cluster) {
        List<String> args = new ArrayList<>(List.of("server", "--disable-agent", "--egress-selector-mode=cluster",
                "--disable=traefik", "--disable=local-storage", "--tls-san=localhost",
                "--tls-san=" + manager.containerName(cluster)));
        manager.daemonTlsSan().ifPresent(san -> args.add("--tls-san=" + san));
        return args;
    }

    /** Manifests k3s applies from its auto-deploy directory: the storage classes AKS creates. */
    Map<String, String> manifests(ManagedCluster cluster) {
        Version controlPlane = Version.parse(cluster.getCurrentKubernetesVersion()).orElseGet(versions::defaultVersion);
        return Map.of(AksStorageManifests.FILE_NAME, AksStorageManifests.manifest(controlPlane));
    }

    List<String> agentArgs(ManagedCluster cluster, AgentPoolProfile pool, String hash, int index,
                           AksVmSizes.VmSize size) {
        List<String> args = new ArrayList<>(List.of("agent",
                "--node-name=" + AksNodeLayout.nodeName(pool.getName(), hash, index)));
        AksNodeLayout.labels(cluster, pool, index, size).forEach((k, v) -> args.add("--node-label=" + k + "=" + v));
        pool.taints().forEach(t -> args.add("--node-taint=" + t));
        args.add("--kubelet-arg=provider-id=" + AksNodeLayout.providerId(cluster, pool, hash, index));
        if (pool.getMaxPods() != null) {
            args.add("--kubelet-arg=max-pods=" + pool.getMaxPods());
        }
        return args;
    }

    /**
     * Brings every pool's nodes to what the pool asks for under the node cap: removes nodes past the count, creates
     * missing ones, recreates those on another Kubernetes version, waits until they are Ready, then applies the
     * pool's labels and taints to the running nodes.
     */
    void reconcileNodes(ManagedCluster cluster, AksReconciler.Persistence persistence) {
        String hash = AksNodeLayout.clusterHash(cluster.getInstanceId());
        Map<String, Integer> allocation = AksNodeLayout.allocate(cluster.pools(), maxNodesPerCluster);
        warnIfCapped(cluster, allocation);
        List<String> desiredNodes = new ArrayList<>();
        for (AgentPoolProfile pool : cluster.pools()) {
            int desired = allocation.getOrDefault(pool.getName(), 0);
            removeExtraNodes(cluster, pool, desired, persistence);
            String image = versions.image(poolVersion(cluster, pool));
            AksVmSizes.VmSize size = AksVmSizes.of(pool.getVmSize());
            for (int index = 0; index < desired; index++) {
                String nodeName = AksNodeLayout.nodeName(pool.getName(), hash, index);
                AgentNode node = pool.nodes().stream().filter(n -> nodeName.equals(n.getName())).findFirst()
                        .orElseGet(() -> new AgentNode(nodeName, 0, null, null));
                node.setIndex(index);
                manager.ensureAgent(cluster, node, new AksClusterManager.AgentSpec(nodeName, image,
                        agentArgs(cluster, pool, hash, index, size), size.vcpus(), size.memoryBytes()));
                persistNode(pool.getName(), node, persistence);
                desiredNodes.add(nodeName);
            }
        }
        if (desiredNodes.isEmpty()) {
            return;
        }
        persistence.waitUntil(() -> allReady(cluster, desiredNodes),
                "agent pool nodes did not become Ready: " + desiredNodes);
        for (AgentPoolProfile pool : cluster.pools()) {
            int desired = allocation.getOrDefault(pool.getName(), 0);
            if (desired > 0) {
                applyLabelsAndTaints(cluster, pool, hash, desired, persistence);
            }
        }
    }

    private Version poolVersion(ManagedCluster cluster, AgentPoolProfile pool) {
        return Version.parse(pool.getCurrentOrchestratorVersion())
                .or(() -> Version.parse(cluster.getCurrentKubernetesVersion()))
                .orElseGet(versions::defaultVersion);
    }

    private void warnIfCapped(ManagedCluster cluster, Map<String, Integer> allocation) {
        List<String> short_ = new ArrayList<>();
        for (AgentPoolProfile pool : cluster.pools()) {
            int granted = allocation.getOrDefault(pool.getName(), 0);
            if (!"Deleting".equals(pool.getProvisioningState()) && granted < pool.getCount()) {
                short_.add(pool.getName() + " (" + granted + " of " + pool.getCount() + ")");
            }
        }
        if (!short_.isEmpty()) {
            LOG.warnv("AKS cluster {0}: floci-az.services.aks.max-nodes-per-cluster={1} caps its node containers;"
                    + " pools short of nodes: {2}. The API still reports the requested counts", cluster.getName(),
                    maxNodesPerCluster, String.join(", ", short_));
        }
    }

    private void removeExtraNodes(ManagedCluster cluster, AgentPoolProfile pool, int desired,
                                  AksReconciler.Persistence persistence) {
        List<AgentNode> extra = pool.nodes().stream()
                .filter(n -> n.getIndex() >= desired)
                .sorted(Comparator.comparingInt(AgentNode::getIndex).reversed())
                .toList();
        for (AgentNode node : extra) {
            manager.removeAgent(cluster, node.getName(), cluster.getContainerId() != null);
            persistence.persist(current -> current.pool(pool.getName())
                    .ifPresent(p -> p.nodes().removeIf(n -> n.getName().equals(node.getName()))));
        }
        pool.nodes().removeAll(extra);
    }

    private static void persistNode(String poolName, AgentNode node, AksReconciler.Persistence persistence) {
        persistence.persist(current -> current.pool(poolName).ifPresent(p -> {
            p.nodes().removeIf(n -> n.getName().equals(node.getName()));
            p.nodes().add(new AgentNode(node.getName(), node.getIndex(), node.getContainerId(), node.getImage()));
            p.nodes().sort(Comparator.comparingInt(AgentNode::getIndex));
        }));
    }

    private boolean allReady(ManagedCluster cluster, List<String> nodeNames) {
        Map<String, Boolean> readiness = manager.nodeReadiness(cluster);
        return nodeNames.stream().allMatch(n -> Boolean.TRUE.equals(readiness.get(n)));
    }

    /**
     * Applies the pool's labels to its running nodes, and removes the user labels and taints a previous request
     * set that this one dropped. Run after the nodes are Ready: k3s's cloud controller rewrites the instance-type
     * labels when it initializes a node, so they are set again here. A GPU size's nodes get their
     * {@code nvidia.com/gpu} capacity, which the kubelet keeps since no device plugin manages it.
     */
    private void applyLabelsAndTaints(ManagedCluster cluster, AgentPoolProfile pool, String hash, int desired,
                                      AksReconciler.Persistence persistence) {
        AksVmSizes.VmSize size = AksVmSizes.of(pool.getVmSize());
        Map<String, String> applied = Optional.ofNullable(pool.getAppliedNodeLabels()).orElse(Map.of());
        List<String> appliedTaints = Optional.ofNullable(pool.getAppliedNodeTaints()).orElse(List.of());
        for (int index = 0; index < desired; index++) {
            String nodeName = AksNodeLayout.nodeName(pool.getName(), hash, index);
            List<String> labelArgs = new ArrayList<>(List.of("label", "node", nodeName, "--overwrite"));
            AksNodeLayout.labels(cluster, pool, index, size).forEach((k, v) -> labelArgs.add(k + "=" + v));
            manager.kubectl(cluster, labelArgs.toArray(String[]::new));
            for (String key : applied.keySet()) {
                if (!pool.labels().containsKey(key)) {
                    removeQuietly(cluster, nodeName, "label", "node", nodeName, key + "-");
                }
            }
            for (String taint : pool.taints()) {
                manager.kubectl(cluster, "taint", "node", nodeName, taint, "--overwrite");
            }
            if (size.gpus() > 0) {
                String gpus = "{\"nvidia.com/gpu\":\"" + size.gpus() + "\"}";
                manager.kubectl(cluster, "patch", "node", nodeName, "--subresource=status", "--type=merge", "-p",
                        "{\"status\":{\"capacity\":" + gpus + ",\"allocatable\":" + gpus + "}}");
            }
            for (String taint : appliedTaints) {
                if (!pool.taints().contains(taint)) {
                    removeQuietly(cluster, nodeName, "taint", "node", nodeName, removal(taint));
                }
            }
        }
        Map<String, String> labels = new LinkedHashMap<>(pool.labels());
        List<String> taints = new ArrayList<>(pool.taints());
        persistence.persist(current -> current.pool(pool.getName()).ifPresent(p -> {
            p.setAppliedNodeLabels(labels);
            p.setAppliedNodeTaints(taints);
        }));
    }

    /** Removes a label or taint the node may no longer carry, for instance after it was recreated without it. */
    private void removeQuietly(ManagedCluster cluster, String nodeName, String... args) {
        try {
            manager.kubectl(cluster, args);
        } catch (RuntimeException e) {
            LOG.debugv("Node {0} of AKS cluster {1}: {2}", nodeName, cluster.getName(), e.getMessage());
        }
    }

    /** {@code key=value:Effect} becomes {@code key:Effect-}, the form kubectl removes a taint by. */
    static String removal(String taint) {
        int colon = taint.lastIndexOf(':');
        String key = taint.substring(0, colon);
        int equals = key.indexOf('=');
        return (equals < 0 ? key : key.substring(0, equals)) + taint.substring(colon) + "-";
    }
}
