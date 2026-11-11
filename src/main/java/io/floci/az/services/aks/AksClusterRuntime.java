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
import java.util.function.Supplier;

/**
 * What runs inside a cluster's containers: an agentless k3s server playing the managed control plane, and one k3s
 * agent per agent pool node, registered under its AKS node name with AKS labels, taints and provider ID.
 */
final class AksClusterRuntime {

    private static final Logger LOG = Logger.getLogger(AksClusterRuntime.class);

    static final String WEBHOOK_DIR = "/etc/floci";
    static final String AUTHN_CONFIG = WEBHOOK_DIR + "/aks-authn.yaml";
    static final String AUTHZ_CONFIG = WEBHOOK_DIR + "/aks-authz.yaml";
    static final String ADMIN_GROUPS_MANIFEST = "floci-aks-admin-groups.yaml";

    private final AksClusterManager manager;
    private final AksVersions versions;
    private final int maxNodesPerCluster;
    private final Supplier<String> flociUrl;
    private final int webhookCacheTtlSeconds;

    AksClusterRuntime(AksClusterManager manager, AksVersions versions, int maxNodesPerCluster) {
        this(manager, versions, maxNodesPerCluster, () -> "http://host.docker.internal:4577", 5);
    }

    /**
     * @param flociUrl               floci-az's base URL as the cluster's containers reach it
     * @param webhookCacheTtlSeconds how long the API server caches a webhook's answer
     */
    AksClusterRuntime(AksClusterManager manager, AksVersions versions, int maxNodesPerCluster,
                      Supplier<String> flociUrl, int webhookCacheTtlSeconds) {
        this.manager = manager;
        this.versions = versions;
        this.maxNodesPerCluster = maxNodesPerCluster;
        this.flociUrl = flociUrl;
        this.webhookCacheTtlSeconds = webhookCacheTtlSeconds;
    }

    /**
     * k3s server arguments. The server runs no kubelet, so only agent pool nodes register, and reaches the kubelets
     * through the agents' tunnels. k3s's own local-path storage gives way to the AKS storage classes. The API
     * certificate must be valid for every host the cluster is addressed by: {@code localhost} always, the container
     * name agents join by, plus the Docker daemon's host in published mode. The network ranges come last.
     */
    List<String> serverArgs(ManagedCluster cluster) {
        return serverArgs(cluster, false);
    }

    /**
     * @param legacy the arguments of a server created before arguments were recorded: none of the features added since
     */
    List<String> serverArgs(ManagedCluster cluster, boolean legacy) {
        List<String> args = new ArrayList<>(List.of("server", "--disable-agent", "--egress-selector-mode=cluster",
                "--disable=traefik", "--disable=local-storage", "--tls-san=localhost",
                "--tls-san=" + manager.containerName(cluster)));
        manager.daemonTlsSan().ifPresent(san -> args.add("--tls-san=" + san));
        args.addAll(AksNetwork.serverArgs(cluster.getNetworkProfile()));
        if (legacy) {
            return args;
        }
        if (cluster.oidcIssuerEnabled()) {
            args.addAll(issuerArgs(cluster.getOidcIssuerUrl()));
        }
        if (cluster.entraEnabled()) {
            args.addAll(List.of(
                    "--kube-apiserver-arg=authentication-token-webhook-config-file=" + AUTHN_CONFIG,
                    "--kube-apiserver-arg=authentication-token-webhook-version=v1",
                    "--kube-apiserver-arg=authentication-token-webhook-cache-ttl=" + webhookCacheTtlSeconds + "s"));
        }
        if (azureRbac(cluster)) {
            args.addAll(List.of(
                    "--kube-apiserver-arg=authorization-mode=Node,RBAC,Webhook",
                    "--kube-apiserver-arg=authorization-webhook-config-file=" + AUTHZ_CONFIG,
                    "--kube-apiserver-arg=authorization-webhook-version=v1",
                    "--kube-apiserver-arg=authorization-webhook-cache-authorized-ttl=" + webhookCacheTtlSeconds + "s",
                    "--kube-apiserver-arg=authorization-webhook-cache-unauthorized-ttl=" + webhookCacheTtlSeconds
                            + "s"));
        }
        return args;
    }

    /**
     * Files k3s reads only when it starts (absolute path to content); they count as server arguments. An Entra
     * cluster's API server gets the kubeconfig of floci-az's token webhook.
     */
    Map<String, String> startupFiles(ManagedCluster cluster) {
        return startupFiles(cluster, false);
    }

