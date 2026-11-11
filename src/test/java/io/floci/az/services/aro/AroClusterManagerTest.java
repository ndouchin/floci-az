package io.floci.az.services.aro;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.docker.ContainerBuilder;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.az.core.docker.ContainerLifecycleManager.ContainerPresence;
import io.floci.az.core.docker.ContainerLifecycleManager.ExecResult;
import io.floci.az.core.docker.ContainerSpec;
import io.floci.az.core.docker.CurrentContainerNetworkResolver;
import io.floci.az.core.docker.DockerHostResolver;
import io.floci.az.core.docker.PortAllocator;
import io.floci.az.core.dns.EmbeddedDnsServer;
import io.floci.az.services.aro.AroModels.OpenShiftCluster;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

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
        return new AroClusterManager(containerBuilder, lifecycleManager, containerDetector, portAllocator,
                mock(DockerHostResolver.class), config);
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
            openshift-cluster-csi-drivers   Running   true
            openshift-cluster-csi-drivers   Running   true
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
    void aLoopbackDaemonAddressIsReachedAsLocalhostWhichTheApiCertificateNames() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(lifecycleManager.daemonHost()).thenReturn("127.0.0.1");
        lenient().when(lifecycleManager.daemonAddress()).thenReturn("127.0.0.1");
        OpenShiftCluster cluster = startableCluster();

        startableManager().startCluster(cluster);

        assertEquals("https://localhost:7600", cluster.getEndpoint());
        assertEquals("127.0.0.1", cluster.getPublicIp());
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

    @Test
    void onTheHostWithALocalDaemonTheApiUrlIsTheProxyOnLocalhost() {
        OpenShiftCluster cluster = proxiedCluster();

        startableManager().startCluster(cluster);

        assertEquals(7700, cluster.getProxyPort());
        assertEquals("https://localhost:7700", cluster.getEndpoint());
        assertEquals("https://localhost:7600", cluster.getInternalEndpoint());
    }

    @Test
    void inDockerTheApiUrlNamesFlociAzWhichTheCertificateCarries() {
        lenient().when(containerDetector.isRunningInContainer()).thenReturn(true);
        lenient().when(config.hostname()).thenReturn(Optional.of("floci-az"));
        OpenShiftCluster cluster = proxiedCluster();
        AroClusterManager manager = startableManager();
        lenient().when(lifecycleManager.startCreated(eq("cid"), any())).thenReturn(new ContainerInfo("cid",
                Map.of(6443, new ContainerLifecycleManager.EndpointInfo("172.18.0.5", 6443))));

        manager.startCluster(cluster);

        assertEquals("https://floci-az:7700", cluster.getEndpoint());
        assertEquals("https://172.18.0.5:6443", cluster.getInternalEndpoint());
        assertTrue(dropIn().contains("- \"floci-az\""), dropIn());
    }

    @Test
    void inPublishedModeWithALoopbackDaemonTheApiUrlIsTheProxyOnLocalhost() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(lifecycleManager.daemonHost()).thenReturn("127.0.0.1");
        lenient().when(lifecycleManager.daemonAddress()).thenReturn("127.0.0.1");
        OpenShiftCluster cluster = proxiedCluster();

        startableManager().startCluster(cluster);

        assertEquals("https://localhost:7700", cluster.getEndpoint());
        assertEquals("https://localhost:7600", cluster.getInternalEndpoint());
        assertFalse(dropIn().contains("localhost"), dropIn());
    }

    @Test
    void inPublishedModeWithARemoteDaemonClientsReachFlociAzAndFlociAzReachesTheDaemon() {
        lenient().when(lifecycleManager.publishedEndpoints()).thenReturn(true);
        lenient().when(lifecycleManager.daemonHost()).thenReturn("192.168.5.2");
        lenient().when(lifecycleManager.daemonAddress()).thenReturn("192.168.5.2");
        lenient().when(config.hostname()).thenReturn(Optional.empty());
        lenient().when(config.effectiveBaseUrl()).thenReturn("http://floci.example:4577");
        OpenShiftCluster cluster = proxiedCluster();

        startableManager().startCluster(cluster);

        assertEquals("https://floci.example:7700", cluster.getEndpoint());
        assertEquals("https://192.168.5.2:7600", cluster.getInternalEndpoint());
        assertTrue(dropIn().contains("- \"floci.example\""), dropIn());
        assertTrue(dropIn().contains("- \"192.168.5.2\""), dropIn());
    }

    @Test
    void aRestoredClusterKeepsItsProxyPortAndAClusterWithoutOneKeepsItsDirectUrl() {
        OpenShiftCluster proxied = proxiedCluster();
        proxied.setProxyPort(7705);
        proxied.setContainerId("old");
        lenient().when(lifecycleManager.presence("old")).thenReturn(ContainerPresence.RUNNING);
        lenient().when(lifecycleManager.adopt(eq("old"), any())).thenReturn(new ContainerInfo("old", Map.of(),
                Map.of(6443, 7610, 80, 7611, 443, 7612)));

        assertEquals(AroClusterManager.Restore.ADOPTED, startableManager().restore(proxied));
        assertEquals("https://localhost:7705", proxied.getEndpoint());
        assertEquals("https://localhost:7610", proxied.getInternalEndpoint());
        verify(portAllocator).reserveUnprobed(7705);

        OpenShiftCluster legacy = startableCluster();
        startableManager().startCluster(legacy);
        assertEquals(0, legacy.getProxyPort());
        assertEquals("https://localhost:7600", legacy.getEndpoint());
    }

    private OpenShiftCluster proxiedCluster() {
        OpenShiftCluster cluster = startableCluster();
        cluster.setOauthEnabled(true);
        return cluster;
    }

    private String dropIn() {
        ArgumentCaptor<Map<String, byte[]>> files = ArgumentCaptor.forClass(Map.class);
        verify(lifecycleManager).copyFilesToContainer(eq("cid"), files.capture());
        return new String(files.getValue().get(AroManifests.CONFIG_DROP_IN));
    }

    @Test
    void finalizeCreatesTheKubeadminSecretOnceWithABcryptOfThePassword() {
        OpenShiftCluster cluster = finalizableCluster();
        List<List<String>> commands = recordExecs(command -> true);

        manager().finalizeCluster(cluster);

        List<String> create = commands.stream().filter(c -> c.size() > 2 && c.get(1).equals("create")).findFirst()
                .orElseThrow();
        assertEquals(List.of("oc", "create", "secret", "generic", "kubeadmin", "-n", "kube-system"),
                create.subList(0, 7));
        String hash = create.get(7).substring("--from-literal=kubeadmin=".length());
        assertTrue(hash.startsWith("$2a$10$"), hash);
        assertTrue(OpenBSDBCrypt.checkPassword(hash, "abcde-fghij-kmnop-qrstu".toCharArray()));
        assertTrue(cluster.isKubeadminSecretCreated());

        commands.clear();
        manager().finalizeCluster(cluster);
        assertTrue(commands.stream().noneMatch(c -> c.get(1).equals("create")), commands.toString());
    }

    @Test
    void finalizeAcceptsAnExistingKubeadminSecretAndWaitsForTheClusterAdminsBinding() {
        OpenShiftCluster cluster = finalizableCluster();
        recordExecs(command -> !command.contains("clusterrolebinding"));
        assertThrows(IllegalStateException.class, () -> manager().finalizeCluster(cluster));
        assertFalse(cluster.isKubeadminSecretCreated());

        recordExecs(command -> !command.get(1).equals("create"));
        manager().finalizeCluster(cluster);
        assertTrue(cluster.isKubeadminSecretCreated());
    }

    private OpenShiftCluster finalizableCluster() {
        OpenShiftCluster cluster = startableCluster();
        cluster.setContainerId("cid");
        cluster.setEndpoint("https://localhost:7700");
        cluster.setKubeadminPassword("abcde-fghij-kmnop-qrstu");
        return cluster;
    }

    /**
     * Answers every exec in container {@code cid}: success when {@code succeeds} accepts the command, else exit 1 with
     * the AlreadyExists message oc prints for a create; {@code cat} of the kubeconfig gives one.
     */
    private List<List<String>> recordExecs(Predicate<List<String>> succeeds) {
        List<List<String>> commands = new ArrayList<>();
        lenient().when(lifecycleManager.execInContainer(eq("cid"), any(String[].class))).thenAnswer(invocation -> {
            List<String> command = List.of((String[]) invocation.getRawArguments()[1]);
            commands.add(command);
            if (command.getFirst().equals("cat")) {
                return new ExecResult(0, "clusters:\n- cluster:\n    server: https://localhost:6443\n");
            }
            return succeeds.test(command) ? new ExecResult(0, "")
                    : new ExecResult(1, "Error from server (AlreadyExists): secrets \"kubeadmin\" already exists");
        });
        return commands;
    }

    /** A manager on a real {@link ContainerBuilder}, whose Docker calls succeed: create gives {@code cid}. */
    private AroClusterManager startableManager() {
        ContainerBuilder realBuilder = new ContainerBuilder(config, mock(DockerHostResolver.class),
                mock(EmbeddedDnsServer.class), mock(CurrentContainerNetworkResolver.class));
        lenient().when(config.services().aro().imageRepository()).thenReturn("quay.io/minc-org/minc");
        lenient().when(config.services().aro().basePort()).thenReturn(7600);
        lenient().when(config.services().aro().maxPort()).thenReturn(7699);
        lenient().when(config.services().aro().storageProvisionerImage()).thenReturn("prov:1");
        lenient().when(config.services().aro().storageHelperImage()).thenReturn("help:1");
        lenient().when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        lenient().when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        lenient().when(config.docker().logMaxSize()).thenReturn("10m");
        lenient().when(config.docker().logMaxFile()).thenReturn("3");
        lenient().when(lifecycleManager.daemonArchitecture()).thenReturn("x86_64");
        lenient().when(portAllocator.allocate(7600, 7699)).thenReturn(7600, 7601, 7602);
        lenient().when(config.services().aro().proxyBasePort()).thenReturn(7700);
        lenient().when(config.services().aro().proxyMaxPort()).thenReturn(7799);
        lenient().when(portAllocator.allocate(7700, 7799)).thenReturn(7700);
        lenient().when(lifecycleManager.create(any())).thenReturn("cid");
        lenient().when(lifecycleManager.startCreated(eq("cid"), any())).thenReturn(new ContainerInfo("cid", Map.of()));
        return new AroClusterManager(realBuilder, lifecycleManager, containerDetector, portAllocator,
                mock(DockerHostResolver.class), config);
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
    void aClusterWithManagedIdentitiesHasNoAzureFileClass() {
        Map<String, byte[]> files = AroManifests.files(new AroManifests.Settings("abc.eastus.aroapp.io",
                "abc-x2b4c-master-0", List.of("n"), "10.128.0.0/14", "172.30.0.0/16", "c0ffee", "prov:1", "help:1",
                false));
        assertFalse(files.containsKey("/etc/microshift/manifests.d/010-floci-aro-storage/storage-azurefile.yaml"));
        assertFalse(new String(files.get("/etc/microshift/manifests.d/010-floci-aro-storage/kustomization.yaml"))
                .contains("azurefile"));

        OpenShiftCluster cluster = new OpenShiftCluster();
        cluster.setProperties(new LinkedHashMap<>(Map.of("clusterProfile", Map.of("domain", "abc"))));
        cluster.setLocation("eastus");
        cluster.setInfraId("abc-x2b4c");
        assertTrue(manager().settings(cluster, "floci-az-aro-1").azureFileClass());
        cluster.getProperties().put("platformWorkloadIdentityProfile", Map.of("platformWorkloadIdentities", Map.of()));
        assertFalse(manager().settings(cluster, "floci-az-aro-1").azureFileClass());
    }

    @Test
    void theKubeconfigIsTheGenericOneForLocalhostAndThePerNameOneOtherwise() {
        assertEquals("/var/lib/microshift/resources/kubeadmin/kubeconfig",
                AroClusterManager.kubeconfigPath("https://localhost:7600"));
        assertEquals("/var/lib/microshift/resources/kubeadmin/floci-az-aro-1a2b3c4d/kubeconfig",
                AroClusterManager.kubeconfigPath("https://floci-az-aro-1a2b3c4d:6443"));
        assertEquals("/var/lib/microshift/resources/kubeadmin/192.168.5.2/kubeconfig",
                AroClusterManager.kubeconfigPath("https://192.168.5.2:7600"));
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
                List.of("floci-az-aro-1", "api.abc.eastus.aroapp.io"), "10.128.0.0/14", "172.30.0.0/16",
                "uuid", "p", "h", true));
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

    @Test
    void theContainerStartsWithTheDropInStorageAndConfigManifests() {
        Map<String, byte[]> files = AroManifests.files(new AroManifests.Settings("abc.eastus.aroapp.io",
                "abc-x2b4c-master-0", List.of("n"), "10.128.0.0/14", "172.30.0.0/16", "c0ffee", "prov:1", "help:1",
                true));
        assertTrue(files.containsKey("/etc/microshift/config.d/10-floci-aro.yaml"));
        String storage = new String(files.get("/etc/microshift/manifests.d/010-floci-aro-storage/storage.yaml"));
        assertTrue(storage.contains("name: managed-csi"));
        assertTrue(storage.contains("provisioner: disk.csi.azure.com"));
        assertTrue(storage.contains("image: prov:1"));
        assertFalse(storage.contains("${"));
        String azureFile = new String(files.get(
                "/etc/microshift/manifests.d/010-floci-aro-storage/storage-azurefile.yaml"));
        assertTrue(azureFile.contains("name: azurefile-csi"));
        assertTrue(azureFile.contains("provisioner: file.csi.azure.com"));
        assertFalse(azureFile.contains("${"));
        assertTrue(new String(files.get("/etc/microshift/manifests.d/010-floci-aro-storage/kustomization.yaml"))
                .contains("- storage-azurefile.yaml"));
        String config = new String(files.get("/etc/microshift/manifests.d/030-floci-aro-config/cluster-config.yaml"));
        assertTrue(config.contains("clusterID: c0ffee"));
        assertTrue(config.contains("domain: apps.abc.eastus.aroapp.io"));
        assertEquals(3, files.keySet().stream().filter(k -> k.contains("020-floci-aro-config-crds/0000_")).count());
    }
}
