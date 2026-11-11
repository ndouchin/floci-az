package io.floci.az.services.aks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.az.services.aks.AksModels.LoadBalancerRecord;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * floci-az's stand-in for AKS's cloud controller manager, for Services of type LoadBalancer. Each pass reads the
 * cluster's Services, Nodes and EndpointSlices and, as kubernetes/cloud-provider's service controller does: adds the
 * {@code service.kubernetes.io/load-balancer-cleanup} finalizer first, gives the load balancer its address and an
 * appliance container holding it, writes {@code status.loadBalancer.ingress} with that address, and reports
 * {@code EnsuringLoadBalancer}, {@code EnsuredLoadBalancer}, {@code SyncLoadBalancerFailed},
 * {@code UpdatedLoadBalancer}, {@code UnAvailableLoadBalancer}, {@code DeletingLoadBalancer} and
 * {@code DeletedLoadBalancer} events with the upstream messages. When a Service is deleted or stops being a
 * LoadBalancer, it removes the appliance, releases the address and drops the finalizer. A pass that changes nothing
 * writes nothing.
 *
 * <p>Backends are the Ready nodes floci-az runs as the cluster's agents, without
 * {@code node.kubernetes.io/exclude-from-external-load-balancers}; with {@code externalTrafficPolicy: Local}, only
 * those with a ready endpoint of the Service.
 */
final class AksLoadBalancerController {

    /** What the controller reads from and writes to a cluster's Kubernetes API. */
    interface Kube {

        record Snapshot(List<JsonNode> services, List<JsonNode> nodes, List<JsonNode> endpointSlices) {
        }

        Optional<Snapshot> snapshot(ManagedCluster cluster);

        boolean patchService(ManagedCluster cluster, String namespace, String name, String mergePatch);

        boolean patchServiceStatus(ManagedCluster cluster, String namespace, String name, String mergePatch);

        void event(ManagedCluster cluster, AksLoadBalancerSpec service, String type, String reason, String message);
    }

    /** The containers that hold load balancer addresses and forward their ports to the nodes. */
    interface Appliances {

        /** One appliance: its address (null for an internal one), what it forwards and to which nodes. */
        record Spec(String name, String serviceUid, boolean internal, String publicAddress,
                    List<AksLoadBalancerSpec.Port> ports, List<String> backends, List<String> sourceRanges) {
        }

        /** A running appliance: the address it holds, and its host port per {@code PROTOCOL/port}. */
        record Running(String address, Map<String, Integer> hostPorts) {
        }

        String nameFor(ManagedCluster cluster, String serviceUid);

        Running ensure(ManagedCluster cluster, Spec spec);

        void remove(ManagedCluster cluster, String name);
    }

    /** The stored load balancers. */
    interface Records {

        List<LoadBalancerRecord> list(ManagedCluster cluster);

        void save(ManagedCluster cluster, LoadBalancerRecord record);

        void release(ManagedCluster cluster, String serviceUid);

        /** Every public address a load balancer of any cluster holds. */
        Collection<String> publicAddresses();
    }

