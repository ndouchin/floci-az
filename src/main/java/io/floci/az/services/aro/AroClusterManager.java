package io.floci.az.services.aro;

import com.github.dockerjava.api.model.Container;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerPresence;
import io.floci.az.core.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.az.core.docker.ContainerLifecycleManager.ExecResult;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.aro.AroModels.OpenShiftCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs one MicroShift container per Azure Red Hat OpenShift cluster, the way MINC runs it (privileged, systemd as
 * PID 1, CRI-O inside), with the cluster's settings in a {@code config.d} drop-in. Not used when
 * {@code floci-az.services.aro.mocked=true}.
 */
@ApplicationScoped
public class AroClusterManager {

    private static final Logger LOG = Logger.getLogger(AroClusterManager.class);

    static final int API_PORT = 6443;
    static final int ROUTER_HTTP_PORT = 80;
    static final int ROUTER_HTTPS_PORT = 443;
    private static final String DATA_DIR = "/var/lib/microshift";
    /** MINC's image store mount, which CRI-O's {@code storage.conf} lists as an additional image store. */
    private static final String IMAGE_STORE_DIR = "/host-container";
    private static final String KUBEADMIN_DIR = DATA_DIR + "/resources/kubeadmin";
    /** The namespaces whose pods must all run before a cluster counts as ready. */
    private static final Set<String> CORE_NAMESPACES = Set.of("openshift-dns", "openshift-ingress",
            "openshift-service-ca", "openshift-cluster-csi-drivers");

    /** What restoring a persisted cluster did. */
    public enum Restore {
        /** Its container survived and runs again; the cluster keeps its state and kubeconfig. */
        ADOPTED,
        /** A new container runs on the surviving data volume; the cluster CA is the same. */
        RECREATED_ON_VOLUME,
        /** Nothing survived; a new cluster with a new CA runs in its place. */
        RECREATED
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;

    @Inject
    public AroClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.config = config;
    }

