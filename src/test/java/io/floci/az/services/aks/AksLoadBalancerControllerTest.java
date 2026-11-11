package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aks.AksModels.LoadBalancerRecord;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The controller against a fake cluster API, fake appliances and in-memory records. */
class AksLoadBalancerControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> calls = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<String, LoadBalancerRecord> records = new HashMap<>();
    private final Map<String, AksLoadBalancerController.Appliances.Spec> appliances = new HashMap<>();
    private List<JsonNode> services = new ArrayList<>();

    private final AksLoadBalancerController.Kube kube = new AksLoadBalancerController.Kube() {
        @Override
        public Optional<Snapshot> snapshot(ManagedCluster cluster) {
            try {
                JsonNode node = MAPPER.readTree("{\"metadata\": {\"name\": \"aks-nodepool1-1-vmss000000\"}, "
                        + "\"status\": {\"addresses\": [{\"type\": \"InternalIP\", \"address\": \"172.20.0.3\"}], "
                        + "\"conditions\": [{\"type\": \"Ready\", \"status\": \"True\"}]}}");
                return Optional.of(new Snapshot(services, List.of(node), List.of()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean patchService(ManagedCluster cluster, String namespace, String name, String mergePatch) {
            calls.add("patch " + mergePatch);
            return true;
        }

        @Override
        public boolean patchServiceStatus(ManagedCluster cluster, String namespace, String name, String mergePatch) {
            calls.add("status " + mergePatch);
            return true;
        }

        @Override
        public void event(ManagedCluster cluster, AksLoadBalancerSpec service, String type, String reason,
                          String message) {
            events.add(reason);
        }
    };

    private final AksLoadBalancerController.Appliances fakeAppliances = new AksLoadBalancerController.Appliances() {
        @Override
        public String nameFor(ManagedCluster cluster, String serviceUid) {
            return "lb-" + serviceUid;
        }

        @Override
        public Running ensure(ManagedCluster cluster, Spec spec) {
            appliances.put(spec.name(), spec);
            return new Running(spec.internal() ? "172.20.0.9" : spec.publicAddress(), Map.of("TCP/80", 41234));
        }

        @Override
        public void remove(ManagedCluster cluster, String name) {
            appliances.remove(name);
        }
    };

    private final AksLoadBalancerController.Records memory = new AksLoadBalancerController.Records() {
        @Override
        public List<LoadBalancerRecord> list(ManagedCluster cluster) {
            return List.copyOf(records.values());
        }

        @Override
        public void save(ManagedCluster cluster, LoadBalancerRecord record) {
            records.put(record.getServiceUid(), record);
        }

        @Override
        public void release(ManagedCluster cluster, String serviceUid) {
            records.remove(serviceUid);
        }

        @Override
        public Collection<String> publicAddresses() {
            return records.values().stream().filter(r -> !r.isInternal()).map(LoadBalancerRecord::getAddress).toList();
        }
    };

    private final AksLoadBalancerController controller = new AksLoadBalancerController(kube, fakeAppliances, memory,
            Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC), () -> 0, "198.51.100.0/24");

    private static JsonNode service(String uid, String finalizers, String extra) throws Exception {
        return MAPPER.readTree("{\"metadata\": {\"uid\": \"" + uid + "\", \"namespace\": \"default\", \"name\": \"web-"
                + uid + "\", \"resourceVersion\": \"1\", \"finalizers\": [" + finalizers + "]" + extra + "}, "
                + "\"spec\": {\"type\": \"LoadBalancer\", \"ports\": [{\"port\": 80, \"nodePort\": 31000, "
                + "\"protocol\": \"TCP\"}]}}");
    }

    private void pass() {
        controller.reconcile(new ManagedCluster(), Set.of("aks-nodepool1-1-vmss000000"));
    }

    @Test
    void theFinalizerComesBeforeTheStatus() throws Exception {
        services = List.of(service("u1", "", ""));
        pass();
        assertTrue(calls.getFirst().startsWith("patch "), calls.toString());
        assertTrue(calls.getFirst().contains("service.kubernetes.io/load-balancer-cleanup"));
        assertTrue(calls.stream().anyMatch(c -> c.equals("status {\"status\":{\"loadBalancer\":{\"ingress\":"
                + "[{\"ip\":\"198.51.100.2\"}]}}}")), calls.toString());
        assertEquals(List.of("EnsuringLoadBalancer", "EnsuredLoadBalancer"), events);
        assertEquals(List.of("172.20.0.3"), appliances.get("lb-u1").backends());
    }

    @Test
    void addressesStayAcrossPassesAndAnotherServiceGetsTheNext() throws Exception {
        services = List.of(service("u1", "\"service.kubernetes.io/load-balancer-cleanup\"", ""));
        pass();
        services = List.of(service("u1", "\"service.kubernetes.io/load-balancer-cleanup\"", ""),
                service("u2", "\"service.kubernetes.io/load-balancer-cleanup\"", ""));
        pass();
        assertEquals("198.51.100.2", records.get("u1").getAddress());
        assertEquals("198.51.100.3", records.get("u2").getAddress());
    }

    @Test
    void anInvalidSpecStaysPendingWithOneFailureEvent() throws Exception {
        services = List.of(MAPPER.readTree("{\"metadata\": {\"uid\": \"u3\", \"namespace\": \"default\", "
                + "\"name\": \"bad\", \"finalizers\": [\"service.kubernetes.io/load-balancer-cleanup\"], "
                + "\"annotations\": {\"service.beta.kubernetes.io/azure-load-balancer-ipv4\": \"203.0.113.5\"}}, "
                + "\"spec\": {\"type\": \"LoadBalancer\", \"ports\": [{\"port\": 80, \"nodePort\": 31000}]}}"));
        pass();
        pass();
        assertEquals(List.of("SyncLoadBalancerFailed"), events);
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("status ")));
    }

    @Test
    void deletingTheServiceRemovesTheApplianceThenTheFinalizer() throws Exception {
        services = List.of(service("u1", "\"service.kubernetes.io/load-balancer-cleanup\"", ""));
        pass();
        services = List.of(service("u1", "\"service.kubernetes.io/load-balancer-cleanup\"",
                ", \"deletionTimestamp\": \"2026-11-11T12:00:00Z\""));
        calls.clear();
        events.clear();
        pass();
        assertTrue(appliances.isEmpty());
        assertTrue(records.isEmpty());
        assertTrue(calls.getLast().contains("\"finalizers\":[]"), calls.toString());
        assertEquals(List.of("DeletingLoadBalancer", "DeletedLoadBalancer"), events);
    }

    @Test
    void aServiceThatIsNoLongerALoadBalancerLosesItsStatus() throws Exception {
        services = List.of(service("u1", "\"service.kubernetes.io/load-balancer-cleanup\"", ""));
        pass();
        services = List.of(MAPPER.readTree("{\"metadata\": {\"uid\": \"u1\", \"namespace\": \"default\", "
                + "\"name\": \"web-u1\", \"finalizers\": [\"service.kubernetes.io/load-balancer-cleanup\"]}, "
                + "\"spec\": {\"type\": \"ClusterIP\"}, \"status\": {\"loadBalancer\": {\"ingress\": "
                + "[{\"ip\": \"198.51.100.2\"}]}}}"));
        calls.clear();
        pass();
        assertTrue(calls.contains("status {\"status\":{\"loadBalancer\":{\"ingress\":null}}}"), calls.toString());
        assertTrue(appliances.isEmpty());
    }
}
