package io.floci.az.services.aks;

import com.github.dockerjava.api.model.Container;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.ContainerStorageHelper;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the appliance that stands in for an Azure load balancer frontend: a small container from the cluster's own k3s
 * image (already pulled; it ships {@code /bin/aux/iptables}) that holds the load balancer's address and DNATs each
 * Service port to the backend nodes' NodePorts, picked at random, masquerading so replies come back through it.
 *
 * <p>The appliance sits on the cluster network; a public one also holds its public address on the shared network
 * {@code floci-aks-lb-public} (created on {@code load-balancer.public-cidr} with the first public load balancer), and
 * an internal one answers at the address the cluster network gives it. Every TCP port is published on a free host
 * port ({@code load-balancer.host-ports}). It runs privileged, like the cluster's own containers, to set
 * {@code net.ipv4.ip_forward} and its NAT rules. A running appliance is kept while its spec label matches what it
 * should do; otherwise it is replaced.
 */
@ApplicationScoped
public class AksLoadBalancerAppliances implements AksLoadBalancerController.Appliances {

    private static final Logger LOG = Logger.getLogger(AksLoadBalancerAppliances.class);
    static final String SERVICE_LABEL = "io.floci.aks.load-balancer";
    static final String SPEC_LABEL = "io.floci.aks.load-balancer-spec";
    private static final String IPTABLES = "/bin/aux/iptables";

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final AksClusterManager clusterManager;
    private final EmulatorConfig config;

    @Inject
    public AksLoadBalancerAppliances(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                                     AksClusterManager clusterManager, EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.clusterManager = clusterManager;
        this.config = config;
    }

    String publicNetwork() {
        return ContainerStorageHelper.dockerName(config, "aks-lb-public");
    }

    @Override
    public String nameFor(ManagedCluster cluster, String serviceUid) {
        return clusterManager.containerName(cluster) + "-lb-" + serviceUid.replace("-", "").substring(0, 12);
    }

