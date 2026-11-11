package io.floci.az.services.aro;

import io.floci.az.services.aro.AroModels.OpenShiftCluster;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static io.floci.az.services.aro.AroHandlerTest.API;
import static io.floci.az.services.aro.AroHandlerTest.PROVIDER;
import static io.floci.az.services.aro.AroHandlerTest.fullBody;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cluster lifecycle in real mode with the MicroShift manager mocked out: {@code Creating} until the cluster is
 * ready, the asynchronous operation following the cluster's state, and what ARO refuses meanwhile.
 */
@QuarkusTest
@TestProfile(AroHandlerRealModeTest.RealModeProfile.class)
@DisplayName("AroHandler: lifecycle and async operations (real mode, manager mocked)")
class AroHandlerRealModeTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-az.services.aro.mocked", "false",
                    "floci-az.services.aro.ready-timeout-seconds", "8");
        }
    }

    @InjectMock
    AroClusterManager clusterManager;

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
        clearInvocations(clusterManager);
        doAnswer(invocation -> {
            OpenShiftCluster cluster = invocation.getArgument(0);
            cluster.setContainerId("container-" + cluster.getInstanceId());
            cluster.setEndpoint("https://localhost:7601");
            cluster.setInternalEndpoint("https://localhost:7601");
            cluster.setPublicIp("127.0.0.1");
            return null;
        }).when(clusterManager).startCluster(any());
        doAnswer(invocation -> {
            OpenShiftCluster cluster = invocation.getArgument(0);
            cluster.setKubeconfig(Base64.getEncoder().encodeToString(
                    "server: https://localhost:7601\n".getBytes(StandardCharsets.UTF_8)));
            return null;
        }).when(clusterManager).finalizeCluster(any());
    }

    private Response create(String name) {
        return given().contentType("application/json").body(fullBody(name)).when().put(PROVIDER + "/" + name + API);
    }

    private static String operationPath(String header) {
        return URI.create(header).getPath() + API;
    }

    private static String awaitState(String name, String expected) throws InterruptedException {
        String state = null;
        for (int i = 0; i < 40 && !expected.equals(state); i++) {
            state = given().when().get(PROVIDER + "/" + name + API).path("properties.provisioningState");
            if (!expected.equals(state)) {
                Thread.sleep(500);
            }
        }
        return state;
    }

    @Test
    void aNewClusterIsCreatingAndItsOperationSaysSo() {
        Response created = create("creating");
        created.then().statusCode(201).body("properties.provisioningState", equalTo("Creating"));
        given().when().get(operationPath(created.header("Azure-AsyncOperation"))).then().statusCode(200)
                .body("status", equalTo("Creating"))
                .body("endTime", nullValue());
        verify(clusterManager, timeout(5000)).startCluster(any());
    }

    @Test
    void aCreatingClusterRefusesCredentialsUpdatesAndDeletes() {
        create("busy").then().statusCode(201);
        given().contentType("application/json").when().post(PROVIDER + "/busy/listCredentials" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("RequestNotAllowed"))
                .body("error.message", equalTo("Request is not allowed in provisioningState 'Creating'."));
        given().contentType("application/json").when().post(PROVIDER + "/busy/listAdminCredentials" + API)
                .then().statusCode(400).body("error.code", equalTo("RequestNotAllowed"));
        given().contentType("application/json").body("{\"tags\":{}}").when().patch(PROVIDER + "/busy" + API)
                .then().statusCode(400).body("error.code", equalTo("RequestNotAllowed"));
        given().when().delete(PROVIDER + "/busy" + API).then().statusCode(400)
                .body("error.code", equalTo("RequestNotAllowed"));
    }

    @Test
    void aReadyClusterSucceedsAndServesItsKubeconfig() throws InterruptedException {
        when(clusterManager.isReady(any())).thenReturn(true);
        Response created = create("ready");
        created.then().statusCode(201);
        assertEquals("Succeeded", awaitState("ready", "Succeeded"));
        given().when().get(operationPath(created.header("Azure-AsyncOperation"))).then()
                .body("status", equalTo("Succeeded"))
                .body("endTime", notNullValue());
        given().when().get(PROVIDER + "/ready" + API).then()
                .body("properties.apiserverProfile.url", equalTo("https://localhost:7601/"))
                .body("properties.apiserverProfile.ip", equalTo("127.0.0.1"));
        String kubeconfig = given().contentType("application/json")
                .when().post(PROVIDER + "/ready/listAdminCredentials" + API).then().statusCode(200)
                .extract().path("kubeconfig");
        assertEquals("server: https://localhost:7601\n",
                new String(Base64.getDecoder().decode(kubeconfig), StandardCharsets.UTF_8));
    }

    @Test
    void anUpdateIsUpdatingUntilThePollerCompletesIt() throws InterruptedException {
        when(clusterManager.isReady(any())).thenReturn(true);
        create("upd").then().statusCode(201);
        assertEquals("Succeeded", awaitState("upd", "Succeeded"));
        Response patched = given().contentType("application/json").body("{\"tags\":{\"a\":\"b\"}}")
                .when().patch(PROVIDER + "/upd" + API);
        patched.then().statusCode(200).body("properties.provisioningState", equalTo("Updating"));
        assertEquals("Succeeded", awaitState("upd", "Succeeded"));
        given().when().get(operationPath(patched.header("Azure-AsyncOperation"))).then()
                .body("status", equalTo("Succeeded"));
    }

    @Test
    void aClusterWhoseContainerCannotStartFails() throws InterruptedException {
        doThrow(new IllegalStateException("docker unavailable")).when(clusterManager).startCluster(any());
        Response created = create("broken");
        created.then().statusCode(201);
        assertEquals("Failed", awaitState("broken", "Failed"));
        given().when().get(operationPath(created.header("Azure-AsyncOperation"))).then()
                .body("status", equalTo("Failed"))
                .body("error.code", equalTo("InternalServerError"))
                .body("error.message", containsString("docker unavailable"));
        given().contentType("application/json").body(fullBody("broken")).when().put(PROVIDER + "/broken" + API)
                .then().statusCode(400)
                .body("error.message", equalTo("Request is not allowed on cluster whose creation failed. Delete the cluster."));
        given().contentType("application/json").when().post(PROVIDER + "/broken/listCredentials" + API)
                .then().statusCode(400).body("error.message", containsString("'Failed'"));
        given().when().delete(PROVIDER + "/broken" + API).then().statusCode(202);
        verify(clusterManager, timeout(5000)).removeCluster(any());
    }

    @Test
    void aClusterThatNeverBecomesReadyFailsAfterTheTimeout() throws InterruptedException {
        Response created = create("slow");
        created.then().statusCode(201);
        assertEquals("Failed", awaitState("slow", "Failed"));
        given().when().get(operationPath(created.header("Azure-AsyncOperation"))).then()
                .body("status", equalTo("Failed"))
                .body("error.message", equalTo("The cluster did not become ready within 8 seconds."));
    }

    @Test
    void deletingRemovesTheContainerBeforeTheOperationSucceeds() throws InterruptedException {
        when(clusterManager.isReady(any())).thenReturn(true);
        create("gone").then().statusCode(201);
        assertEquals("Succeeded", awaitState("gone", "Succeeded"));
        Response deleted = given().when().delete(PROVIDER + "/gone" + API);
        deleted.then().statusCode(202);
        verify(clusterManager, timeout(5000)).removeCluster(any());
        String status = null;
        for (int i = 0; i < 20 && !"Succeeded".equals(status); i++) {
            status = given().when().get(operationPath(deleted.header("Azure-AsyncOperation"))).path("status");
            Thread.sleep(250);
        }
        assertEquals("Succeeded", status);
        given().when().get(PROVIDER + "/gone" + API).then().statusCode(404);
        given().when().get(operationPath(deleted.header("Location"))).then().statusCode(204);
    }
}
