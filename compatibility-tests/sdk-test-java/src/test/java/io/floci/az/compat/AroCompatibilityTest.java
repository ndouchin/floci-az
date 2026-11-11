package io.floci.az.compat;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.management.profile.AzureProfile;
import com.azure.resourcemanager.redhatopenshift.RedHatOpenShiftManager;
import com.azure.resourcemanager.redhatopenshift.fluent.OpenShiftClustersClient;
import com.azure.resourcemanager.redhatopenshift.fluent.models.OpenShiftClusterCredentialsInner;
import com.azure.resourcemanager.redhatopenshift.fluent.models.OpenShiftClusterInner;
import com.azure.resourcemanager.redhatopenshift.models.ApiServerProfile;
import com.azure.resourcemanager.redhatopenshift.models.ClusterProfile;
import com.azure.resourcemanager.redhatopenshift.models.IngressProfile;
import com.azure.resourcemanager.redhatopenshift.models.MasterProfile;
import com.azure.resourcemanager.redhatopenshift.models.NetworkProfile;
import com.azure.resourcemanager.redhatopenshift.models.OpenShiftCluster;
import com.azure.resourcemanager.redhatopenshift.models.OpenShiftClusterUpdate;
import com.azure.resourcemanager.redhatopenshift.models.ProvisioningState;
import com.azure.resourcemanager.redhatopenshift.models.ServicePrincipalProfile;
import com.azure.resourcemanager.redhatopenshift.models.Visibility;
import com.azure.resourcemanager.redhatopenshift.models.WorkerProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility coverage for Azure Red Hat OpenShift through Microsoft's {@code azure-resourcemanager-redhatopenshift}
 * management client (api-version 2025-07-25), pointed at floci-az the same way {@link AksCompatibilityTest} is.
 *
 * <p>Written for mocked mode ({@code FLOCI_AZ_SERVICES_ARO_MOCKED=true}), where a cluster reaches {@code Succeeded}
 * without Docker. In real mode the create poller waits for the MicroShift container instead.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Azure Red Hat OpenShift Java SDK Compatibility")
class AroCompatibilityTest {

    private static final String ENDPOINT = EmulatorConfig.httpBase();
    private static final String SUBSCRIPTION = "00000000-0000-0000-0000-000000000001";
    private static final String TENANT = "00000000-0000-0000-0000-000000000002";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String RESOURCE_GROUP = "aro-rg-" + SUFFIX;
    private static final String CLUSTER = "aro-" + SUFFIX;
    private static final String DOMAIN = "d" + SUFFIX;

    private static RedHatOpenShiftManager manager;
    private static OpenShiftClustersClient clusters;

    @BeforeAll
    static void setup() {
        EmulatorConfig.assumeEmulatorRunning();
        Map<String, String> endpoints = new HashMap<>(AzureEnvironment.AZURE.getEndpoints());
        endpoints.put("resourceManagerEndpointUrl", ENDPOINT + "/");
        endpoints.put("managementEndpointUrl", ENDPOINT + "/");
        AzureProfile profile = new AzureProfile(TENANT, SUBSCRIPTION, new AzureEnvironment(endpoints));
        HttpPipeline pipeline = new HttpPipelineBuilder()
                .httpClient(HttpClient.createDefault())
                .build();
        manager = RedHatOpenShiftManager.authenticate(pipeline, profile);
        clusters = manager.serviceClient().getOpenShiftClusters();
    }