    Map<String, String> startupFiles(ManagedCluster cluster, boolean legacy) {
        Map<String, String> files = new LinkedHashMap<>();
        if (legacy) {
            return files;
        }
        if (cluster.entraEnabled()) {
            files.put(AUTHN_CONFIG, webhookKubeconfig(webhookUrl(cluster, "token-review")));
        }
        if (azureRbac(cluster)) {
            files.put(AUTHZ_CONFIG, webhookKubeconfig(webhookUrl(cluster, "subject-access-review")));
        }
        return files;
    }

    /**
     * The service account issuer of a cluster with the OIDC issuer: tokens are signed under the AKS-format URL, which
     * k3s puts first ({@code -=} prepends, so its default issuer stays accepted for tokens issued before), the
     * discovery document names the AKS-format JWKS URL, and token lifetimes follow AKS: no extended expiration
     * (from 1.30) and at most 24 hours ({@code use-oidc-issuer.md}).
     */
    static List<String> issuerArgs(String issuerUrl) {
        return List.of(
                "--kube-apiserver-arg=service-account-issuer-=" + issuerUrl,
                "--kube-apiserver-arg=service-account-jwks-uri=" + issuerUrl + "openid/v1/jwks",
                "--kube-apiserver-arg=service-account-extend-token-expiration=false",
                "--kube-apiserver-arg=service-account-max-token-expiration=24h");
    }

    /**
     * Azure RBAC: the authorization webhook comes after Node and RBAC, so a Kubernetes RoleBinding still grants what
     * it grants and the webhook decides only what RBAC has no opinion on, the union AKS describes
     * ({@code concepts-cluster-authorization.md}).
     */
    private static boolean azureRbac(ManagedCluster cluster) {
        return cluster.entraEnabled() && cluster.getAadProfile().azureRbac();
    }

    /** The hash of the arguments a server created before they were recorded runs with. */
    String legacyServerHash(ManagedCluster cluster) {
        return AksClusterManager.serverHash(serverArgs(cluster, true), startupFiles(cluster, true));
    }

    String webhookUrl(ManagedCluster cluster, String review) {
        return flociUrl.get() + "/_floci/aks/clusters/" + cluster.getInstanceId() + "/" + review;
    }

    /** The kubeconfig format webhook configuration files take: one cluster, the webhook's URL, no credential. */
    static String webhookKubeconfig(String url) {
        return """
                apiVersion: v1
                kind: Config
                clusters:
                - name: floci-az
                  cluster:
                    server: %s
                users:
                - name: kube-apiserver
                  user: {}
                contexts:
                - name: webhook
                  context:
                    cluster: floci-az
                    user: kube-apiserver
                current-context: webhook
                """.formatted(url);
    }

    /**
     * Manifests k3s applies from its auto-deploy directory: the storage classes AKS creates, and for an Entra cluster
     * the ClusterRoleBinding of its admin groups. k3s re-applies a manifest whose content changed, so they are
     * written again on every reconcile.
     */
    Map<String, String> manifests(ManagedCluster cluster) {
        Version controlPlane = Version.parse(cluster.getCurrentKubernetesVersion()).orElseGet(versions::defaultVersion);
        Map<String, String> manifests = new LinkedHashMap<>();
        manifests.put(AksStorageManifests.FILE_NAME, AksStorageManifests.manifest(controlPlane));
        if (cluster.entraEnabled()) {
            manifests.put(ADMIN_GROUPS_MANIFEST, adminGroupsBinding(cluster));
        }
        return manifests;
    }

    /**
     * The ClusterRoleBinding of {@code adminGroupObjectIDs} to {@code cluster-admin}. Its name is not documented; with
     * Azure RBAC the admin groups are irrelevant ({@code concepts-identity.md}), so it names no subject, and an empty
     * list keeps the file in place for k3s instead of leaving a stale binding behind.
     */
    static String adminGroupsBinding(ManagedCluster cluster) {
        List<String> groups = cluster.getAadProfile().azureRbac() ? List.of() : cluster.getAadProfile().adminGroups();
        StringBuilder yaml = new StringBuilder("""
                apiVersion: rbac.authorization.k8s.io/v1
                kind: ClusterRoleBinding
                metadata:
                  name: aks-cluster-admin-binding-aad
                  labels:
                    kubernetes.azure.com/managed-by: floci-az
                roleRef:
                  apiGroup: rbac.authorization.k8s.io
                  kind: ClusterRole
                  name: cluster-admin
                """);
        if (groups.isEmpty()) {
            yaml.append("subjects: []\n");
        } else {
            yaml.append("subjects:\n");
            groups.forEach(group -> yaml.append("""
                    - apiGroup: rbac.authorization.k8s.io
                      kind: Group
                      name: "%s"
                    """.formatted(group)));
        }
        return yaml.toString();
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
