package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lifecycle and long-running operations in real (k3s) mode with the container manager mocked out, so the
 * operation can be held InProgress, released to Succeeded or forced to Failed without Docker.
 */
@QuarkusTest
@TestProfile(AksLifecycleRealModeTest.RealModeProfile.class)
@DisplayName("AksHandler: lifecycle and long-running operations (real mode, containers mocked)")
@SuppressWarnings("unused")
class AksLifecycleRealModeTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aks.mocked", "false",
                    "floci-az.services.aks.ready-timeout-seconds", "20");
        }
    }

    private static final String SUB = "test-sub-aks-lro";
    private static final String BASE = "/subscriptions/" + SUB + "/resourceGroups/rg-lro"
            + "/providers/Microsoft.ContainerService/managedClusters/";
    private static final String API = "?api-version=2025-10-01";

    @InjectMock
    AksClusterManager clusterManager;

    private final AtomicBoolean apiReady = new AtomicBoolean();

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        apiReady.set(false);
        when(clusterManager.isApiReady(any())).thenAnswer(inv -> apiReady.get());
        doAnswer(inv -> {
            ManagedCluster cluster = inv.getArgument(0);
            cluster.setContainerId("server-" + cluster.getInstanceId());
            cluster.setEndpoint("https://localhost:6500");
            cluster.setInternalEndpoint("https://localhost:6500");
            return AksClusterManager.ServerStart.CREATED_FRESH;
        }).when(clusterManager).prepareServer(any(), anyString(), anyList(), anyMap());
        doAnswer(inv -> {
            ManagedCluster cluster = inv.getArgument(0);
            cluster.setCaData("Q0E=");
            cluster.setClientCertificateData("Q0VSVA==");
            cluster.setClientKeyData("S0VZ");
            return null;
        }).when(clusterManager).extractCredentials(any());
    }

    private Response create(String name, String body) {
        return given().contentType("application/json").body(body).put(BASE + name + API);
    }

    private static String pathOf(String url) {
        URI uri = URI.create(url);
        return uri.getRawPath() + "?" + uri.getRawQuery();
    }

    private static String state(String name) {
        return given().get(BASE + name + API).path("properties.provisioningState");
    }

    private static void awaitState(String name, String expected) {
        AksTestPolling.until(Duration.ofSeconds(15), () -> expected.equals(state(name)));
    }

    @Test
    @DisplayName("PUT returns 201 Creating with an Azure-AsyncOperation that reports InProgress, then Succeeded")
    void createIsALongRunningOperationThatFollowsTheCluster() {
        Response created = create("lro1", "{\"location\":\"eastus\",\"properties\":{}}");
        created.then().statusCode(201)
                .header("Retry-After", "1")
                .body("properties.provisioningState", equalTo("Creating"));
        String operation = pathOf(created.header("Azure-AsyncOperation"));
        assertTrue(operation.contains("/locations/eastus/operations/"), operation);

        given().get(operation).then().statusCode(200)
                .body("status", equalTo("InProgress"))
                .body("startTime", notNullValue())
                .body("endTime", nullValue());

        apiReady.set(true);
        awaitState("lro1", "Succeeded");

        given().get(operation).then().statusCode(200)
                .body("status", equalTo("Succeeded"))
                .body("endTime", notNullValue());
        given().get(BASE + "lro1" + API).then()
                .body("properties.currentKubernetesVersion", equalTo("1.36.6"))
                .body("properties.kubernetesVersion", equalTo("1.36"))
                .body("properties.powerState.code", equalTo("Running"))
                .body("properties.agentPoolProfiles[0].provisioningState", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("a cluster whose kubeconfig cannot be read fails, and so does its operation")
    void aFinalizeFailureEndsFailed() {
        apiReady.set(true);
        doThrow(new IllegalStateException("could not read the k3s kubeconfig")).when(clusterManager)
                .extractCredentials(any());

        Response created = create("lro2", "{\"location\":\"eastus\",\"properties\":{}}");
        awaitState("lro2", "Failed");

        given().get(pathOf(created.header("Azure-AsyncOperation"))).then().statusCode(200)
                .body("status", equalTo("Failed"))
                .body("error.message", containsString("could not read the k3s kubeconfig"));
        given().contentType("application/json").post(BASE + "lro2/listClusterAdminCredential" + API)
                .then().statusCode(409);
    }

    @Test
    @DisplayName("a re-PUT runs the cluster again instead of sticking in Creating")
    void aRePutAppliesTheChange() {
        apiReady.set(true);
        create("lro3", "{\"location\":\"eastus\",\"properties\":{}}").then().statusCode(201);
        awaitState("lro3", "Succeeded");

        Response updated = create("lro3", "{\"location\":\"eastus\",\"properties\":{\"agentPoolProfiles\":["
                + "{\"name\":\"nodepool1\",\"count\":3,\"mode\":\"System\"}]}}");
        updated.then().statusCode(200)
                .header("Azure-AsyncOperation", notNullValue())
                .body("properties.provisioningState", equalTo("Updating"));
        awaitState("lro3", "Succeeded");

        given().get(BASE + "lro3" + API).then().body("properties.agentPoolProfiles[0].count", equalTo(3));
        verify(clusterManager, atLeast(2)).prepareServer(any(), anyString(), anyList(), anyMap());
    }

    @Test
    @DisplayName("changing kubernetesVersion recreates the server on the next minor's image")
    void aVersionChangeUpgradesTheServer() {
        apiReady.set(true);
        create("lro4", "{\"location\":\"eastus\",\"properties\":{\"kubernetesVersion\":\"1.35\"}}")
                .then().statusCode(201);
        awaitState("lro4", "Succeeded");

        create("lro4", "{\"location\":\"eastus\",\"properties\":{\"kubernetesVersion\":\"1.36\"}}")
                .then().statusCode(200)
                .body("properties.provisioningState", equalTo("Upgrading"))
                .body("properties.currentKubernetesVersion", equalTo("1.36.6"));
        awaitState("lro4", "Succeeded");

        ArgumentCaptor<String> images = ArgumentCaptor.forClass(String.class);
        verify(clusterManager, atLeast(2)).prepareServer(any(), images.capture(), anyList(), anyMap());
        List<String> seen = images.getAllValues();
        assertEquals("rancher/k3s:v1.35.10-k3s1", seen.getFirst());
        assertEquals("rancher/k3s:v1.36.6-k3s1", seen.getLast());
        given().get(BASE + "lro4" + API).then()
                .body("properties.agentPoolProfiles[0].currentOrchestratorVersion", equalTo("1.36.6"));
    }

    @Test
    @DisplayName("stop and start are final-state-via-location operations that set powerState")
    void stopAndStartChangeThePowerState() {
        apiReady.set(true);
        create("lro5", "{\"location\":\"eastus\",\"properties\":{}}").then().statusCode(201);
        awaitState("lro5", "Succeeded");

        Response stopped = given().post(BASE + "lro5/stop" + API);
        stopped.then().statusCode(202).header("Location", containsString("/operationresults/"));
        awaitState("lro5", "Succeeded");
        given().get(pathOf(stopped.header("Location"))).then().statusCode(204);
        given().get(BASE + "lro5" + API).then()
                .body("properties.powerState.code", equalTo("Stopped"))
                .body("properties.agentPoolProfiles[0].powerState.code", equalTo("Stopped"));
        verify(clusterManager, timeout(5000)).stopContainers(any(), anyList());

        given().post(BASE + "lro5/start" + API).then().statusCode(202);
        awaitState("lro5", "Succeeded");
        given().get(BASE + "lro5" + API).then().body("properties.powerState.code", equalTo("Running"));
    }

    @Test
    @DisplayName("DELETE shows Deleting until the containers are gone, then the cluster is not found")
    void deleteRemovesTheContainersThenTheRecord() {
        apiReady.set(true);
        create("lro6", "{\"location\":\"eastus\",\"properties\":{}}").then().statusCode(201);
        awaitState("lro6", "Succeeded");

        Response deleted = given().delete(BASE + "lro6" + API);
        deleted.then().statusCode(202);
        AksTestPolling.until(Duration.ofSeconds(10), () -> given().get(BASE + "lro6" + API).statusCode() == 404);
        verify(clusterManager, timeout(5000)).removeCluster(any());
        given().get(pathOf(deleted.header("Azure-AsyncOperation"))).then().body("status", equalTo("Succeeded"));
    }

    @Test
    @DisplayName("a cluster stuck before /readyz fails once the ready timeout passes")
    void aClusterThatNeverBecomesReadyFails() {
        create("lro7", "{\"location\":\"eastus\",\"properties\":{}}").then().statusCode(201);

        AksTestPolling.until(Duration.ofSeconds(40), () -> "Failed".equals(state("lro7")));
    }
}
