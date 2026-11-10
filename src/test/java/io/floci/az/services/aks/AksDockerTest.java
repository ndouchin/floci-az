package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AKS with {@code mocked=false}: real k3s containers, driven through the ARM API and checked through the kubeconfig
 * the API hands out. Skipped when no Docker daemon is reachable.
 *
 * <p>Tests are ordered and share one cluster: created on 1.35 with three agent pools, checked node by node, scaled,
 * stopped and started, its server container lost and restored on the same data volume, upgraded to 1.36, then
 * deleted.
 */
@QuarkusTest
@TestProfile(AksDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AksHandler: real k3s mode (Docker required)")
class AksDockerTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aks.mocked", "false");
        }
    }

    private static final String SUB = "test-sub-aks-docker";
    private static final String RG = "test-rg-docker";
    private static final String NAME = "k3s-test-cluster";
    private static final String API = "?api-version=2025-10-01";
    private static final String CLUSTER_PATH = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.ContainerService/managedClusters/" + NAME;
    private static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(5);

    @Inject
    DockerClient docker;

    @Inject
    AksHandler handler;

    private String adminKubeconfig;

    /** Pure filesystem check, safe to run before Quarkus is fully ready. */
    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock"))
                || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available, skipping real k3s tests");
    }

    @AfterAll
    void cleanup() {
        try {
            given().delete(CLUSTER_PATH + API);
            AksTestPolling.until(Duration.ofMinutes(2), () -> given().get(CLUSTER_PATH + API).statusCode() == 404);
        } catch (Throwable ignored) {
            // best effort: a failed test may already have removed the cluster
        }
    }

    private static String pathOf(String url) {
        URI uri = URI.create(url);
        return uri.getRawPath() + "?" + uri.getRawQuery();
    }

    private static void awaitOperation(Response accepted) {
        String operation = pathOf(accepted.header("Azure-AsyncOperation"));
        AksTestPolling.until(OPERATION_TIMEOUT, () -> !"InProgress".equals(given().get(operation).path("status")));
        given().get(operation).then().statusCode(200).body("status", equalTo("Succeeded"));
    }

    private static void awaitLocation(Response accepted) {
        String location = pathOf(accepted.header("Location"));
        AksTestPolling.until(OPERATION_TIMEOUT, () -> given().get(location).statusCode() != 202);
        given().get(location).then().statusCode(204);
    }

    private List<Container> containers(String role) {
        return docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("io.floci.resource-id", NAME, AksClusterManager.ROLE_LABEL, role))
                .exec();
    }

    private static String kubeconfig(String operation) {
        return given().contentType("application/json").post(CLUSTER_PATH + "/" + operation + API)
                .then().statusCode(200).extract().path("kubeconfigs[0].value");
    }

    private static String gitVersion(String kubeconfig) throws Exception {
        return KubeconfigClient.fromBase64(kubeconfig).get("/version").path("gitVersion").asText();
    }

    private static boolean answers(String kubeconfig) {
        try {
            gitVersion(kubeconfig);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @Order(1)
    @DisplayName("PUT is a long-running operation that ends Succeeded on the requested version")
    void createCluster() {
        given().post("/_admin/reset").then().statusCode(204);

        Response created = given().contentType("application/json").body("""
                {
                  "location": "eastus",
                  "properties": {
                    "kubernetesVersion": "1.35",
                    "dnsPrefix": "k3s-test-dns",
                    "agentPoolProfiles": [
                      {"name": "nodepool1", "count": 1, "vmSize": "Standard_DS2_v2", "osType": "Linux",
                       "mode": "System"},
                      {"name": "userpool", "count": 2, "vmSize": "Standard_D2s_v5", "mode": "User",
                       "nodeLabels": {"team": "payments"}, "nodeTaints": ["dedicated=batch:NoSchedule"],
                       "availabilityZones": ["1", "2"]},
                      {"name": "zeropool", "count": 0, "vmSize": "Standard_D2s_v5", "mode": "User"}
                    ]
                  }
                }
                """).put(CLUSTER_PATH + API);
        created.then().statusCode(201)
                .body("properties.provisioningState", equalTo("Creating"))
                .header("Azure-AsyncOperation", containsString("/operations/"));

        awaitOperation(created);

        given().get(CLUSTER_PATH + API).then().statusCode(200)
                .body("type", equalTo("Microsoft.ContainerService/managedClusters"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.kubernetesVersion", equalTo("1.35"))
                .body("properties.currentKubernetesVersion", equalTo("1.35.10"))
                .body("properties.powerState.code", equalTo("Running"));
    }

    @Test
    @Order(2)
    @DisplayName("the admin kubeconfig reaches the API server running the requested version")
    void adminKubeconfigReachesTheRequestedVersion() throws Exception {
        adminKubeconfig = kubeconfig("listClusterAdminCredential");

        assertTrue(gitVersion(adminKubeconfig).startsWith("v1.35.10"), gitVersion(adminKubeconfig));
    }

    private JsonNode nodes() throws Exception {
        return KubeconfigClient.fromBase64(adminKubeconfig).get("/api/v1/nodes").path("items");
    }

    private static JsonNode node(JsonNode nodes, String prefix, String suffix) {
        for (JsonNode node : nodes) {
            String name = node.path("metadata").path("name").asText();
            if (name.startsWith(prefix) && name.endsWith(suffix)) {
                return node;
            }
        }
        throw new AssertionError("no node " + prefix + "*" + suffix + " in " + nodes);
    }

    @Test
    @Order(3)
    @DisplayName("agent pools are AKS-named nodes with AKS labels, taints and provider IDs; count 0 has none")
    void agentPoolsAreNodes() throws Exception {
        JsonNode nodes = nodes();
        assertEquals(3, nodes.size(), nodes.toString());
        for (JsonNode node : nodes) {
            String name = node.path("metadata").path("name").asText();
            assertTrue(name.matches("aks-(nodepool1|userpool)-\\d{8}-vmss00000[01]"), name);
            assertTrue(node.path("spec").path("providerID").asText().startsWith("azure:///subscriptions/" + SUB
                    + "/resourceGroups/MC_" + RG + "_" + NAME + "_eastus/providers/Microsoft.Compute"
                    + "/virtualMachineScaleSets/"), node.path("spec").path("providerID").asText());
        }
        JsonNode system = node(nodes, "aks-nodepool1-", "vmss000000");
        JsonNode systemLabels = system.path("metadata").path("labels");
        assertEquals("nodepool1", systemLabels.path("kubernetes.azure.com/agentpool").asText());
        assertEquals("nodepool1", systemLabels.path("agentpool").asText());
        assertEquals("system", systemLabels.path("kubernetes.azure.com/mode").asText());
        assertEquals("agent", systemLabels.path("kubernetes.azure.com/role").asText());
        assertEquals("MC_" + RG + "_" + NAME + "_eastus", systemLabels.path("kubernetes.azure.com/cluster").asText());
        assertEquals("Ubuntu", systemLabels.path("kubernetes.azure.com/os-sku").asText());
        assertEquals("Standard_DS2_v2", systemLabels.path("node.kubernetes.io/instance-type").asText());
        assertEquals("eastus", systemLabels.path("topology.kubernetes.io/region").asText());
        assertEquals("0", systemLabels.path("topology.kubernetes.io/zone").asText());

        JsonNode user1 = node(nodes, "aks-userpool-", "vmss000001");
        JsonNode userLabels = user1.path("metadata").path("labels");
        assertEquals("user", userLabels.path("kubernetes.azure.com/mode").asText());
        assertEquals("payments", userLabels.path("team").asText());
        assertEquals("eastus-2", userLabels.path("topology.kubernetes.io/zone").asText());
        assertEquals("Standard_D2s_v5", userLabels.path("node.kubernetes.io/instance-type").asText());
        assertTrue(user1.path("spec").path("taints").toString().contains("\"key\":\"dedicated\""),
                user1.path("spec").path("taints").toString());
        assertTrue(user1.path("spec").path("providerID").asText().endsWith("-vmss/virtualMachines/1"));
        assertTrue(user1.path("status").path("nodeInfo").path("kubeletVersion").asText().startsWith("v1.35.10"));

        assertEquals(1, containers("server").size());
        assertEquals(3, containers("agent").size());
    }

    private static final String PVC_PATH = "/api/v1/namespaces/default/persistentvolumeclaims";

    private static String phase(KubeconfigClient kube, String path) {
        try {
            return kube.get(path).path("status").path("phase").asText();
        } catch (Exception e) {
            return "unreadable: " + e.getMessage();
        }
    }

    @Test
    @Order(4)
    @DisplayName("the AKS storage classes exist, default is the default, and disk and file claims bind")
    void storageClassesBind() throws Exception {
        KubeconfigClient kube = KubeconfigClient.fromBase64(adminKubeconfig);
        JsonNode classes = kube.get("/apis/storage.k8s.io/v1/storageclasses").path("items");
        Map<String, JsonNode> byName = new TreeMap<>();
        classes.forEach(c -> byName.put(c.path("metadata").path("name").asText(), c));
        assertEquals(Set.of("default", "managed-csi", "managed-csi-premium", "managed-csi-premium-v2",
                "managed-premium", "azurefile-csi", "azurefile-csi-premium", "azurefile", "azurefile-premium"),
                byName.keySet());
        assertEquals("true", byName.get("default").path("metadata").path("annotations")
                .path("storageclass.kubernetes.io/is-default-class").asText());
        assertEquals("disk.csi.azure.com", byName.get("managed-csi").path("provisioner").asText());
        assertEquals("file.csi.azure.com", byName.get("azurefile-csi").path("provisioner").asText());

        assertEquals(201, kube.send("POST", PVC_PATH, """
                {"apiVersion":"v1","kind":"PersistentVolumeClaim","metadata":{"name":"disk-claim"},
                 "spec":{"accessModes":["ReadWriteOnce"],"resources":{"requests":{"storage":"1Gi"}}}}"""));
        assertEquals(201, kube.send("POST", PVC_PATH, """
                {"apiVersion":"v1","kind":"PersistentVolumeClaim","metadata":{"name":"file-claim"},
                 "spec":{"accessModes":["ReadWriteMany"],"storageClassName":"azurefile-csi",
                         "resources":{"requests":{"storage":"1Gi"}}}}"""));
        assertEquals(201, kube.send("POST", "/api/v1/namespaces/default/pods", """
                {"apiVersion":"v1","kind":"Pod","metadata":{"name":"disk-writer"},
                 "spec":{"restartPolicy":"Never","containers":[{"name":"w","image":"%s",
                   "command":["sh","-c","echo ok > /data/f && cat /data/f"],
                   "volumeMounts":[{"name":"d","mountPath":"/data"}]}],
                   "volumes":[{"name":"d","persistentVolumeClaim":{"claimName":"disk-claim"}}]}}"""
                .formatted(AksStorageManifests.HELPER_IMAGE)));

        AksTestPolling.until(Duration.ofMinutes(3), () -> "Bound".equals(phase(kube, PVC_PATH + "/file-claim")));
        AksTestPolling.until(Duration.ofMinutes(3), () -> "Succeeded".equals(
                phase(kube, "/api/v1/namespaces/default/pods/disk-writer")));
        assertEquals("Bound", phase(kube, PVC_PATH + "/disk-claim"));
    }

    @Test
    @Order(5)
    @DisplayName("scaling a pool through the agent pool API removes its highest-index node")
    void scalingAPoolRemovesNodes() throws Exception {
        Response scaled = given().contentType("application/json")
                .body("{\"properties\":{\"count\":1}}")
                .put(CLUSTER_PATH + "/agentPools/userpool" + API);
        scaled.then().statusCode(200).body("properties.provisioningState", equalTo("Scaling"));
        awaitOperation(scaled);

        JsonNode nodes = nodes();
        assertEquals(2, nodes.size(), nodes.toString());
        node(nodes, "aks-userpool-", "vmss000000");
        assertEquals(2, containers("agent").size());
    }

    @Test
    @Order(6)
    @DisplayName("stop stops the containers and start brings the same cluster back")
    void stopAndStart() {
        Response stopped = given().post(CLUSTER_PATH + "/stop" + API);
        stopped.then().statusCode(202);
        awaitLocation(stopped);
        given().get(CLUSTER_PATH + API).then().body("properties.powerState.code", equalTo("Stopped"));
        assertFalse(containers("server").isEmpty());
        assertTrue(containers("server").stream().noneMatch(c -> "running".equals(c.getState())),
                "the server container is stopped");

        Response started = given().post(CLUSTER_PATH + "/start" + API);
        started.then().statusCode(202);
        awaitLocation(started);
        given().get(CLUSTER_PATH + API).then().body("properties.powerState.code", equalTo("Running"));
        AksTestPolling.until(Duration.ofMinutes(2), () -> answers(adminKubeconfig));
    }

    @Test
    @Order(7)
    @DisplayName("a lost server container is recreated on its data volume at restore, keeping the CA")
    void restoreRecreatesALostServerOnItsVolume() {
        String lost = containers("server").getFirst().getId();
        docker.removeContainerCmd(lost).withForce(true).exec();

        handler.restorePersistedClusters();

        AksTestPolling.until(Duration.ofMinutes(3), () -> !containers("server").isEmpty()
                && !containers("server").getFirst().getId().equals(lost));
        AksTestPolling.until(Duration.ofMinutes(3), () -> answers(adminKubeconfig));
    }

    @Test
    @Order(8)
    @DisplayName("changing kubernetesVersion upgrades the server and the nodes, the old kubeconfig keeps working")
    void upgradeRecreatesTheServerOnTheNewImage() throws Exception {
        Response upgraded = given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"properties\":{\"kubernetesVersion\":\"1.36\"}}")
                .put(CLUSTER_PATH + API);
        upgraded.then().statusCode(200).body("properties.provisioningState", equalTo("Upgrading"));

        awaitOperation(upgraded);

        given().get(CLUSTER_PATH + API).then()
                .body("properties.currentKubernetesVersion", equalTo("1.36.6"))
                .body("properties.agentPoolProfiles[0].currentOrchestratorVersion", equalTo("1.36.6"));
        assertTrue(gitVersion(adminKubeconfig).startsWith("v1.36.6"), gitVersion(adminKubeconfig));
        AksTestPolling.until(Duration.ofMinutes(2), () -> {
            try {
                JsonNode nodes = nodes();
                boolean allUpgraded = nodes.size() == 2;
                for (JsonNode node : nodes) {
                    allUpgraded &= node.path("status").path("nodeInfo").path("kubeletVersion").asText()
                            .startsWith("v1.36.6");
                }
                return allUpgraded;
            } catch (Exception e) {
                return false;
            }
        });
        given().get(CLUSTER_PATH + "/upgradeProfiles/default" + API).then()
                .body("properties.controlPlaneProfile.upgrades.kubernetesVersion", contains("1.37.2"));
    }

    @Test
    @Order(99)
    @DisplayName("DELETE removes the containers, volumes and network, then the cluster is not found")
    void deleteCluster() {
        Response deleted = given().delete(CLUSTER_PATH + API);
        deleted.then().statusCode(202);
        awaitOperation(deleted);

        given().get(CLUSTER_PATH + API).then().statusCode(404);
        assertTrue(containers("server").isEmpty(), "server container removed");
        assertTrue(containers("agent").isEmpty(), "agent containers removed");
        assertTrue(docker.listNetworksCmd().exec().stream()
                .noneMatch(n -> n.getName().startsWith("floci-aks-") && NAME.equals(
                        n.getLabels() == null ? null : n.getLabels().get("io.floci.resource-id"))),
                "cluster network removed");
    }
}
