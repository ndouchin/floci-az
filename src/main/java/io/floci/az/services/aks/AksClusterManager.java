package io.floci.az.services.aks;

import com.github.dockerjava.api.model.Container;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.CurrentContainerNetworkResolver;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.aks.AksModels.AgentNode;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the Docker side of AKS clusters: a per-cluster network, the k3s server container that plays the managed
 * control plane, and one k3s agent container per agent pool node. Not used when
 * {@code floci-az.services.aks.mocked=true}.
 */
@ApplicationScoped
public class AksClusterManager {

    private static final Logger LOG = Logger.getLogger(AksClusterManager.class);
    static final int K3S_API_SERVER_PORT = 6443;
    static final String K3S_DATA_DIR = "/var/lib/rancher/k3s";
    static final String MANIFESTS_DIR = K3S_DATA_DIR + "/server/manifests";
    static final String STORAGE_DIR = "/var/lib/floci-aks-storage";
    static final String ROLE_LABEL = "io.floci.aks.role";
    static final String NODE_LABEL = "io.floci.aks.node";
    /** Hash of the arguments and start-up files a server container was created with. */
    static final String SERVER_ARGS_LABEL = "io.floci.aks.server-args";

    /** How a server container came to run, which tells whether the cluster kept its CA. */
    enum ServerStart { ADOPTED, RECREATED_ON_VOLUME, CREATED_FRESH }

    /** What to do with the cluster's server container. */
    enum ServerDecision { ADOPT, REPLACE, CREATE }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final CurrentContainerNetworkResolver networkResolver;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    // Host port each running server container was given by the allocator, keyed by container id, so it goes back
    // to the pool when the container is removed. Without it the range drains over the process lifetime.
    private final Map<String, Integer> hostPorts = new ConcurrentHashMap<>();

    @Inject
    public AksClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             CurrentContainerNetworkResolver networkResolver,
                             PortAllocator portAllocator,
                             EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.networkResolver = networkResolver;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    // ── Server ────────────────────────────────────────────────────────────────

    /**
     * Makes the cluster's k3s server run {@code image} with {@code args}: adopts a surviving container created from
     * that image with those arguments, otherwise replaces it on the same data volume (an upgrade, changed arguments or
     * a lost container keep the cluster's state and CA), otherwise creates it on a fresh volume. {@code startupFiles}
     * (absolute path to content) are files k3s reads only when it starts, such as webhook kubeconfigs: they count as
     * arguments. {@code manifests} (file name to content) are dropped into the k3s auto-deploy directory before a new
     * container starts. Sets the container id, host port, image, argument hash and endpoints on {@code cluster}.
     *
     * @param legacyHash the hash of the arguments a server created before they were recorded runs with; an unlabelled
     *                   server adopted for a record without a hash is recorded with it, so a feature the record carries
     *                   that such a server lacks replaces it at the next change
     */
    public ServerStart prepareServer(ManagedCluster cluster, String image, List<String> args,
                                     Map<String, String> startupFiles, Map<String, String> manifests,
                                     String legacyHash) {
        String name = containerName(cluster);
        lifecycleManager.ensureNetwork(networkName(cluster), identityLabels(cluster));
        Optional<Container> existing = lifecycleManager.findByName(name);
        String hash = serverHash(args, startupFiles);
        ServerDecision decision = serverDecision(existing, image, hash, cluster.getServerArgsHash());
        if (decision == ServerDecision.ADOPT) {
            boolean unlabelled = existing.orElseThrow().getLabels() == null
                    || !existing.get().getLabels().containsKey(SERVER_ARGS_LABEL);
            adoptServer(cluster, existing.get().getId());
            cluster.setServerImage(image);
            cluster.setServerArgsHash(unlabelled && cluster.getServerArgsHash() == null && legacyHash != null
                    ? legacyHash : hash);
            return ServerStart.ADOPTED;
        }
        if (existing.isPresent()) {
            LOG.infov("AKS cluster {0}: replacing k3s server {1} with image {2}{3} on the same data volume",
                    cluster.getName(), existing.get().getImage(), image,
                    image.equals(existing.get().getImage()) ? " and new arguments" : "");
            lifecycleManager.stopAndRemove(existing.get().getId(), null);
        }
        releaseHostPort(cluster.getContainerId());
        boolean volumeExisted = lifecycleManager.volumeExists(name);
        Map<String, String> files = new LinkedHashMap<>(startupFiles);
        manifests.forEach((file, content) -> files.put(MANIFESTS_DIR + "/" + file, content));
        createServer(cluster, image, args, files, hash);
        cluster.setServerImage(image);
        cluster.setServerArgsHash(hash);
        return volumeExisted ? ServerStart.RECREATED_ON_VOLUME : ServerStart.CREATED_FRESH;
    }

