package io.floci.az.services.aks;

import com.github.dockerjava.api.model.Container;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.CurrentContainerNetworkResolver;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.services.aks.AksClusterManager.ServerDecision;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The k3s server's arguments are part of its identity: a server started with other arguments is replaced on its data
 * volume, as AKS restarts its API server to enable the OIDC issuer ({@code use-oidc-issuer.md}). A server created
 * before the arguments were recorded carries no label and is adopted as it runs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AksClusterManager: server arguments as server identity")
class AksServerArgsTest {

    private static final String IMAGE = "rancher/k3s:v1.36.6-k3s1";

    @Mock
    ContainerBuilder containerBuilder;

    @Mock
    ContainerLifecycleManager lifecycleManager;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    CurrentContainerNetworkResolver networkResolver;

    @Mock
    PortAllocator portAllocator;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @Mock
    EmulatorConfig.ServicesConfig servicesConfig;

    private static Container container(String image, Map<String, String> labels) {
        Container container = mock(Container.class);
        lenient().when(container.getImage()).thenReturn(image);
        lenient().when(container.getLabels()).thenReturn(labels);
        lenient().when(container.getId()).thenReturn("c1");
        return container;
    }

    @Test
    void theSameImageAndArgumentsAdopt() {
        String hash = AksClusterManager.serverHash(List.of("server", "--a"), Map.of());
        assertEquals(ServerDecision.ADOPT, AksClusterManager.serverDecision(
                Optional.of(container(IMAGE, Map.of(AksClusterManager.SERVER_ARGS_LABEL, hash))), IMAGE, hash, hash));
    }

    @Test
    void theSameImageWithOtherArgumentsReplaces() {
        String before = AksClusterManager.serverHash(List.of("server", "--a"), Map.of());
        String after = AksClusterManager.serverHash(List.of("server", "--a", "--b"), Map.of());
        assertNotEquals(before, after);
        assertEquals(ServerDecision.REPLACE, AksClusterManager.serverDecision(
                Optional.of(container(IMAGE, Map.of(AksClusterManager.SERVER_ARGS_LABEL, before))), IMAGE, after,
                before));
    }

    @Test
    void aChangedStartupFileReplaces() {
        String before = AksClusterManager.serverHash(List.of("server"), Map.of("/etc/floci/authn.yaml", "url: a"));
        String after = AksClusterManager.serverHash(List.of("server"), Map.of("/etc/floci/authn.yaml", "url: b"));
        assertNotEquals(before, after);
    }

    @Test
    void anotherImageReplacesAndNoContainerCreates() {
        String hash = AksClusterManager.serverHash(List.of("server"), Map.of());
        assertEquals(ServerDecision.REPLACE, AksClusterManager.serverDecision(
                Optional.of(container("rancher/k3s:v1.35.2-k3s1", Map.of(AksClusterManager.SERVER_ARGS_LABEL, hash))),
                IMAGE, hash, hash));
        assertEquals(ServerDecision.CREATE, AksClusterManager.serverDecision(Optional.empty(), IMAGE, hash, null));
    }

    @Test
    void anUnlabelledServerIsAdoptedUntilTheRecordedArgumentsChange() {
        String hash = AksClusterManager.serverHash(List.of("server"), Map.of());
        String changed = AksClusterManager.serverHash(List.of("server", "--entra"), Map.of());
        Optional<Container> roundOne = Optional.of(container(IMAGE, Map.of()));
        assertEquals(ServerDecision.ADOPT, AksClusterManager.serverDecision(roundOne, IMAGE, hash, null));
        assertEquals(ServerDecision.ADOPT, AksClusterManager.serverDecision(roundOne, IMAGE, hash, hash));
        assertEquals(ServerDecision.REPLACE, AksClusterManager.serverDecision(roundOne, IMAGE, changed, hash));
    }

    @Test
    void adoptingAnUnlabelledServerRecordsTheArgumentsItIsTakenToRunWith() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.services()).thenReturn(servicesConfig);
        lenient().when(servicesConfig.dockerNetwork()).thenReturn(Optional.empty());
        Container existing = container(IMAGE, Map.of());
        when(lifecycleManager.findByName(anyString())).thenReturn(Optional.of(existing));
        when(lifecycleManager.adopt(anyString(), anyList())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
                "c1", Map.of(), Map.of(AksClusterManager.K3S_API_SERVER_PORT, 16443)));
        ManagedCluster cluster = new ManagedCluster();
        cluster.setInstanceId("abc");
        cluster.setName("aks1");
        AksClusterManager manager = new AksClusterManager(containerBuilder, lifecycleManager, containerDetector,
                networkResolver, portAllocator, config);

        List<String> args = List.of("server", "--disable-agent");
        AksClusterManager.ServerStart start = manager.prepareServer(cluster, IMAGE, args, Map.of(), Map.of());

        assertEquals(AksClusterManager.ServerStart.ADOPTED, start);
        assertEquals(AksClusterManager.serverHash(args, Map.of()), cluster.getServerArgsHash());
        verify(lifecycleManager, never()).stopAndRemove(anyString(), any());
    }
}