    @Test
    @Order(1)
    void createClusterReachesSucceeded() {
        OpenShiftClusterInner request = new OpenShiftClusterInner()
                .withLocation("eastus")
                .withTags(Map.of("suite", "java-sdk"))
                .withClusterProfile(new ClusterProfile().withDomain(DOMAIN).withVersion("4.19.0")
                        .withPullSecret("{\"auths\":{}}"))
                .withServicePrincipalProfile(new ServicePrincipalProfile()
                        .withClientId(UUID.randomUUID().toString()).withClientSecret("not-a-real-secret"))
                .withNetworkProfile(new NetworkProfile().withPodCidr("10.128.0.0/14").withServiceCidr("172.30.0.0/16"))
                .withMasterProfile(new MasterProfile().withVmSize("Standard_D8s_v5"))
                .withWorkerProfiles(List.of(new WorkerProfile().withName("worker").withVmSize("Standard_D4s_v5")
                        .withDiskSizeGB(128).withCount(3)))
                .withApiserverProfile(new ApiServerProfile().withVisibility(Visibility.PUBLIC))
                .withIngressProfiles(List.of(new IngressProfile().withName("default")
                        .withVisibility(Visibility.PUBLIC)));

        OpenShiftClusterInner created = clusters.createOrUpdate(RESOURCE_GROUP, CLUSTER, request);

        assertEquals(CLUSTER, created.name());
        assertEquals(ProvisioningState.SUCCEEDED, created.provisioningState());
        assertEquals("Microsoft.RedHatOpenShift/openShiftClusters", created.type());
        assertEquals("/subscriptions/" + SUBSCRIPTION + "/resourceGroups/" + RESOURCE_GROUP
                + "/providers/Microsoft.RedHatOpenShift/openShiftClusters/" + CLUSTER, created.id());
        assertEquals(DOMAIN, created.clusterProfile().domain());
        assertEquals("4.19.0", created.clusterProfile().version());
        assertNull(created.clusterProfile().pullSecret(), "the pull secret is never returned");
        assertNull(created.servicePrincipalProfile().clientSecret(), "the client secret is never returned");
        assertEquals("https://console-openshift-console.apps." + DOMAIN + ".eastus.aroapp.io/",
                created.consoleProfile().url());
        assertTrue(created.apiserverProfile().url().startsWith("https://"), created.apiserverProfile().url());
    }

    @Test
    @Order(2)
    void getClusterThroughTheFluentApi() {
        OpenShiftCluster cluster = manager.openShiftClusters().getByResourceGroup(RESOURCE_GROUP, CLUSTER);

        assertEquals(CLUSTER, cluster.name());
        assertEquals("eastus", cluster.regionName());
        assertEquals("java-sdk", cluster.tags().get("suite"));
        assertEquals(ProvisioningState.SUCCEEDED, cluster.provisioningState());
        assertEquals("172.30.0.0/16", cluster.networkProfile().serviceCidr());
        assertEquals(3, cluster.workerProfilesStatus().stream().mapToInt(WorkerProfile::count).sum());
    }

    @Test
    @Order(3)
    void listClustersByResourceGroupAndSubscription() {
        List<String> inGroup = new ArrayList<>();
        clusters.listByResourceGroup(RESOURCE_GROUP).forEach(value -> inGroup.add(value.name()));
        assertEquals(List.of(CLUSTER), inGroup);

        List<String> inSubscription = new ArrayList<>();
        clusters.list().forEach(value -> inSubscription.add(value.name()));
        assertTrue(inSubscription.contains(CLUSTER), "subscription list: " + inSubscription);
    }

    @Test
    @Order(4)
    void listCredentialsAndTheAdminKubeconfig() {
        OpenShiftClusterCredentialsInner credentials = clusters.listCredentials(RESOURCE_GROUP, CLUSTER);
        assertEquals("kubeadmin", credentials.kubeadminUsername());
        assertTrue(credentials.kubeadminPassword().matches("[A-Za-z0-9]{5}(-[A-Za-z0-9]{5}){3}"),
                credentials.kubeadminPassword());

        String kubeconfig = new String(Base64.getDecoder().decode(
                clusters.listAdminCredentials(RESOURCE_GROUP, CLUSTER).kubeconfig()), StandardCharsets.UTF_8);
        assertTrue(kubeconfig.contains("kind: Config"), kubeconfig);
        assertTrue(kubeconfig.contains("server: https://"), kubeconfig);
    }

    @Test
    @Order(5)
    void openShiftVersionsListsTheInstallableVersions() {
        List<String> versions = new ArrayList<>();
        manager.openShiftVersions().list("eastus").forEach(version -> versions.add(version.version()));
        assertTrue(versions.contains("4.19.0"), "versions: " + versions);
    }

    @Test
    @Order(6)
    void patchingTagsThroughTheSdkPoller() {
        OpenShiftClusterInner updated = clusters.update(RESOURCE_GROUP, CLUSTER,
                new OpenShiftClusterUpdate().withTags(Map.of("suite", "java-sdk", "patched", "yes")));

        assertEquals(ProvisioningState.SUCCEEDED, updated.provisioningState());
        assertEquals("yes", updated.tags().get("patched"));
    }

    @Test
    @Order(7)
    void deleteClusterThroughTheSdkPoller() {
        clusters.delete(RESOURCE_GROUP, CLUSTER);

        ManagementException missing = assertThrows(ManagementException.class,
                () -> clusters.getByResourceGroup(RESOURCE_GROUP, CLUSTER));
        assertEquals(404, missing.getResponse().getStatusCode());
    }
}