    /**
     * Adopt a container of the same image whose argument label matches. A container without the label was created
     * before arguments were recorded: it is adopted as it runs while the record names no arguments or the same ones,
     * and replaced once the record's arguments change.
     */
    static ServerDecision serverDecision(Optional<Container> existing, String image, String hash,
                                         String recordedHash) {
        if (existing.isEmpty()) {
            return ServerDecision.CREATE;
        }
        if (!image.equals(existing.get().getImage())) {
            return ServerDecision.REPLACE;
        }
        Map<String, String> labels = existing.get().getLabels();
        String label = labels == null ? null : labels.get(SERVER_ARGS_LABEL);
        if (label != null) {
            return label.equals(hash) ? ServerDecision.ADOPT : ServerDecision.REPLACE;
        }
        return recordedHash == null || recordedHash.equals(hash) ? ServerDecision.ADOPT : ServerDecision.REPLACE;
    }

    /** The hash of a server's arguments, in order, and of its start-up files, by path. */
    static String serverHash(List<String> args, Map<String, String> startupFiles) {
        StringBuilder text = new StringBuilder();
        args.forEach(arg -> text.append(arg).append('\n'));
        new TreeMap<>(startupFiles).forEach((path, content) ->
                text.append("--- ").append(path).append('\n').append(content).append('\n'));
        return AksHashes.sha256Hex(text.toString()).substring(0, 32);
    }

    private void createServer(ManagedCluster cluster, String image, List<String> args, Map<String, String> files,
                              String hash) {
        String containerName = containerName(cluster);
        LOG.infov("Starting k3s server for AKS cluster {0} using image {1}", cluster.getName(), image);
        // Pulled before the port is reserved, so a slow pull does not hold the reservation.
        lifecycleManager.ensureImageAvailable(image);
        int hostPort = claimHostPort(cluster.getHostPort());
        boolean volumeCreatedByThisStart = false;
        String containerId = null;
        try {
            lifecycleManager.removeIfExists(containerName);
            volumeCreatedByThisStart = !lifecycleManager.volumeExists(containerName);
            lifecycleManager.ensureVolume(containerName);
            ContainerSpec spec = containerBuilder.newContainer(image)
                    .withName(containerName)
                    .withLabels(identityLabels(cluster))
                    .withLabel(ROLE_LABEL, "server")
                    .withLabel(SERVER_ARGS_LABEL, hash)
                    .withCmd(args)
                    .withEnv("K3S_KUBECONFIG_MODE", "644")
                    .withEnv("K3S_TOKEN", cluster.getClusterToken())
                    .withPortBinding(K3S_API_SERVER_PORT, hostPort)
                    .withNamedVolume(containerName, K3S_DATA_DIR)
                    .withPrimaryNetwork(networkName(cluster))
                    .withHostDockerInternalOnLinux()
                    .withPrivileged(true)
                    .withLogRotation()
                    .build();
            containerId = lifecycleManager.create(spec);
            if (!files.isEmpty()) {
                Map<String, byte[]> bytes = new LinkedHashMap<>();
                files.forEach((path, content) -> bytes.put(path, content.getBytes(StandardCharsets.UTF_8)));
                lifecycleManager.copyFilesToContainer(containerId, bytes);
            }
            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
            hostPorts.put(info.containerId(), hostPort);
            cluster.setContainerId(info.containerId());
            cluster.setHostPort(hostPort);
            joinEmulatorNetwork(cluster, info.containerId());
            setEndpoints(cluster, info.containerId(), hostPort);
        } catch (RuntimeException e) {
            // Dispose whatever this start created. The volume is only removed when THIS start created it: a
            // pre-existing one holds the cluster's k3s state and must survive a transient start failure.
            try {
                lifecycleManager.removeIfExists(containerName);
                if (volumeCreatedByThisStart) {
                    lifecycleManager.removeVolume(containerName);
                }
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            } finally {
                portAllocator.release(hostPort);
                if (containerId != null) {
                    hostPorts.remove(containerId);
                }
            }
            throw e;
        }
        LOG.infov("k3s server {0} started for AKS cluster {1} on port {2} (internal: {3})",
                cluster.getContainerId(), cluster.getName(), hostPort, cluster.getInternalEndpoint());
    }

