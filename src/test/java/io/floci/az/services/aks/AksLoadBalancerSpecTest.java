package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Which Services AKS's cloud controller takes and what it builds for them ({@code load-balancer-standard.md},
 * {@code internal-lb.md}; kubernetes/cloud-provider {@code wantsLoadBalancer}), the public addresses, and k3s's
 * servicelb turned off for emulated load balancers.
 */
class AksLoadBalancerSpecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CIDR = "198.51.100.0/24";

    private static AksLoadBalancerSpec spec(String json) throws Exception {
        return AksLoadBalancerSpec.from(MAPPER.readTree(json));
    }

    private static String service(String annotations, String spec) {
        return "{\"metadata\": {\"uid\": \"u1\", \"namespace\": \"default\", \"name\": \"web\", \"annotations\": {"
                + annotations + "}}, \"spec\": {\"type\": \"LoadBalancer\", \"ports\": [{\"port\": 80, "
                + "\"nodePort\": 31000, \"protocol\": \"TCP\"}]" + spec + "}}";
    }

    @Test
    void aLoadBalancerWithoutAClassIsPublicByDefault() throws Exception {
        AksLoadBalancerSpec spec = spec(service("", ""));
        assertTrue(spec.wantsLoadBalancer());
        assertFalse(spec.internal());
        assertNull(spec.error());
        assertEquals(List.of(new AksLoadBalancerSpec.Port("", "TCP", 80, 31000)), spec.ports());
    }

    @Test
    void anotherClassOrTypeIsNotTaken() throws Exception {
        assertFalse(spec(service("", ", \"loadBalancerClass\": \"example.com/lb\"")).wantsLoadBalancer());
        assertFalse(spec("{\"metadata\": {\"uid\": \"u\"}, \"spec\": {\"type\": \"ClusterIP\"}}").wantsLoadBalancer());
    }

    @Test
    void theInternalAnnotationMakesItInternal() throws Exception {
        assertTrue(spec(service("\"service.beta.kubernetes.io/azure-load-balancer-internal\": \"true\"", ""))
                .internal());
    }

    @Test
    void theIpv4AnnotationWinsOverLoadBalancerIp() throws Exception {
        AksLoadBalancerSpec spec = spec(service("\"service.beta.kubernetes.io/azure-load-balancer-ipv4\": "
                + "\"198.51.100.20\"", ", \"loadBalancerIP\": \"198.51.100.30\""));
        assertEquals("198.51.100.20", spec.requestedIp());
        assertEquals("198.51.100.30", spec(service("", ", \"loadBalancerIP\": \"198.51.100.30\"")).requestedIp());
    }

    @Test
    void aFixedInternalAddressOrABadAddressStaysPending() throws Exception {
        assertTrue(spec(service("\"service.beta.kubernetes.io/azure-load-balancer-internal\": \"true\", "
                + "\"service.beta.kubernetes.io/azure-load-balancer-ipv4\": \"10.224.0.7\"", "")).error() != null);
        assertTrue(spec(service("\"service.beta.kubernetes.io/azure-load-balancer-ipv4\": \"not-an-ip\"", ""))
                .error() != null);
    }

    @Test
    void publicAddressesComeFromTheRangeUniquelyAndAskedOnesMustFit() {
        assertEquals("198.51.100.2", AksLoadBalancerAddresses.allocate(CIDR, null, Set.of()).address());
        assertEquals("198.51.100.3",
                AksLoadBalancerAddresses.allocate(CIDR, null, Set.of("198.51.100.2")).address());
        assertEquals("198.51.100.20",
                AksLoadBalancerAddresses.allocate(CIDR, "198.51.100.20", Set.of()).address());
        assertTrue(AksLoadBalancerAddresses.allocate(CIDR, "203.0.113.5", Set.of()).error() != null);
        assertTrue(AksLoadBalancerAddresses.allocate(CIDR, "198.51.100.20", Set.of("198.51.100.20")).error() != null);
    }

    @Test
    void theHostPortsAnnotationListsEachPort() {
        assertEquals("TCP/443=127.0.0.1:41235,TCP/80=127.0.0.1:41234",
                AksLoadBalancerAddresses.hostPortsAnnotation(Map.of("TCP/80", 41234, "TCP/443", 41235)));
    }

    @Test
    void emulatedAndOffTurnServicelbOffAndARestoredClusterKeepsIt() {
        AksClusterRuntime runtime = new AksClusterRuntime(mock(AksClusterManager.class),
                new AksVersions("rancher/k3s", Optional.empty()), 5);
        ManagedCluster cluster = new ManagedCluster();
        cluster.setInstanceId("abc");
        assertFalse(runtime.serverArgs(cluster).contains("--disable=servicelb"));
        cluster.setLoadBalancerMode("servicelb");
        assertFalse(runtime.serverArgs(cluster).contains("--disable=servicelb"));
        cluster.setLoadBalancerMode("emulated");
        assertTrue(runtime.serverArgs(cluster).contains("--disable=servicelb"));
        cluster.setLoadBalancerMode("off");
        assertTrue(runtime.serverArgs(cluster).contains("--disable=servicelb"));
        assertFalse(runtime.serverArgs(cluster, true).contains("--disable=servicelb"));
    }
}
