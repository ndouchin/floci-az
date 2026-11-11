package io.floci.az.core.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectNetworkCmd;
import com.github.dockerjava.api.command.RemoveNetworkCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkSettings;
import com.github.dockerjava.api.model.Ports;
import io.floci.az.config.EmulatorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-resource Docker networks, primary networks, hostnames and CPU limits: what services running several
 * containers per resource (an AKS control plane and its nodes) need from the shared container layer.
 */
@ExtendWith(MockitoExtension.class)
class ContainerLifecycleManagerNetworkTest {

    @Mock
    DockerClient dockerClient;

    @Mock
    ImageCacheService imageCacheService;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    EmulatorConfig config;

    @Mock
    EmulatorConfig.DockerConfig dockerConfig;

    @BeforeEach
    void setUp() {
        lenient().when(config.docker()).thenReturn(dockerConfig);
        lenient().when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(dockerConfig.endpointMode()).thenReturn(EmulatorConfig.DockerEndpointMode.AUTO);
    }

    private ContainerLifecycleManager manager() {
        return new ContainerLifecycleManager(dockerClient, imageCacheService, containerDetector, config);
    }

    @Test
    void aPrimaryNetworkIsJoinedAtCreateEvenWithPortBindings() {
        CreateContainerCmd createCmd = stubCreate();
        ContainerSpec spec = spec(Map.of(6443, 7000), "floci-aks-abc", true, null, null);

        manager().create(spec);

        assertEquals("floci-aks-abc", capturedHostConfig(createCmd).getNetworkMode());
    }

    @Test
    void aPlainNetworkWithPortBindingsIsStillJoinedAfterStart() {
        CreateContainerCmd createCmd = stubCreate();
        ContainerSpec spec = spec(Map.of(6443, 7000), "shared-net", false, null, null);

        manager().create(spec);

        assertNull(capturedHostConfig(createCmd).getNetworkMode());
    }

    @Test
    void startingAContainerOnItsPrimaryNetworkDoesNotConnectItAgain() {
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("c1")).thenReturn(startCmd);
        stubInspect("c1", Map.of());

        manager().startCreated("c1", spec(Map.of(6443, 7000), "floci-aks-abc", true, null, null));

        verify(dockerClient, never()).connectToNetworkCmd();
    }

    @Test
    void hostnameAndCpuLimitReachDocker() {
        CreateContainerCmd createCmd = stubCreate();

        manager().create(spec(Map.of(), null, false, "aks-nodepool1-12345678-vmss000000", 2_000_000_000L));

        verify(createCmd).withHostName("aks-nodepool1-12345678-vmss000000");
        assertEquals(2_000_000_000L, capturedHostConfig(createCmd).getNanoCPUs());
    }

    @Test
    void ensureNetworkCreatesAMissingNetworkWithTheEmulatorLabels() {
        InspectNetworkCmd inspect = mock(InspectNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.inspectNetworkCmd()).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("no such network"));
        CreateNetworkCmd create = mock(CreateNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.createNetworkCmd()).thenReturn(create);
        when(create.exec()).thenReturn(new CreateNetworkResponse());

        manager().ensureNetwork("floci-aks-abc", Map.of("io.floci.service", "aks"));

        verify(create).withName("floci-aks-abc");
        verify(create).withDriver("bridge");
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.captor();
        verify(create).withLabels(labels.capture());
        assertEquals("true", labels.getValue().get("floci"));
        assertEquals("aks", labels.getValue().get("io.floci.service"));
    }

    @Test
    void ensureNetworkLeavesAnExistingNetworkAlone() {
        InspectNetworkCmd inspect = mock(InspectNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.inspectNetworkCmd()).thenReturn(inspect);
        when(inspect.exec()).thenReturn(new Network());

        manager().ensureNetwork("floci-aks-abc", Map.of());

        verify(dockerClient, never()).createNetworkCmd();
    }

    @Test
    void removeNetworkToleratesAMissingNetwork() {
        RemoveNetworkCmd remove = mock(RemoveNetworkCmd.class);
        when(dockerClient.removeNetworkCmd("floci-aks-abc")).thenReturn(remove);
        when(remove.exec()).thenThrow(new NotFoundException("gone"));

        manager().removeNetwork("floci-aks-abc");

        verify(remove).exec();
    }

    @Test
    void connectToNetworkConnectsTheContainer() {
        ConnectToNetworkCmd connect = mock(ConnectToNetworkCmd.class, RETURNS_SELF);
        when(dockerClient.connectToNetworkCmd()).thenReturn(connect);

        manager().connectToNetwork("c1", "shared-net");

        verify(connect).withContainerId("c1");
        verify(connect).withNetworkId("shared-net");
        verify(connect).exec();
    }

    @Test
    void addressOnANetworkIsReadFromTheContainer() {
        ContainerNetwork onCluster = mock(ContainerNetwork.class);
        lenient().when(onCluster.getIpAddress()).thenReturn("172.30.0.2");
        ContainerNetwork onShared = mock(ContainerNetwork.class);
        when(onShared.getIpAddress()).thenReturn("172.18.0.5");
        stubInspect("c1", Map.of("floci-aks-abc", onCluster, "shared-net", onShared));

        assertEquals(Optional.of("172.18.0.5"), manager().addressOnNetwork("c1", "shared-net"));
        assertEquals(Optional.empty(), manager().addressOnNetwork("c1", "other"));
    }

    private CreateContainerCmd stubCreate() {
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        CreateContainerResponse response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("c1");
        when(createCmd.exec()).thenReturn(response);
        return createCmd;
    }

    private void stubInspect(String containerId, Map<String, ContainerNetwork> networks) {
        InspectContainerResponse inspect = mock(InspectContainerResponse.class);
        NetworkSettings settings = mock(NetworkSettings.class);
        lenient().when(settings.getNetworks()).thenReturn(networks);
        lenient().when(settings.getPorts()).thenReturn(new Ports(ExposedPort.tcp(6443), Ports.Binding.bindPort(7000)));
        lenient().when(inspect.getNetworkSettings()).thenReturn(settings);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(inspectCmd.exec()).thenReturn(inspect);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
    }

    private static HostConfig capturedHostConfig(CreateContainerCmd createCmd) {
        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        return hostConfig.getValue();
    }

    private static ContainerSpec spec(Map<Integer, Integer> ports, String network, boolean primary, String hostname,
                                      Long nanoCpus) {
        return new ContainerSpec("rancher/k3s:test", "c", List.of(), null, null, null, ports,
                List.copyOf(ports.keySet()), network, List.of(), List.of(), List.of(), Map.of(), null, false, null,
                List.of(), null, null, List.of(), hostname, nanoCpus, primary);
    }
}