    /**
     * Creates and starts the cluster's container, pulling the image first. Sets the container id, host ports,
     * endpoints and public address on the cluster; the cluster becomes usable once {@link #isReady} holds and
     * {@link #finalizeCluster} has run.
     */
    public void startCluster(OpenShiftCluster cluster) {
        String image = image(cluster.getImageTag());
        String containerName = containerName(cluster);
        LOG.infov("Starting MicroShift container {0} for ARO cluster {1} from {2}", containerName,
                cluster.getName(), image);
        lifecycleManager.ensureImageAvailable(image);

        List<Integer> ports = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                ports.add(portAllocator.allocate(config.services().aro().basePort(),
                        config.services().aro().maxPort()));
            }
        } catch (RuntimeException e) {
            ports.forEach(portAllocator::release);
            throw e;
        }
        String dataVolume = containerName + "-data";
        String storeVolume = containerName + "-storage";
        boolean dataCreatedHere = false;
        boolean storeCreatedHere = false;
        ContainerInfo info;
        String containerId = null;
        try {
            lifecycleManager.removeIfExists(containerName);
            dataCreatedHere = !lifecycleManager.volumeExists(dataVolume);
            storeCreatedHere = !lifecycleManager.volumeExists(storeVolume);
            lifecycleManager.ensureVolume(dataVolume);
            lifecycleManager.ensureVolume(storeVolume);
            ContainerSpec spec = containerBuilder.newContainer(image)
                    .withName(containerName)
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels("aro", cluster.armId(),
                            cluster.getSubscriptionId(), cluster.getResourceGroup(), cluster.getLocation()))
                    .withPortBinding(API_PORT, ports.get(0))
                    .withPortBinding(ROUTER_HTTP_PORT, ports.get(1))
                    .withPortBinding(ROUTER_HTTPS_PORT, ports.get(2))
                    .withNamedVolume(dataVolume, DATA_DIR)
                    .withNamedVolume(storeVolume, IMAGE_STORE_DIR)
                    // kube-proxy takes its node name from the hostname, MicroShift from hostnameOverride; when they
                    // differ, the router's LoadBalancer service has no local endpoint and drops outside traffic.
                    .withHostname(nodeName(cluster))
                    .withDockerNetwork(config.services().dockerNetwork())
                    .withPrivileged(true)
                    .withLogRotation()
                    .build();
            containerId = lifecycleManager.create(spec);
            lifecycleManager.copyFilesToContainer(containerId, AroManifests.files(settings(cluster, containerName)));
            info = lifecycleManager.startCreated(containerId, spec);
        } catch (RuntimeException e) {
            try {
                lifecycleManager.removeIfExists(containerName);
                if (dataCreatedHere) {
                    lifecycleManager.removeVolume(dataVolume);
                }
                if (storeCreatedHere) {
                    lifecycleManager.removeVolume(storeVolume);
                }
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            } finally {
                ports.forEach(portAllocator::release);
            }
            throw e;
        }
        cluster.setContainerId(info.containerId());
        cluster.setApiHostPort(ports.get(0));
        cluster.setHttpHostPort(ports.get(1));
        cluster.setHttpsHostPort(ports.get(2));
        applyEndpoints(cluster, containerName, info);
        LOG.infov("MicroShift container {0} started for ARO cluster {1}: API {2}, router http {3}, https {4}",
                info.containerId(), cluster.getName(), cluster.getEndpoint(), String.valueOf(ports.get(1)),
                String.valueOf(ports.get(2)));
    }

    /**
     * Brings a persisted cluster back after a restart: adopts its container when it survived, else starts a new one,
     * on the data volume when that survived. An adopted cluster is ready at once; a recreated one goes through
     * {@link #isReady} and {@link #finalizeCluster} again.
     */
    public Restore restore(OpenShiftCluster cluster) {
        String containerName = containerName(cluster);
        String containerId = cluster.getContainerId() != null
                && lifecycleManager.presence(cluster.getContainerId()) != ContainerPresence.ABSENT
                ? cluster.getContainerId()
                : lifecycleManager.findByName(containerName).map(Container::getId).orElse(null);
        if (containerId != null) {
            ContainerInfo info = lifecycleManager.adopt(containerId,
                    List.of(API_PORT, ROUTER_HTTP_PORT, ROUTER_HTTPS_PORT));
            cluster.setContainerId(containerId);
            cluster.setApiHostPort(claim(info, API_PORT, cluster.getApiHostPort()));
            cluster.setHttpHostPort(claim(info, ROUTER_HTTP_PORT, cluster.getHttpHostPort()));
            cluster.setHttpsHostPort(claim(info, ROUTER_HTTPS_PORT, cluster.getHttpsHostPort()));
            applyEndpoints(cluster, containerName, info);
            LOG.infov("Adopted MicroShift container {0} of ARO cluster {1}", containerId, cluster.getName());
            return Restore.ADOPTED;
        }
        boolean volumeSurvived = lifecycleManager.volumeExists(containerName + "-data");
        if (!volumeSurvived) {
            LOG.warnv("ARO cluster {0}: no container or data volume survived; starting a new cluster, whose CA and "
                    + "credentials differ from the old one's", cluster.getName());
        }
        startCluster(cluster);
        return volumeSurvived ? Restore.RECREATED_ON_VOLUME : Restore.RECREATED;
    }

    /**
     * True once MicroShift is active, the API server answers {@code /readyz} and every pod of the DNS, router,
     * service CA and storage namespaces runs.
     */
    public boolean isReady(OpenShiftCluster cluster) {
        String containerId = cluster.getContainerId();
        if (containerId == null || cluster.getInternalEndpoint() == null) {
            return false;
        }
        try {
            ExecResult active = lifecycleManager.execInContainer(containerId, "systemctl", "is-active", "microshift");
            if (active.exitCode() != 0) {
                return false;
            }
            if (!apiAnswers(cluster.getInternalEndpoint() + "/readyz")) {
                return false;
            }
            ExecResult pods = lifecycleManager.execInContainer(containerId, "oc", "get", "pods", "-A",
                    "--no-headers", "-o",
                    "custom-columns=NS:.metadata.namespace,PHASE:.status.phase,READY:.status.containerStatuses[*].ready");
            return pods.exitCode() == 0 && corePodsRunning(pods.output());
        } catch (RuntimeException e) {
            LOG.debugv("ARO cluster {0} not ready: {1}", cluster.getName(), e.toString());
            return false;
        }
    }

    /**
     * Gives the node its ARO labels and provider id, fills the status of the {@code config.openshift.io} objects and
     * reads the admin kubeconfig. Throws while the cluster objects are not applied yet; the caller retries.
     */
    public void finalizeCluster(OpenShiftCluster cluster) {
        String containerId = cluster.getContainerId();
        String nodeName = nodeName(cluster);
        String fullDomain = AroProperties.fullDomain(cluster.clusterProfileValue("domain"), cluster.getLocation());
        String clusterGroup = lastSegment(cluster.clusterProfileValue("resourceGroupId"));
        String vmSize = String.valueOf(cluster.profile("masterProfile").getOrDefault("vmSize",
                AroProperties.DEFAULT_MASTER_VM_SIZE));

        List<String> label = new ArrayList<>(List.of("oc", "label", "node", nodeName, "--overwrite"));
        label.addAll(AroManifests.nodeLabels(cluster, vmSize));
        oc(containerId, label.toArray(String[]::new));
        String providerId = AroManifests.providerId(cluster, clusterGroup, nodeName);
        ExecResult currentProvider = lifecycleManager.execInContainer(containerId, "oc", "get", "node", nodeName,
                "-o", "jsonpath={.spec.providerID}");
        if (currentProvider.output().isBlank()) {
            oc(containerId, "oc", "patch", "node", nodeName, "--type=merge",
                    "-p", "{\"spec\":{\"providerID\":\"" + providerId + "\"}}");
        }
        oc(containerId, "oc", "patch", "clusterversion", "version", "--subresource=status", "--type=merge",
                "-p", AroManifests.clusterVersionStatus(cluster.getImageTag(), Instant.now()));
        oc(containerId, "oc", "patch", "infrastructure", "cluster", "--subresource=status", "--type=merge",
                "-p", AroManifests.infrastructureStatus(fullDomain, cluster.getInfraId(), clusterGroup,
                        networkResourceGroup(cluster)));
        oc(containerId, "oc", "patch", "ingress.config.openshift.io", "cluster", "--subresource=status",
                "--type=merge", "-p", AroManifests.ingressStatus());

        String path = kubeconfigPath(cluster.getEndpoint());
        ExecResult kubeconfig = lifecycleManager.execInContainer(containerId, "cat", path);
        if (kubeconfig.exitCode() != 0 || !kubeconfig.output().contains("server:")) {
            throw new IllegalStateException("no kubeconfig at " + path + ": " + kubeconfig.output().strip());
        }
        String rewritten = kubeconfig.output().replaceAll("server: https://[^\\n]+", "server: " + cluster.getEndpoint());
        cluster.setKubeconfig(Base64.getEncoder().encodeToString(rewritten.getBytes(StandardCharsets.UTF_8)));
        LOG.infov("ARO cluster {0} finalized: node {1}, kubeconfig for {2}", cluster.getName(), nodeName,
                cluster.getEndpoint());
    }

    /** Removes the container at shutdown, unless {@code keepRunningOnShutdown} asks to leave it running. */
    public void stopCluster(OpenShiftCluster cluster) {
        if (config.services().aro().keepRunningOnShutdown()) {
            LOG.infov("Leaving MicroShift container of ARO cluster {0} running", cluster.getName());
            return;
        }
        removeCluster(cluster);
    }

    /** Removes the container and both volumes, and gives the host ports back. */
    public void removeCluster(OpenShiftCluster cluster) {
        String containerName = containerName(cluster);
        if (cluster.getContainerId() != null) {
            lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
        }
        lifecycleManager.removeIfExists(containerName);
        lifecycleManager.removeVolume(containerName + "-data");
        lifecycleManager.removeVolume(containerName + "-storage");
        for (int port : List.of(cluster.getApiHostPort(), cluster.getHttpHostPort(), cluster.getHttpsHostPort())) {
            if (port > 0) {
                portAllocator.release(port);
            }
        }
        LOG.infov("Removed MicroShift container {0} of ARO cluster {1}", containerName, cluster.getName());
    }

    /** The image a cluster runs: the configured repository, its version's tag and the daemon's architecture. */
    String image(String tag) {
        return config.services().aro().imageRepository() + ":" + tag
                + AroVersions.architectureSuffix(lifecycleManager.daemonArchitecture());
    }

    String containerName(OpenShiftCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "aro-" + cluster.getInstanceId());
    }

    /** The admin kubeconfig MicroShift writes for the endpoint's host: the generic one for localhost, else its own. */
    static String kubeconfigPath(String endpoint) {
        String host = URI.create(endpoint).getHost();
        return "localhost".equals(host) ? KUBEADMIN_DIR + "/kubeconfig" : KUBEADMIN_DIR + "/" + host + "/kubeconfig";
    }

    /** ARO names its first master {@code <infraID>-master-0}; masters and workers are one node here. */
    static String nodeName(OpenShiftCluster cluster) {
        return cluster.getInfraId() + "-master-0";
    }

    AroManifests.Settings settings(OpenShiftCluster cluster, String containerName) {
        String fullDomain = AroProperties.fullDomain(cluster.clusterProfileValue("domain"), cluster.getLocation());
        return new AroManifests.Settings(fullDomain, nodeName(cluster), subjectAltNames(containerName, fullDomain),
                cluster.networkProfileValue("podCidr"), cluster.networkProfileValue("serviceCidr"),
                cluster.getClusterUuid(), config.services().aro().storageProvisionerImage(),
                config.services().aro().storageHelperImage(),
                !(cluster.getProperties().get("platformWorkloadIdentityProfile") instanceof Map));
    }

    /**
     * The extra names on the API certificate: the container name (in-Docker clients), {@code api.<domain>} and, in
     * published mode, the daemon's address. The kubeconfig of each is generated under {@code resources/kubeadmin}.
     */
    List<String> subjectAltNames(String containerName, String fullDomain) {
        List<String> names = new ArrayList<>(List.of(containerName, "api." + fullDomain));
        if (lifecycleManager.publishedEndpoints()) {
            String daemon = lifecycleManager.daemonAddress();
            if (!"localhost".equals(daemon) && !"127.0.0.1".equals(daemon) && !names.contains(daemon)) {
                names.add(daemon);
            }
        }
        return names;
    }

    static boolean corePodsRunning(String podList) {
        Map<String, Boolean> namespaces = new HashMap<>();
        for (String line : podList.split("\n")) {
            String[] columns = line.trim().split("\\s+");
            if (columns.length < 3 || !CORE_NAMESPACES.contains(columns[0])) {
                continue;
            }
            boolean ready = "Running".equals(columns[1]) && !columns[2].contains("false");
            namespaces.merge(columns[0], ready, Boolean::logicalAnd);
        }
        return namespaces.size() == CORE_NAMESPACES.size() && namespaces.values().stream().allMatch(b -> b);
    }

    private void applyEndpoints(OpenShiftCluster cluster, String containerName, ContainerInfo info) {
        if (lifecycleManager.publishedEndpoints()) {
            // MicroShift's loopback certificate names localhost only, and it refuses loopback extra names.
            String host = "127.0.0.1".equals(lifecycleManager.daemonAddress()) ? "localhost"
                    : lifecycleManager.daemonHost();
            cluster.setEndpoint("https://" + host + ":" + cluster.getApiHostPort());
            cluster.setInternalEndpoint(cluster.getEndpoint());
            cluster.setPublicIp(ipOf(lifecycleManager.daemonAddress()));
        } else if (containerDetector.isRunningInContainer()) {
            cluster.setEndpoint("https://" + containerName + ":" + API_PORT);
            EndpointInfo endpoint = info.getEndpoint(API_PORT);
            cluster.setInternalEndpoint(endpoint != null
                    ? "https://" + endpoint.host() + ":" + endpoint.port()
                    : cluster.getEndpoint());
            cluster.setPublicIp(endpoint != null ? ipOf(endpoint.host()) : "127.0.0.1");
        } else {
            cluster.setEndpoint("https://localhost:" + cluster.getApiHostPort());
            cluster.setInternalEndpoint(cluster.getEndpoint());
            cluster.setPublicIp("127.0.0.1");
        }
    }

    private int claim(ContainerInfo info, int containerPort, int recorded) {
        Integer published = info.publishedHostPorts().get(containerPort);
        int port = published != null ? published : recorded;
        if (port > 0) {
            portAllocator.reserveUnprobed(port);
        }
        return port;
    }

    private void oc(String containerId, String... command) {
        ExecResult result = lifecycleManager.execInContainer(containerId, command);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(String.join(" ", command[0], command[1], command[2]) + " failed: "
                    + result.output().strip());
        }
    }

    /** The resource group of the master subnet's virtual network, else the cluster object's own group. */
    private static String networkResourceGroup(OpenShiftCluster cluster) {
        Object subnet = cluster.profile("masterProfile").get("subnetId");
        if (subnet instanceof String id) {
            String[] parts = id.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("resourceGroups".equalsIgnoreCase(parts[i])) {
                    return parts[i + 1];
                }
            }
        }
        return cluster.getResourceGroup();
    }

    private static String lastSegment(String id) {
        if (id == null) {
            return "";
        }
        return id.substring(id.lastIndexOf('/') + 1);
    }

    private static String ipOf(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (UnknownHostException e) {
            LOG.debugv("Cannot resolve {0}: {1}", host, e.getMessage());
            return host;
        }
    }

    private static boolean apiAnswers(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            if (conn instanceof HttpsURLConnection https) {
                trustAll(https);
            }
            int code = conn.getResponseCode();
            return code == 200 || code == 401 || code == 403;
        } catch (Exception e) {
            LOG.debugv("API at {0} not answering: {1}", url, e.toString());
            return false;
        }
    }

    @SuppressWarnings("java:S4830")
    private static void trustAll(HttpsURLConnection conn) throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());
        conn.setSSLSocketFactory(context.getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);
    }
}
