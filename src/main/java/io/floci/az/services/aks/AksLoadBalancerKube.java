package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The load balancer controller's view of a cluster, through {@code kubectl} in the server container: one read of every
 * Service, Node and EndpointSlice per pass, JSON merge patches of a Service and of its {@code status}, and core/v1
 * Events from {@code service-controller}, as a cloud controller reports them.
 */
final class AksLoadBalancerKube implements AksLoadBalancerController.Kube {

    private static final Logger LOG = Logger.getLogger(AksLoadBalancerKube.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AksClusterManager manager;
    private final Clock clock;

    AksLoadBalancerKube(AksClusterManager manager, Clock clock) {
        this.manager = manager;
        this.clock = clock;
    }

    @Override
    public Optional<Snapshot> snapshot(ManagedCluster cluster) {
        try {
            JsonNode items = MAPPER.readTree(manager.kubectl(cluster, "get", "services,nodes,endpointslices", "-A",
                    "-o", "json")).path("items");
            List<JsonNode> services = new ArrayList<>();
            List<JsonNode> nodes = new ArrayList<>();
            List<JsonNode> slices = new ArrayList<>();
            for (JsonNode item : items) {
                switch (item.path("kind").asText()) {
                    case "Service" -> services.add(item);
                    case "Node" -> nodes.add(item);
                    case "EndpointSlice" -> slices.add(item);
                    default -> {
                    }
                }
            }
            return Optional.of(new Snapshot(services, nodes, slices));
        } catch (Exception e) {
            LOG.debugv("AKS cluster {0}: could not read Services: {1}", cluster.getName(), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean patchService(ManagedCluster cluster, String namespace, String name, String mergePatch) {
        return patch(cluster, namespace, name, mergePatch, false);
    }

    @Override
    public boolean patchServiceStatus(ManagedCluster cluster, String namespace, String name, String mergePatch) {
        return patch(cluster, namespace, name, mergePatch, true);
    }

    private boolean patch(ManagedCluster cluster, String namespace, String name, String mergePatch, boolean status) {
        List<String> args = new ArrayList<>(List.of("patch", "service", name, "-n", namespace, "--type=merge",
                "-p", mergePatch));
        if (status) {
            args.add("--subresource=status");
        }
        try {
            manager.kubectl(cluster, args.toArray(String[]::new));
            return true;
        } catch (RuntimeException e) {
            LOG.debugv("AKS cluster {0}: patch of Service {1}/{2} refused: {3}", cluster.getName(), namespace, name,
                    e.getMessage());
            return false;
        }
    }

    @Override
    public void event(ManagedCluster cluster, AksLoadBalancerSpec service, String type, String reason,
                      String message) {
        String now = clock.instant().toString();
        Map<String, Object> involved = new LinkedHashMap<>();
        involved.put("apiVersion", "v1");
        involved.put("kind", "Service");
        involved.put("name", service.name());
        involved.put("namespace", service.namespace());
        involved.put("uid", service.uid());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("apiVersion", "v1");
        event.put("kind", "Event");
        event.put("metadata", Map.of("generateName", service.name() + ".", "namespace", service.namespace()));
        event.put("involvedObject", involved);
        event.put("reason", reason);
        event.put("message", message);
        event.put("type", type);
        event.put("source", Map.of("component", "service-controller"));
        event.put("reportingComponent", "service-controller");
        event.put("firstTimestamp", now);
        event.put("lastTimestamp", now);
        event.put("count", 1);
        try {
            manager.kubectlCreate(cluster, MAPPER.writeValueAsString(event));
        } catch (Exception e) {
            LOG.debugv("AKS cluster {0}: event {1} not recorded: {2}", cluster.getName(), reason, e.getMessage());
        }
    }
}