    @Override
    public Running ensure(ManagedCluster cluster, Spec spec) {
        String script = script(spec);
        String digest = digest(String.join("\n", cluster.getServerImage(), String.valueOf(spec.publicAddress()),
                String.valueOf(spec.internal()), config.services().aks().loadBalancer().hostPorts(), ports(spec), script));
        Optional<Container> existing = lifecycleManager.findByName(spec.name());
        if (existing.isPresent() && digest.equals(labels(existing.get()).get(SPEC_LABEL))
                && lifecycleManager.isContainerRunning(existing.get().getId())) {
            return running(cluster, spec, existing.get().getId(),
                    lifecycleManager.adopt(existing.get().getId(), tcpPorts(spec)));
        }
        lifecycleManager.removeIfExists(spec.name());
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.resourceIdentityLabels("aks",
                cluster.getName(), cluster.getSubscriptionId(), cluster.getResourceGroup(), cluster.getLocation()));
        labels.put(AksClusterManager.ROLE_LABEL, "load-balancer");
        labels.put(SERVICE_LABEL, spec.serviceUid());
        labels.put(SPEC_LABEL, digest);
        ContainerBuilder.Builder builder = containerBuilder.newContainer(cluster.getServerImage())
                .withName(spec.name())
                .withLabels(labels)
                .withEntrypoint(List.of("sh", "-c", script))
                .withCmd(List.of())
                .withPrimaryNetwork(AksClusterManager.networkName(cluster))
                .withPrivileged(true)
                .withLogRotation();
        if (!"none".equals(config.services().aks().loadBalancer().hostPorts())) {
            tcpPorts(spec).forEach(port -> builder.withPortBinding(port, 0));
        }
        ContainerSpec containerSpec = builder.build();
        String containerId = lifecycleManager.create(containerSpec);
        try {
            if (!spec.internal()) {
                lifecycleManager.ensureNetwork(publicNetwork(), config.services().aks().loadBalancer().publicCidr(),
                        Map.of(SERVICE_LABEL, "public"));
                lifecycleManager.connectToNetwork(containerId, publicNetwork(), spec.publicAddress());
            }
            ContainerLifecycleManager.ContainerInfo info = lifecycleManager.startCreated(containerId, containerSpec);
            Running running = running(cluster, spec, containerId, info);
            LOG.infov("Started load balancer appliance {0} for Service {1} of AKS cluster {2} at {3}, host ports {4}",
                    spec.name(), spec.serviceUid(), cluster.getName(), running.address(), running.hostPorts());
            return running;
        } catch (RuntimeException e) {
            lifecycleManager.removeIfExists(spec.name());
            throw e;
        }
    }

    private Running running(ManagedCluster cluster, Spec spec, String containerId,
                            ContainerLifecycleManager.ContainerInfo info) {
        Map<String, Integer> hostPorts = new LinkedHashMap<>();
        for (AksLoadBalancerSpec.Port port : spec.ports()) {
            if ("TCP".equals(port.protocol())) {
                info.publishedHostPort(port.port()).ifPresent(hostPort -> hostPorts.put(port.key(), hostPort));
            }
        }
        String address = spec.internal()
                ? lifecycleManager.addressOnNetwork(containerId, AksClusterManager.networkName(cluster)).orElse(null)
                : spec.publicAddress();
        return new Running(address, hostPorts);
    }

    @Override
    public void remove(ManagedCluster cluster, String name) {
        lifecycleManager.findByName(name).ifPresent(container -> {
            lifecycleManager.stopAndRemove(container.getId(), null);
            LOG.infov("Removed load balancer appliance {0} of AKS cluster {1}", name, cluster.getName());
        });
    }

    /** Removes the shared public network once no load balancer uses it; Docker refuses while one does. */
    void removePublicNetworkIfUnused(List<ManagedCluster> clusters) {
        boolean used = clusters.stream().flatMap(cluster -> cluster.loadBalancers().stream())
                .anyMatch(record -> !record.isInternal());
        if (!used) {
            lifecycleManager.removeNetwork(publicNetwork());
        }
    }

    /** The appliance's {@code sh -c} script: forwarding on, the NAT rules, then sleep. Every value is an address or a number. */
    static String script(Spec spec) {
        StringBuilder script = new StringBuilder("set -e\nsysctl -w net.ipv4.ip_forward=1 >/dev/null\nIPT="
                + IPTABLES + "\n");
        List<String> backends = spec.backends();
        for (AksLoadBalancerSpec.Port port : spec.ports()) {
            String protocol = port.protocol().toLowerCase(Locale.ROOT);
            List<String> sources = spec.sourceRanges().isEmpty() ? Collections.singletonList(null) : spec.sourceRanges();
            for (String source : sources) {
                for (int i = 0; i < backends.size(); i++) {
                    script.append("$IPT -t nat -A PREROUTING -p ").append(protocol);
                    if (source != null) {
                        script.append(" -s ").append(source);
                    }
                    script.append(" --dport ").append(port.port());
                    if (i < backends.size() - 1) {
                        script.append(" -m statistic --mode random --probability ")
                                .append(String.format(Locale.ROOT, "%.10f", 1.0 / (backends.size() - i)));
                    }
                    script.append(" -j DNAT --to-destination ").append(backends.get(i)).append(':')
                            .append(port.nodePort()).append('\n');
                }
            }
            for (String backend : backends) {
                script.append("$IPT -t nat -A POSTROUTING -p ").append(protocol).append(" -d ").append(backend)
                        .append(" --dport ").append(port.nodePort()).append(" -j MASQUERADE\n");
            }
        }
        return script.append("exec sleep infinity\n").toString();
    }

    private static List<Integer> tcpPorts(Spec spec) {
        return spec.ports().stream().filter(port -> "TCP".equals(port.protocol()))
                .map(AksLoadBalancerSpec.Port::port).distinct().toList();
    }

    private static String ports(Spec spec) {
        return String.join(",", spec.ports().stream().map(AksLoadBalancerSpec.Port::key).toList());
    }

    private static Map<String, String> labels(Container container) {
        return container.getLabels() == null ? Map.of() : container.getLabels();
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
