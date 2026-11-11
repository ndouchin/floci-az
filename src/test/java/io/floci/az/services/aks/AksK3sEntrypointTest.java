package io.floci.az.services.aks;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.dns.EmbeddedDnsServer;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.CurrentContainerNetworkResolver;
import io.floci.az.core.docker.DockerHostResolver;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.aks.AksModels.AgentNode;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Without br_netfilter on the Docker host, replies between two pods of one node are bridged past conntrack, so a
 * Service's DNAT is never reversed and cluster DNS times out for pods on CoreDNS's node. k3s cannot modprobe the module
 * from its container, and its network policy controller, whose physdev rules load it as a side effect, is off unless
 * the cluster names a policy engine. Every k3s container therefore makes the host kernel load it before k3s starts.
 */
@DisplayName("AksClusterManager: k3s containers load br_netfilter on the Docker host")
class AksK3sEntrypointTest {

    private static final String IMAGE = "rancher/k3s:v1.36.6-k3s1";
    private static final String SYSCTL = "/proc/sys/net/bridge/bridge-nf-call-iptables";

    private ContainerLifecycleManager lifecycleManager;
    private AksClusterManager manager;
    private ManagedCluster cluster;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.findByName(anyString())).thenReturn(Optional.empty());
        when(lifecycleManager.create(any())).thenReturn("cid");
        when(lifecycleManager.startCreated(anyString(), any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("cid", Map.of()));
        ContainerBuilder containerBuilder = new ContainerBuilder(config, mock(DockerHostResolver.class),
                mock(EmbeddedDnsServer.class), mock(CurrentContainerNetworkResolver.class));
        manager = new AksClusterManager(containerBuilder, lifecycleManager, mock(ContainerDetector.class),
                mock(CurrentContainerNetworkResolver.class), mock(PortAllocator.class), config);
        cluster = new ManagedCluster();
        cluster.setInstanceId("abc");
        cluster.setName("aks1");
        cluster.setClusterToken("token");
    }

    private ContainerSpec created() {
        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(spec.capture());
        return spec.getValue();
    }

    /** The entrypoint runs a shell script that hands the container's command to k3s. */
    private static String script(ContainerSpec spec, List<String> args) {
        List<String> entrypoint = spec.entrypoint();
        assertNotNull(entrypoint, "no entrypoint: the image runs k3s directly");
        assertEquals(List.of("/bin/sh", "-c"), entrypoint.subList(0, 2));
        assertEquals(4, entrypoint.size(), entrypoint.toString());
        assertEquals(args, spec.cmd());
        String script = entrypoint.get(2);
        assertTrue(script.strip().endsWith("exec /bin/k3s \"$@\""), script);
        return script;
    }

    private static void loadsBrNetfilterThenCleansUp(String script) {
        int guard = script.indexOf("[ ! -e " + SYSCTL + " ]");
        int load = script.indexOf("iptables -A FLOCI-BR-NETFILTER -m physdev");
        int remove = script.indexOf("iptables -X FLOCI-BR-NETFILTER");
        int exec = script.indexOf("exec /bin/k3s");
        assertTrue(guard >= 0, "only when the module is missing: " + script);
        assertTrue(guard < load && load < remove && remove < exec,
                "a physdev rule loads the module, its chain goes before k3s detects its iptables mode: " + script);
    }

    @Test
    void theServerLoadsBrNetfilterBeforeK3sStarts() {
        List<String> args = List.of("server", "--disable-agent", "--disable-network-policy");
        manager.prepareServer(cluster, IMAGE, args, Map.of(), Map.of(), null);

        loadsBrNetfilterThenCleansUp(script(created(), args));
    }

    @Test
    void anAgentLoadsBrNetfilterBeforeK3sStarts() {
        List<String> args = List.of("agent", "--node-name=aks-nodepool1-12345678-vmss000000");
        manager.ensureAgent(cluster, new AgentNode(), new AksClusterManager.AgentSpec(
                "aks-nodepool1-12345678-vmss000000", IMAGE, args, 2, 1L << 30));

        loadsBrNetfilterThenCleansUp(script(created(), args));
    }
}
