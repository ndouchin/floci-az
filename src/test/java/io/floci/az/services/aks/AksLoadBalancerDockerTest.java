package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Services of type LoadBalancer on real k3s: floci-az writes an Azure-shaped address, runs an appliance holding it,
 * publishes its ports on the host, and cleans up after the Service and the cluster. Skipped without Docker.
 */
@QuarkusTest
@TestProfile(AksLoadBalancerDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AKS LoadBalancer Services on real k3s (Docker required)")
class AksLoadBalancerDockerTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aks.mocked", "false");
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SUB = "test-sub-aks-lb";
    private static final String RG = "test-rg-lb";
    private static final String NAME = "lb-cluster";
    private static final String API = "?api-version=2026-08-01";
    private static final String CLUSTER_PATH = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.ContainerService/managedClusters/" + NAME;
    private static final String BUSYBOX = "rancher/mirrored-library-busybox:1.37.0";

    @Inject
    DockerClient docker;

    @Inject
    AksHandler handler;

    private String adminKubeconfig;
    private String instanceId;
    private String publicAddress;

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

    private KubeconfigClient admin() throws Exception {
        return KubeconfigClient.fromBase64(adminKubeconfig);
    }

    private JsonNode service(String name) throws Exception {
        return admin().get("/api/v1/namespaces/default/services/" + name);
    }

    private String ingressIp(String name) {
        try {
            return service(name).path("status").path("loadBalancer").path("ingress").path(0).path("ip").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private List<Container> appliances() {
        return docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(AksClusterManager.ROLE_LABEL, "load-balancer")).exec();
    }

    private void createService(String name, String annotations) throws Exception {
        assertEquals(201, admin().send("POST", "/api/v1/namespaces/default/services", """
                {"apiVersion": "v1", "kind": "Service", "metadata": {"name": "%s", "annotations": {%s}},
                 "spec": {"type": "LoadBalancer", "selector": {"app": "web"},
                          "ports": [{"port": 80, "targetPort": 8080, "protocol": "TCP"}]}}"""
                .formatted(name, annotations)));
    }

    @Test
    @Order(1)
    @DisplayName("a cluster and a web Deployment")
    void createClusterAndDeployment() throws Exception {
        given().post("/_admin/reset").then().statusCode(204);
        Response created = given().contentType("application/json").body("""
                {"location": "eastus",
                 "properties": {"kubernetesVersion": "1.36", "dnsPrefix": "lb",
                   "agentPoolProfiles": [{"name": "nodepool1", "count": 1, "vmSize": "Standard_DS2_v2",
                                          "mode": "System"}]}}""").put(CLUSTER_PATH + API);
        created.then().statusCode(201);
        URI uri = URI.create(created.header("Azure-AsyncOperation"));
        String operation = uri.getRawPath() + "?" + uri.getRawQuery();
        AksTestPolling.until(Duration.ofMinutes(5), () -> !"InProgress".equals(given().get(operation).path("status")));
        given().get(operation).then().body("status", equalTo("Succeeded"));
        adminKubeconfig = given().post(CLUSTER_PATH + "/listClusterAdminCredential" + API)
                .then().statusCode(200).extract().path("kubeconfigs[0].value");
        instanceId = handler.findCluster(SUB, RG, NAME).orElseThrow().getInstanceId();
        assertEquals(201, admin().send("POST", "/apis/apps/v1/namespaces/default/deployments", """
                {"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": "web"},
                 "spec": {"replicas": 1, "selector": {"matchLabels": {"app": "web"}},
                   "template": {"metadata": {"labels": {"app": "web"}},
                     "spec": {"containers": [{"name": "web", "image": "%s",
                       "command": ["sh", "-c", "mkdir -p /www && echo hello-aks > /www/index.html && httpd -f -p 8080 -h /www"],
                       "ports": [{"containerPort": 8080}]}]}}}}""".formatted(BUSYBOX)));
    }

    @Test
    @Order(2)
    @DisplayName("a LoadBalancer Service gets a public address from 198.51.100.0/24 within 30 s")
    void aPublicAddress() throws Exception {
        createService("web", "");
        AksTestPolling.until(Duration.ofSeconds(30), () -> ingressIp("web").startsWith("198.51.100."));
        publicAddress = ingressIp("web");
        assertTrue(service("web").path("metadata").path("finalizers").toString()
                .contains("service.kubernetes.io/load-balancer-cleanup"));
    }

    @Test
    @Order(3)
    @DisplayName("the internal annotation gives an address on the cluster network")
    void anInternalAddress() throws Exception {
        createService("web-internal", "\"service.beta.kubernetes.io/azure-load-balancer-internal\": \"true\"");
        AksTestPolling.until(Duration.ofSeconds(30), () -> !ingressIp("web-internal").isEmpty());
        String address = ingressIp("web-internal");
        String subnet = docker.inspectNetworkCmd().withNetworkId("floci-aks-" + instanceId).exec()
                .getIpam().getConfig().getFirst().getSubnet();
        int prefix = Integer.parseInt(subnet.substring(subnet.indexOf('/') + 1));
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        assertEquals(AksLoadBalancerAddresses.toLong(subnet.substring(0, subnet.indexOf('/'))) & mask,
                AksLoadBalancerAddresses.toLong(address) & mask, address + " not in " + subnet);
        assertTrue(!address.startsWith("198.51.100."), address);
    }

    @Test
    @Order(4)
    @DisplayName("the address survives scaling the Deployment")
    void theAddressSurvivesScaling() throws Exception {
        assertEquals(200, patchReplicas());
        Thread.sleep(8000);
        assertEquals(publicAddress, ingressIp("web"));
    }

    private int patchReplicas() throws Exception {
        return admin().send("PUT", "/apis/apps/v1/namespaces/default/deployments/web/scale",
                "{\"apiVersion\": \"autoscaling/v1\", \"kind\": \"Scale\", \"metadata\": {\"name\": \"web\", "
                        + "\"namespace\": \"default\"}, \"spec\": {\"replicas\": 2}}");
    }

    @Test
    @Order(5)
    @DisplayName("HTTP through the published host port answers 200")
    void theHostPortAnswers() throws Exception {
        AksTestPolling.until(Duration.ofSeconds(30), () -> {
            try {
                return service("web").path("metadata").path("annotations")
                        .has(AksLoadBalancerSpec.HOST_PORTS_ANNOTATION);
            } catch (Exception e) {
                return false;
            }
        });
        String annotation = service("web").path("metadata").path("annotations")
                .path(AksLoadBalancerSpec.HOST_PORTS_ANNOTATION).asText();
        assertTrue(annotation.startsWith("TCP/80=127.0.0.1:"), annotation);
        int port = Integer.parseInt(annotation.substring(annotation.lastIndexOf(':') + 1));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        AksTestPolling.until(Duration.ofSeconds(60), () -> {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/")).timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 && response.body().contains("hello-aks");
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    @Order(6)
    @DisplayName("a pod reaches the load balancer address")
    void aPodReachesTheAddress() throws Exception {
        assertEquals(201, admin().send("POST", "/api/v1/namespaces/default/pods", MAPPER.writeValueAsString(
                Map.of("apiVersion", "v1", "kind", "Pod", "metadata", Map.of("name", "probe"),
                        "spec", Map.of("restartPolicy", "Never", "containers", List.of(Map.of("name", "probe",
                                "image", BUSYBOX, "command", List.of("sh", "-c", "for i in $(seq 1 30); do wget -qO- "
                                        + "http://" + publicAddress + "/ && exit 0; sleep 2; done; exit 1"))))))));
        AksTestPolling.until(Duration.ofMinutes(2), () -> {
            try {
                return "Succeeded".equals(admin().get("/api/v1/namespaces/default/pods/probe").path("status")
                        .path("phase").asText());
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(admin().text("/api/v1/namespaces/default/pods/probe/log").contains("hello-aks"));
    }

    @Test
    @Order(7)
    @DisplayName("deleting the Service removes its appliance and lets the finalizer go")
    void deletingTheServiceCleansUp() throws Exception {
        String uid = service("web").path("metadata").path("uid").asText();
        assertEquals(200, admin().send("DELETE", "/api/v1/namespaces/default/services/web", null));
        AksTestPolling.until(Duration.ofSeconds(30), () -> {
            try {
                return admin().exchange("GET", "/api/v1/namespaces/default/services/web", null).status() == 404;
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(appliances().stream().noneMatch(c -> uid.equals(c.getLabels().get(AksLoadBalancerAppliances
                .SERVICE_LABEL))));
    }

    @Test
    @Order(8)
    @DisplayName("deleting the cluster removes every appliance and the public network")
    void deletingTheClusterCleansUp() {
        given().delete(CLUSTER_PATH + API).then().statusCode(202);
        AksTestPolling.until(Duration.ofMinutes(2), () -> given().get(CLUSTER_PATH + API).statusCode() == 404);
        AksTestPolling.until(Duration.ofSeconds(30), () -> appliances().isEmpty());
        AksTestPolling.until(Duration.ofSeconds(30), () -> {
            try {
                docker.inspectNetworkCmd().withNetworkId("floci-az-aks-lb-public").exec();
                return false;
            } catch (NotFoundException gone) {
                return true;
            }
        });
    }
}
