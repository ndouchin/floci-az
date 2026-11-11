package io.floci.az.services.aro;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerPresence;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.CurrentContainerNetworkResolver;
import io.floci.az.core.docker.DockerHostResolver;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.core.dns.EmbeddedDnsServer;
import io.floci.az.services.aro.AroModels.OpenShiftCluster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AroClusterManagerTest {

    @Mock
    ContainerBuilder containerBuilder;

    @Mock
    ContainerLifecycleManager lifecycleManager;

    @Mock
    ContainerDetector containerDetector;

    @Mock
    PortAllocator portAllocator;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    EmulatorConfig config;

    private AroClusterManager manager() {
        return new AroClusterManager(containerBuilder, lifecycleManager, containerDetector, portAllocator, config);
    }

    @Test
    void theImageTagCarriesTheDaemonsArchitecture() {
        lenient().when(config.services().aro().imageRepository()).thenReturn("quay.io/minc-org/minc");
        lenient().when(lifecycleManager.daemonArchitecture()).thenReturn("x86_64");
        assertEquals("quay.io/minc-org/minc:4.19.0-okd-scos.17-amd64", manager().image("4.19.0-okd-scos.17"));
    }

    @Test
    void theApiCertificateNamesNeverIncludeLocalhost() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(false);
        assertEquals(List.of("floci-az-aro-1", "api.abc.eastus.aroapp.io"),
                manager().subjectAltNames("floci-az-aro-1", "abc.eastus.aroapp.io"));
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(lifecycleManager.daemonAddress()).thenReturn("localhost");
        assertEquals(2, manager().subjectAltNames("floci-az-aro-1", "abc.eastus.aroapp.io").size());
        lenient().when(lifecycleManager.daemonAddress()).thenReturn("192.168.5.2");
        assertEquals(List.of("floci-az-aro-1", "api.abc.eastus.aroapp.io", "192.168.5.2"),
                manager().subjectAltNames("floci-az-aro-1", "abc.eastus.aroapp.io"));
    }

    @Test
    void aClusterIsReadyOnlyWhenEveryCorePodRuns() {
        String running = """
            kube-proxy                      Running   true
            openshift-dns                   Running   true,true
            openshift-dns                   Running   true
            openshift-ingress               Running   true
            openshift-service-ca            Running   true
            """;
        assertTrue(AroClusterManager.corePodsRunning(running));
        assertFalse(AroClusterManager.corePodsRunning(running.replace("true,true", "true,false")));
        assertFalse(AroClusterManager.corePodsRunning(running.replace("openshift-ingress               Running",
                "openshift-ingress               Pending")));
        assertFalse(AroClusterManager.corePodsRunning(running.replace("openshift-service-ca", "openshift-other")));
    }

    @Test
    void theContainerHostnameIsTheNodeNameSoKubeProxyCountsTheRouterAsLocal() {
        startableManager().startCluster(startableCluster());

        ArgumentCaptor<ContainerSpec> spec = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(spec.capture());
        assertEquals("demo-x2b4c-master-0", spec.getValue().hostname());
        assertTrue(spec.getValue().privileged());
        assertEquals("quay.io/minc-org/minc:4.19.0-okd-scos.17-amd64", spec.getValue().image());
    }

    @Test
    void restoringAdoptsASurvivingContainerAndItsPublishedPorts() {
        OpenShiftCluster cluster = startableCluster();
        cluster.setContainerId("old");
        lenient().when(lifecycleManager.presence("old")).thenReturn(ContainerPresence.STOPPED);
        lenient().when(lifecycleManager.adopt(eq("old"), any())).thenReturn(new ContainerInfo("old", Map.of(),
                Map.of(6443, 7610, 80, 7611, 443, 7612)));

        assertEquals(AroClusterManager.Restore.ADOPTED, startableManager().restore(cluster));

        assertEquals("https://localhost:7610", cluster.getEndpoint());
        assertEquals(7611, cluster.getHttpHostPort());
        assertEquals(7612, cluster.getHttpsHostPort());
        verify(portAllocator).reserveUnprobed(7610);
        verify(lifecycleManager, never()).create(any());
    }

    @Test
    void restoringRecreatesTheContainerOnTheSurvivingDataVolume() {
        OpenShiftCluster cluster = startableCluster();
        cluster.setContainerId("gone");
        lenient().when(lifecycleManager.presence("gone")).thenReturn(ContainerPresence.ABSENT);
        lenient().when(lifecycleManager.findByName("floci-az-aro-1a2b3c4d")).thenReturn(Optional.empty());
        lenient().when(lifecycleManager.volumeExists("floci-az-aro-1a2b3c4d-data")).thenReturn(true);

        assertEquals(AroClusterManager.Restore.RECREATED_ON_VOLUME, startableManager().restore(cluster));

        assertEquals("cid", cluster.getContainerId());
        verify(lifecycleManager, never()).adopt(any(), any());
        verify(lifecycleManager, never()).removeVolume(any());
    }

    @Test
    void restoringStartsAFreshClusterWhenNothingSurvived() {
        OpenShiftCluster cluster = startableCluster();
        lenient().when(lifecycleManager.findByName("floci-az-aro-1a2b3c4d")).thenReturn(Optional.empty());

        assertEquals(AroClusterManager.Restore.RECREATED, startableManager().restore(cluster));

        assertEquals("cid", cluster.getContainerId());
        assertEquals("https://localhost:7600", cluster.getEndpoint());
    }

    /** A manager on a real {@link ContainerBuilder}, whose Docker calls succeed: create gives {@code cid}. */
    private AroClusterManager startableManager() {
        ContainerBuilder realBuilder = new ContainerBuilder(config, mock(DockerHostResolver.class),
                mock(EmbeddedDnsServer.class), mock(CurrentContainerNetworkResolver.class));
        lenient().when(config.services().aro().imageRepository()).thenReturn("quay.io/minc-org/minc");
        lenient().when(config.services().aro().basePort()).thenReturn(7600);
        lenient().when(config.services().aro().maxPort()).thenReturn(7699);
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.docker().logMaxSize()).thenReturn("10m");
        lenient().when(config.docker().logMaxFile()).thenReturn("3");
        lenient().when(lifecycleManager.daemonArchitecture()).thenReturn("x86_64");
        lenient().when(portAllocator.allocate(7600, 7699)).thenReturn(7600, 7601, 7602);
        lenient().when(lifecycleManager.create(any())).thenReturn("cid");
        lenient().when(lifecycleManager.startCreated(eq("cid"), any())).thenReturn(new ContainerInfo("cid", Map.of()));
        return new AroClusterManager(realBuilder, lifecycleManager, containerDetector, portAllocator, config);
    }

    private static OpenShiftCluster startableCluster() {
        OpenShiftCluster cluster = new OpenShiftCluster();
        cluster.setInstanceId("1a2b3c4d");
        cluster.setSubscriptionId("sub");
        cluster.setResourceGroup("rg");
        cluster.setName("demo");
        cluster.setLocation("eastus");
        cluster.setInfraId("demo-x2b4c");
        cluster.setClusterUuid("c0ffee");
        cluster.setImageTag("4.19.0-okd-scos.17");
        cluster.setProperties(new LinkedHashMap<>(Map.of(
                "clusterProfile", Map.of("domain", "demo"),
                "networkProfile", Map.of("podCidr", "10.128.0.0/14", "serviceCidr", "172.30.0.0/16"))));
        return cluster;
    }

    @Test
    void theNodeIsNamedAfterTheInfraIdLikeAnAroMaster() {
        OpenShiftCluster cluster = new OpenShiftCluster();
        cluster.setInfraId("demo-x2b4c");
        assertEquals("demo-x2b4c-master-0", AroClusterManager.nodeName(cluster));
    }

    @Test
    void theDropInSetsDomainNetworksNamesAndNodeName() {
        String yaml = AroManifests.configDropIn(new AroManifests.Settings("abc.eastus.aroapp.io", "abc-x2b4c-master-0",
                List.of("floci-az-aro-1", "api.abc.eastus.aroapp.io"), "10.128.0.0/14", "172.30.0.0/16"));
        assertEquals("""
            apiServer:
              subjectAltNames:
              - "floci-az-aro-1"
              - "api.abc.eastus.aroapp.io"
            dns:
              baseDomain: "abc.eastus.aroapp.io"
            network:
              clusterNetwork:
              - "10.128.0.0/14"
              serviceNetwork:
              - "172.30.0.0/16"
            node:
              hostnameOverride: "abc-x2b4c-master-0"
            """, yaml);
    }
}