    private void adoptServer(ManagedCluster cluster, String containerId) {
        ContainerLifecycleManager.ContainerInfo info = lifecycleManager.adopt(containerId,
                List.of(K3S_API_SERVER_PORT));
        OptionalInt published = info.publishedHostPort(K3S_API_SERVER_PORT);
        int hostPort = published.orElse(cluster.getHostPort() == null ? 0 : cluster.getHostPort());
        if (hostPort > 0 && portAllocator.reserveUnprobed(hostPort)) {
            hostPorts.put(containerId, hostPort);
        }
        cluster.setContainerId(containerId);
        cluster.setHostPort(hostPort);
        joinEmulatorNetwork(cluster, containerId);
        setEndpoints(cluster, containerId, hostPort);
        LOG.infov("Adopted k3s server {0} for AKS cluster {1} on port {2}", containerId, cluster.getName(), hostPort);
    }

    /** A previously used host port when it is still free, so kubeconfigs survive an upgrade; else a new one. */
    private int claimHostPort(Integer previous) {
        if (previous != null && previous > 0) {
            int claimed = portAllocator.claimOrZero(previous);
            if (claimed > 0) {
                return claimed;
            }
        }
        return portAllocator.allocate(config.services().aks().apiServerBasePort(),
                config.services().aks().apiServerMaxPort());
    }

    /**
     * The network floci-az itself reaches containers on: the configured one, or the one its own container is on.
     * The server joins it next to the cluster network, so in-Docker clients reach the API server as before.
     */
    private Optional<String> emulatorNetwork() {
        Optional<String> configured = config.services().dockerNetwork().filter(n -> !n.isBlank());
        if (configured.isPresent()) {
            return configured;
        }
        return containerDetector.isRunningInContainer() ? networkResolver.resolveNetworkName() : Optional.empty();
    }

    /**
     * Connects a server or agent container to the network floci-az is on, so the API server reaches its webhooks and
     * pods reach its Entra when floci-az runs in a container.
     */
    private void joinEmulatorNetwork(ManagedCluster cluster, String containerId) {
        Optional<String> network = emulatorNetwork().filter(n -> !n.equals(networkName(cluster)));
        if (network.isEmpty() || lifecycleManager.addressOnNetwork(containerId, network.get()).isPresent()) {
            return;
        }
        try {
            lifecycleManager.connectToNetwork(containerId, network.get());
        } catch (RuntimeException e) {
            LOG.warnv("Could not connect container {0} of AKS cluster {1} to network {2}: {3}", containerId,
                    cluster.getName(), network.get(), e.getMessage());
        }
    }