    private static final Logger LOG = Logger.getLogger(AksLoadBalancerController.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String EXCLUDE_LABEL = "node.kubernetes.io/exclude-from-external-load-balancers";
    private static final String NORMAL = "Normal";
    private static final String WARNING = "Warning";

    private final Kube kube;
    private final Appliances appliances;
    private final Records records;
    private final Clock clock;
    private final IntSupplier provisioningDelaySeconds;
    private final String publicCidr;
    private final Map<String, String> reportedFailures = new ConcurrentHashMap<>();

    AksLoadBalancerController(Kube kube, Appliances appliances, Records records, Clock clock,
                              IntSupplier provisioningDelaySeconds, String publicCidr) {
        this.kube = kube;
        this.appliances = appliances;
        this.records = records;
        this.clock = clock;
        this.provisioningDelaySeconds = provisioningDelaySeconds;
        this.publicCidr = publicCidr;
    }

    /**
     * One pass over the cluster.
     *
     * @param agentNodes the node names floci-az runs as the cluster's k3s agents
     */
    void reconcile(ManagedCluster cluster, Set<String> agentNodes) {
        Optional<Kube.Snapshot> read = kube.snapshot(cluster);
        if (read.isEmpty()) {
            return;
        }
        Kube.Snapshot snapshot = read.get();
        Map<String, String> backendNodes = readyBackendNodes(snapshot.nodes(), agentNodes);
        Map<String, LoadBalancerRecord> stored = new LinkedHashMap<>();
        records.list(cluster).forEach(record -> stored.put(record.getServiceUid(), record));
        Set<String> present = new HashSet<>();
        for (JsonNode service : snapshot.services()) {
            AksLoadBalancerSpec spec = AksLoadBalancerSpec.from(service);
            present.add(spec.uid());
            LoadBalancerRecord record = stored.get(spec.uid());
            try {
                if (!spec.wantsLoadBalancer() || spec.deleting()) {
                    if (record != null || spec.hasCleanupFinalizer()) {
                        cleanUp(cluster, spec, record);
                    }
                } else {
                    ensure(cluster, spec, record, backendNodes, snapshot.endpointSlices());
                }
            } catch (RuntimeException e) {
                LOG.debugv(e, "Load balancer of Service {0}/{1} in AKS cluster {2} not synced", spec.namespace(),
                        spec.name(), cluster.getName());
                failed(cluster, spec, String.valueOf(e.getMessage()));
            }
        }
        for (LoadBalancerRecord record : stored.values()) {
            if (!present.contains(record.getServiceUid())) {
                LOG.infov("Removing the load balancer of Service {0}/{1} in AKS cluster {2}: the Service is gone",
                        record.getNamespace(), record.getName(), cluster.getName());
                appliances.remove(cluster, applianceName(cluster, record));
                records.release(cluster, record.getServiceUid());
                reportedFailures.remove(record.getServiceUid());
            }
        }
    }

    private void ensure(ManagedCluster cluster, AksLoadBalancerSpec spec, LoadBalancerRecord stored,
                        Map<String, String> backendNodes, List<JsonNode> endpointSlices) {
        if (!spec.hasCleanupFinalizer() && !patchFinalizers(cluster, spec, true)) {
            return;
        }
        if (spec.error() != null) {
            failed(cluster, spec, spec.error());
            return;
        }
        LoadBalancerRecord record = stored;
        boolean changed = record == null || record.isInternal() != spec.internal()
                || (!spec.internal() && spec.requestedIp() != null && !spec.requestedIp().equals(record.getAddress()));
        if (changed) {
            if (record != null) {
                appliances.remove(cluster, applianceName(cluster, record));
            }
            record = new LoadBalancerRecord();
            record.setServiceUid(spec.uid());
            record.setNamespace(spec.namespace());
            record.setName(spec.name());
            record.setInternal(spec.internal());
            record.setFirstSeen(clock.instant().toString());
            if (!spec.internal()) {
                Set<String> used = new HashSet<>(records.publicAddresses());
                if (stored != null && stored.getAddress() != null) {
                    used.remove(stored.getAddress());
                }
                AksLoadBalancerAddresses.Allocation allocation =
                        AksLoadBalancerAddresses.allocate(publicCidr, spec.requestedIp(), used);
                if (allocation.error() != null) {
                    failed(cluster, spec, allocation.error());
                    return;
                }
                record.setAddress(allocation.address());
            }
            records.save(cluster, record);
            kube.event(cluster, spec, NORMAL, "EnsuringLoadBalancer", "Ensuring load balancer");
        }
        List<String> backends = spec.localTrafficPolicy()
                ? localBackends(spec, backendNodes, endpointSlices)
                : List.copyOf(backendNodes.values());
        String name = applianceName(cluster, record);
        Appliances.Running running = appliances.ensure(cluster, new Appliances.Spec(name, spec.uid(), spec.internal(),
                spec.internal() ? null : record.getAddress(), forwardedPorts(spec), backends, spec.sourceRanges()));
        boolean backendsChanged = !backends.equals(record.getBackends());
        if (record.isEnsured() && backendsChanged) {
            if (backends.isEmpty()) {
                kube.event(cluster, spec, WARNING, "UnAvailableLoadBalancer",
                        "There are no available nodes for LoadBalancer");
            } else {
                kube.event(cluster, spec, NORMAL, "UpdatedLoadBalancer", "Updated load balancer with new hosts");
            }
        }
        if (changed || backendsChanged || !running.hostPorts().equals(record.getHostPorts())
                || !name.equals(record.getApplianceName()) || !Objects.equals(running.address(), record.getAddress())) {
            record.setApplianceName(name);
            record.setBackends(backends);
            record.setHostPorts(running.hostPorts());
            record.setAddress(running.address());
            records.save(cluster, record);
        }
        String annotation = running.hostPorts().isEmpty() ? null
                : AksLoadBalancerAddresses.hostPortsAnnotation(running.hostPorts());
        if (!Objects.equals(annotation, spec.hostPortsAnnotation())) {
            kube.patchService(cluster, spec.namespace(), spec.name(), hostPortsPatch(annotation));
        }
        Instant provisionedAt = Instant.parse(record.getFirstSeen()).plusSeconds(provisioningDelaySeconds.getAsInt());
        if (clock.instant().isBefore(provisionedAt) || record.getAddress() == null) {
            return;
        }
        List<Map<String, String>> ingress = AksLoadBalancerAddresses.ingress(record.getAddress());
        if (!record.isEnsured()) {
            kube.event(cluster, spec, NORMAL, "EnsuredLoadBalancer", "Ensured load balancer");
        }
        if (!sameIngress(spec.statusIngress(), ingress)
                && !kube.patchServiceStatus(cluster, spec.namespace(), spec.name(), statusPatch(ingress))) {
            return;
        }
        reportedFailures.remove(spec.uid());
        if (!record.isEnsured()) {
            record.setEnsured(true);
            records.save(cluster, record);
            LOG.infov("Load balancer of Service {0}/{1} in AKS cluster {2}: {3}", spec.namespace(), spec.name(),
                    cluster.getName(), record.getAddress());
        }
    }

    /** Removes what the load balancer holds; the finalizer goes last, so a Service is never gone while its appliance runs. */
    private void cleanUp(ManagedCluster cluster, AksLoadBalancerSpec spec, LoadBalancerRecord record) {
        if (record != null) {
            kube.event(cluster, spec, NORMAL, "DeletingLoadBalancer", "Deleting load balancer");
            appliances.remove(cluster, applianceName(cluster, record));
            records.release(cluster, spec.uid());
        }
        if (!spec.deleting()) {
            if (spec.statusIngress().isArray() && !spec.statusIngress().isEmpty()) {
                kube.patchServiceStatus(cluster, spec.namespace(), spec.name(),
                        "{\"status\":{\"loadBalancer\":{\"ingress\":null}}}");
            }
            if (spec.hostPortsAnnotation() != null) {
                kube.patchService(cluster, spec.namespace(), spec.name(), hostPortsPatch(null));
            }
        }
        if (spec.hasCleanupFinalizer() && !patchFinalizers(cluster, spec, false)) {
            return;
        }
        reportedFailures.remove(spec.uid());
        kube.event(cluster, spec, NORMAL, "DeletedLoadBalancer", "Deleted load balancer");
    }

    private void failed(ManagedCluster cluster, AksLoadBalancerSpec spec, String reason) {
        String message = "Error syncing load balancer: failed to ensure load balancer: " + reason;
        if (!message.equals(reportedFailures.put(spec.uid(), message))) {
            LOG.warnv("Service {0}/{1} in AKS cluster {2}: {3}", spec.namespace(), spec.name(), cluster.getName(),
                    message);
            kube.event(cluster, spec, WARNING, "SyncLoadBalancerFailed", message);
        }
    }

    /**
     * Adds or removes the cleanup finalizer, sending the whole list with the Service's {@code resourceVersion}, so a
     * finalizer another controller added since the read is never lost: the API server refuses the patch and the next
     * pass retries.
     */
    private boolean patchFinalizers(ManagedCluster cluster, AksLoadBalancerSpec spec, boolean present) {
        List<String> finalizers = new ArrayList<>(spec.finalizers());
        finalizers.remove(AksLoadBalancerSpec.CLEANUP_FINALIZER);
        if (present) {
            finalizers.add(AksLoadBalancerSpec.CLEANUP_FINALIZER);
        }
        ObjectNode patch = JSON.createObjectNode();
        ObjectNode metadata = patch.putObject("metadata");
        if (spec.resourceVersion() != null) {
            metadata.put("resourceVersion", spec.resourceVersion());
        }
        ArrayNode list = metadata.putArray("finalizers");
        finalizers.forEach(list::add);
        return kube.patchService(cluster, spec.namespace(), spec.name(), write(patch));
    }

    private static String hostPortsPatch(String annotation) {
        ObjectNode patch = JSON.createObjectNode();
        ObjectNode annotations = patch.putObject("metadata").putObject("annotations");
        if (annotation == null) {
            annotations.putNull(AksLoadBalancerSpec.HOST_PORTS_ANNOTATION);
        } else {
            annotations.put(AksLoadBalancerSpec.HOST_PORTS_ANNOTATION, annotation);
        }
        return write(patch);
    }

    private static String statusPatch(List<Map<String, String>> ingress) {
        ObjectNode patch = JSON.createObjectNode();
        patch.putObject("status").putObject("loadBalancer").set("ingress", JSON.valueToTree(ingress));
        return write(patch);
    }

    /** Whether the status already names {@code desired}; the API server fills {@code ipMode: VIP} itself. */
    static boolean sameIngress(JsonNode current, List<Map<String, String>> desired) {
        if (!current.isArray()) {
            return desired.isEmpty();
        }
        if (current.size() != desired.size()) {
            return false;
        }
        for (int i = 0; i < desired.size(); i++) {
            if (!desired.get(i).get("ip").equals(current.get(i).path("ip").asText(null))
                    || current.get(i).has("hostname")) {
                return false;
            }
        }
        return true;
    }

    /** The ports the appliance can forward: those with a NodePort. */
    private static List<AksLoadBalancerSpec.Port> forwardedPorts(AksLoadBalancerSpec spec) {
        return spec.ports().stream().filter(port -> port.nodePort() > 0).toList();
    }

    private static Map<String, String> readyBackendNodes(List<JsonNode> nodes, Set<String> agentNodes) {
        Map<String, String> backends = new TreeMap<>();
        for (JsonNode node : nodes) {
            String name = node.path("metadata").path("name").asText();
            if (!agentNodes.contains(name) || node.path("metadata").hasNonNull("deletionTimestamp")
                    || node.path("metadata").path("labels").has(EXCLUDE_LABEL) || !isReady(node)) {
                continue;
            }
            String address = name;
            for (JsonNode entry : node.path("status").path("addresses")) {
                if ("InternalIP".equals(entry.path("type").asText())) {
                    address = entry.path("address").asText(name);
                }
            }
            backends.put(name, address);
        }
        return backends;
    }

    private static boolean isReady(JsonNode node) {
        for (JsonNode condition : node.path("status").path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                return "True".equals(condition.path("status").asText());
            }
        }
        return false;
    }

    private static List<String> localBackends(AksLoadBalancerSpec spec, Map<String, String> backendNodes,
                                              List<JsonNode> endpointSlices) {
        Map<String, String> local = new TreeMap<>();
        for (JsonNode slice : endpointSlices) {
            if (!spec.namespace().equals(slice.path("metadata").path("namespace").asText())
                    || !spec.name().equals(slice.path("metadata").path("labels")
                            .path("kubernetes.io/service-name").asText())) {
                continue;
            }
            for (JsonNode endpoint : slice.path("endpoints")) {
                String node = endpoint.path("nodeName").asText(null);
                boolean ready = endpoint.path("conditions").path("ready").asBoolean(true);
                if (ready && node != null && backendNodes.containsKey(node)) {
                    local.put(node, backendNodes.get(node));
                }
            }
        }
        return List.copyOf(local.values());
    }

    private String applianceName(ManagedCluster cluster, LoadBalancerRecord record) {
        return record.getApplianceName() != null ? record.getApplianceName()
                : appliances.nameFor(cluster, record.getServiceUid());
    }

    private static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write a JSON patch", e);
        }
    }
}