    private void setEndpoints(ManagedCluster cluster, String containerId, int hostPort) {
        String containerName = containerName(cluster);
        if (lifecycleManager.publishedEndpoints()) {
            cluster.setEndpoint("https://" + lifecycleManager.daemonHost() + ":" + hostPort);
            cluster.setInternalEndpoint(cluster.getEndpoint());
        } else if (containerDetector.isRunningInContainer()) {
            cluster.setEndpoint("https://" + containerName + ":" + K3S_API_SERVER_PORT);
            Optional<String> network = emulatorNetwork();
            Optional<String> address = network.flatMap(n -> lifecycleManager.addressOnNetwork(containerId, n));
            cluster.setInternalEndpoint(network.filter(ContainerLifecycleManager::resolvesContainerNames).isPresent()
                    || address.isEmpty()
                    ? cluster.getEndpoint()
                    : "https://" + address.get() + ":" + K3S_API_SERVER_PORT);
        } else {
            cluster.setEndpoint("https://localhost:" + hostPort);
            cluster.setInternalEndpoint(cluster.getEndpoint());
        }
    }

    /**
     * The Docker daemon's host when clients reach clusters at it (published endpoint mode) and it is not
     * {@code localhost}: the API certificate must name it too. A SAN takes the bare address, not the bracketed URL
     * form of an IPv6 literal.
     */
    public Optional<String> daemonTlsSan() {
        if (lifecycleManager.publishedEndpoints() && !"localhost".equals(lifecycleManager.daemonAddress())) {
            return Optional.of(lifecycleManager.daemonAddress());
        }
        return Optional.empty();
    }

    /** Writes {@code manifests} (file name to content) into the running server's k3s auto-deploy directory. */
    public void updateManifests(ManagedCluster cluster, Map<String, String> manifests) {
        if (cluster.getContainerId() == null || manifests.isEmpty()) {
            return;
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        manifests.forEach((file, content) ->
                files.put(MANIFESTS_DIR + "/" + file, content.getBytes(StandardCharsets.UTF_8)));
        lifecycleManager.copyFilesToContainer(cluster.getContainerId(), files);
    }

    /** True once the API server answers {@code /readyz}, asked from inside the server container. */
    public boolean isApiReady(ManagedCluster cluster) {
        if (cluster.getContainerId() == null) {
            return false;
        }
        try {
            ContainerLifecycleManager.ExecResult result = lifecycleManager.execInContainer(cluster.getContainerId(),
                    "kubectl", "get", "--raw=/readyz");
            return result.exitCode() == 0 && result.output().trim().endsWith("ok");
        } catch (RuntimeException e) {
            LOG.debugv("AKS cluster {0} not ready: {1}", cluster.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Reads the k3s admin kubeconfig from the server and keeps its CA, client certificate and key on the cluster.
     *
     * @throws IllegalStateException when the kubeconfig cannot be read or lacks a credential
     */
    public void extractCredentials(ManagedCluster cluster) {
        ContainerLifecycleManager.ExecResult result = lifecycleManager.execInContainer(cluster.getContainerId(),
                "cat", "/etc/rancher/k3s/k3s.yaml");
        if (result.exitCode() != 0) {
            throw new IllegalStateException("could not read the k3s kubeconfig: " + result.output().trim());
        }
        String yaml = result.output();
        String ca = extractYamlField(yaml, "certificate-authority-data");
        String cert = extractYamlField(yaml, "client-certificate-data");
        String key = extractYamlField(yaml, "client-key-data");
        if (ca == null || cert == null || key == null) {
            throw new IllegalStateException("the k3s kubeconfig has no CA or client credential");
        }
        cluster.setCaData(ca);
        cluster.setClientCertificateData(cert);
        cluster.setClientKeyData(key);
        LOG.infov("Read the kubeconfig of AKS cluster {0}", cluster.getName());
    }

    /**
     * Removes the Workload Identity add-on from a running server: its manifest, so k3s stops applying it, and its
     * objects, found by the add-on's system label.
     */
    public void removeWorkloadIdentityAddon(ManagedCluster cluster) {
        lifecycleManager.execInContainer(cluster.getContainerId(), "rm", "-f",
                MANIFESTS_DIR + "/" + AksWorkloadIdentityWebhook.MANIFEST);
        String selector = AksWorkloadIdentityWebhook.SYSTEM_LABEL + "=true";
        kubectl(cluster, "delete", "mutatingwebhookconfiguration,clusterrole,clusterrolebinding", "-l", selector,
                "--ignore-not-found");
        kubectl(cluster, "-n", "kube-system", "delete",
                "deployment,service,secret,configmap,serviceaccount,role,rolebinding,poddisruptionbudget", "-l",
                selector, "--ignore-not-found");
        LOG.infov("AKS cluster {0}: removed the Workload Identity add-on", cluster.getName());
    }

    /**
     * The gateway of the cluster's network, creating the network first: the Docker host on the same bridge as the
     * cluster's containers, which reach floci-az there when it runs natively on a Linux host.
     */
    public Optional<String> networkGateway(ManagedCluster cluster) {
        lifecycleManager.ensureNetwork(networkName(cluster), identityLabels(cluster));
        return lifecycleManager.networkGateway(networkName(cluster));
    }

    /**
     * The IPv4 address {@code host} resolves to inside the cluster's server container: its {@code /etc/hosts} first
     * (the {@code host-gateway} entry on Linux), then DNS (Docker Desktop's {@code host.docker.internal}).
     */
    public Optional<String> resolveInServer(ManagedCluster cluster, String host) {
        if (cluster.getContainerId() == null || !host.matches("[A-Za-z0-9.-]+")) {
            return Optional.empty();
        }
        try {
            ContainerLifecycleManager.ExecResult result = lifecycleManager.execInContainer(cluster.getContainerId(),
                    "sh", "-c", "awk '$2==\"" + host + "\"{print $1}' /etc/hosts; nslookup " + host
                            + " 2>/dev/null | awk '/^Address/{print $NF}'");
            return result.output().lines().map(String::trim)
                    .filter(line -> line.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && !line.startsWith("127."))
                    .findFirst();
        } catch (RuntimeException e) {
            LOG.debugv("AKS cluster {0}: could not resolve {1} in its server: {2}", cluster.getName(), host,
                    e.getMessage());
            return Optional.empty();
        }
    }

    /** The JWKS the API server publishes for its service account issuer ({@code /openid/v1/jwks}). */
    public String serviceAccountJwks(ManagedCluster cluster) {
        return kubectl(cluster, "get", "--raw", "/openid/v1/jwks").trim();
    }

    /**
     * Runs {@code kubectl} with {@code args} inside the server container.
     *
     * @throws IllegalStateException when kubectl exits non-zero
     */
    public String kubectl(ManagedCluster cluster, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "kubectl";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ContainerLifecycleManager.ExecResult result = lifecycleManager.execInContainer(cluster.getContainerId(), cmd);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("kubectl " + String.join(" ", args) + " failed: " + result.output().trim());
        }
        return result.output();
    }

    /** Runs {@code kubectl create -f -} in the server with {@code manifest}, passed through the environment. */
    public void kubectlCreate(ManagedCluster cluster, String manifest) {
        ContainerLifecycleManager.ExecResult result = lifecycleManager.execInContainer(cluster.getContainerId(),
                List.of("FLOCI_MANIFEST=" + manifest), "sh", "-c", "printf %s \"$FLOCI_MANIFEST\" | kubectl create -f -");
        if (result.exitCode() != 0) {
            throw new IllegalStateException("kubectl create failed: " + result.output().trim());
        }
    }

    // ── Agents ────────────────────────────────────────────────────────────────

    /** What an agent container needs that the cluster record does not hold. */
    record AgentSpec(String nodeName, String image, List<String> args, double cpus, long memoryBytes) {
    }

    /**
     * Makes the node's agent container run {@code spec}: adopts one created from the same image, otherwise
     * replaces or creates it. The node password is derived from the cluster token and the node name, so a node
     * recreated on a new image rejoins under its own name.
     *
     * @return the agent container id
     */
    public String ensureAgent(ManagedCluster cluster, AgentNode node, AgentSpec spec) {
        String name = agentContainerName(cluster, spec.nodeName());
        Optional<Container> existing = lifecycleManager.findByName(name);
        if (existing.isPresent() && spec.image().equals(existing.get().getImage())) {
            String adopted = lifecycleManager.adopt(existing.get().getId(), List.of()).containerId();
            joinEmulatorNetwork(cluster, adopted);
            return adopted;
        }
        existing.ifPresent(c -> lifecycleManager.stopAndRemove(c.getId(), null));
        lifecycleManager.ensureImageAvailable(spec.image());
        lifecycleManager.ensureVolume(storageVolumeName(cluster));
        int hostCpus = lifecycleManager.dockerCpuCount();
        ContainerSpec containerSpec = containerBuilder.newContainer(spec.image())
                .withName(name)
                .withHostname(spec.nodeName())
                .withLabels(identityLabels(cluster))
                .withLabel(ROLE_LABEL, "agent")
                .withLabel(NODE_LABEL, spec.nodeName())
                .withCmd(spec.args())
                .withEnv("K3S_URL", "https://" + containerName(cluster) + ":" + K3S_API_SERVER_PORT)
                .withEnv("K3S_TOKEN", cluster.getClusterToken())
                .withNamedVolume(storageVolumeName(cluster), STORAGE_DIR)
                .withPrimaryNetwork(networkName(cluster))
                .withCpus(Math.min(spec.cpus(), hostCpus))
                .withMemoryBytes(spec.memoryBytes())
                .withPrivileged(true)
                .withLogRotation()
                .build();
        String containerId = lifecycleManager.create(containerSpec);
        try {
            lifecycleManager.copyFilesToContainer(containerId, Map.of("/etc/rancher/node/password",
                    nodePassword(cluster, spec.nodeName()).getBytes(StandardCharsets.UTF_8)));
            lifecycleManager.startCreated(containerId, containerSpec);
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(name);
            throw e;
        }
        joinEmulatorNetwork(cluster, containerId);
        node.setContainerId(containerId);
        node.setImage(spec.image());
        LOG.infov("Started k3s agent {0} for node {1} of AKS cluster {2}", containerId, spec.nodeName(),
                cluster.getName());
        return containerId;
    }

    /** Removes a node: its agent container, and its Node object when the API server is up. */
    public void removeAgent(ManagedCluster cluster, String nodeName, boolean deleteNodeObject) {
        lifecycleManager.findByName(agentContainerName(cluster, nodeName))
                .ifPresent(c -> lifecycleManager.stopAndRemove(c.getId(), null));
        if (deleteNodeObject) {
            try {
                kubectl(cluster, "delete", "node", nodeName, "--ignore-not-found", "--wait=false");
            } catch (RuntimeException e) {
                LOG.warnv("Could not delete node {0} of AKS cluster {1}: {2}", nodeName, cluster.getName(),
                        e.getMessage());
            }
        }
        LOG.infov("Removed node {0} of AKS cluster {1}", nodeName, cluster.getName());
    }

    /** Node readiness as the API server reports it: node name to whether its Ready condition is True. */
    public Map<String, Boolean> nodeReadiness(ManagedCluster cluster) {
        Map<String, Boolean> ready = new HashMap<>();
        String out;
        try {
            out = kubectl(cluster, "get", "nodes", "-o",
                    "jsonpath={range .items[*]}{.metadata.name}{\"\\t\"}"
                            + "{.status.conditions[?(@.type==\"Ready\")].status}{\"\\t\"}"
                            + "{.spec.taints[*].key}{\"\\n\"}{end}");
        } catch (RuntimeException e) {
            LOG.debugv("Could not list nodes of AKS cluster {0}: {1}", cluster.getName(), e.getMessage());
            return ready;
        }
        for (String line : out.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length < 2 || parts[0].isBlank()) {
                continue;
            }
            boolean uninitialized = parts.length > 2
                    && parts[2].contains("node.cloudprovider.kubernetes.io/uninitialized");
            ready.put(parts[0].trim(), "True".equals(parts[1].trim()) && !uninitialized);
        }
        return ready;
    }

    // ── Power and removal ─────────────────────────────────────────────────────

    /** Stops the server and every agent without removing them, for a cluster that is stopped. */
    public void stopContainers(ManagedCluster cluster, List<String> nodeNames) {
        for (String nodeName : nodeNames) {
            lifecycleManager.findByName(agentContainerName(cluster, nodeName))
                    .ifPresent(c -> lifecycleManager.stop(c.getId(), 10));
        }
        if (cluster.getContainerId() != null) {
            lifecycleManager.stop(cluster.getContainerId(), 10);
        }
    }

    /**
     * Stops and removes the cluster's containers at emulator shutdown, unless {@code keepRunningOnShutdown} asks to
     * leave them running. An explicit delete uses {@link #removeCluster}.
     */
    public void stopCluster(ManagedCluster cluster) {
        if (cluster.getContainerId() == null) {
            return;
        }
        if (config.services().aks().keepRunningOnShutdown()) {
            LOG.infov("Leaving the containers of AKS cluster {0} running", cluster.getName());
            return;
        }
        removeCluster(cluster);
    }

    /**
     * Removes every container, volume and the network of the cluster whatever {@code keepRunningOnShutdown} says.
     * For a record that is being dropped for good, whose containers nothing could reach again once it is gone.
     */
    public void removeCluster(ManagedCluster cluster) {
        if (cluster.getContainerId() == null && cluster.getInstanceId() == null) {
            return;
        }
        for (String nodeName : nodeNames(cluster)) {
            lifecycleManager.findByName(agentContainerName(cluster, nodeName))
                    .ifPresent(c -> lifecycleManager.stopAndRemove(c.getId(), null));
        }
        for (AksModels.LoadBalancerRecord record : cluster.loadBalancers()) {
            if (record.getApplianceName() != null) {
                lifecycleManager.findByName(record.getApplianceName())
                        .ifPresent(c -> lifecycleManager.stopAndRemove(c.getId(), null));
            }
        }
        if (cluster.getContainerId() != null) {
            lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
        } else {
            lifecycleManager.findByName(containerName(cluster))
                    .ifPresent(c -> lifecycleManager.stopAndRemove(c.getId(), null));
        }
        lifecycleManager.removeVolume(containerName(cluster));
        lifecycleManager.removeVolume(storageVolumeName(cluster));
        lifecycleManager.removeNetwork(networkName(cluster));
        releaseHostPort(cluster.getContainerId());
        LOG.infov("Removed the containers of AKS cluster {0}", cluster.getName());
    }

    private static List<String> nodeNames(ManagedCluster cluster) {
        List<String> names = new ArrayList<>();
        cluster.pools().forEach(p -> p.nodes().forEach(n -> names.add(n.getName())));
        return names;
    }

    private void releaseHostPort(String containerId) {
        if (containerId == null) {
            return;
        }
        Integer hostPort = hostPorts.remove(containerId);
        if (hostPort != null) {
            portAllocator.release(hostPort);
        }
    }

    // ── Names ─────────────────────────────────────────────────────────────────

    String containerName(ManagedCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "aks-" + cluster.getInstanceId());
    }

    String agentContainerName(ManagedCluster cluster, String nodeName) {
        return containerName(cluster) + "-" + nodeName;
    }

    static String networkName(ManagedCluster cluster) {
        return "floci-aks-" + cluster.getInstanceId();
    }

    String storageVolumeName(ManagedCluster cluster) {
        return containerName(cluster) + "-storage";
    }

    private static Map<String, String> identityLabels(ManagedCluster cluster) {
        return ContainerStorageHelper.resourceIdentityLabels("aks", cluster.getName(), cluster.getSubscriptionId(),
                cluster.getResourceGroup(), cluster.getLocation());
    }

    static String nodePassword(ManagedCluster cluster, String nodeName) {
        return AksHashes.sha256Hex(cluster.getClusterToken() + ":" + nodeName).substring(0, 32);
    }

    static String extractYamlField(String yaml, String fieldName) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(fieldName + ":")) {
                return trimmed.substring(fieldName.length() + 1).trim();
            }
        }
        return null;
    }
}
