package io.floci.az.services.aks;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Kubernetes versions, upgrade profiles and the version rules AKS enforces on PUT, plus the long-running-operation
 * headers every write hands out (mocked mode: no container runs, so operations finish at once).
 */
@QuarkusTest
@TestProfile(AksHandlerTest.MockedProfile.class)
@DisplayName("AksHandler: versions, upgrade profiles and operations (mocked mode)")
class AksVersionsApiTest {

    private static final String SUB = "test-sub-aks-versions";
    private static final String CLUSTERS = "/subscriptions/" + SUB + "/resourceGroups/rg-v"
            + "/providers/Microsoft.ContainerService/managedClusters/";
    private static final String API = "?api-version=2025-10-01";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static Response put(String name, String version) {
        String props = version == null ? "{}" : "{\"kubernetesVersion\":\"" + version + "\"}";
        return given().contentType("application/json").body("{\"location\":\"eastus\",\"properties\":" + props + "}")
                .put(CLUSTERS + name + API);
    }

    private static String pathOf(String url) {
        URI uri = URI.create(url);
        return uri.getRawPath() + "?" + uri.getRawQuery();
    }

    @Test
    @DisplayName("kubernetesVersions lists the runnable minors, their patch, its upgrades and the default")
    void kubernetesVersionsListsWhatTheEmulatorRuns() {
        given().get("/subscriptions/" + SUB + "/providers/Microsoft.ContainerService/locations/eastus"
                        + "/kubernetesVersions" + API)
                .then().statusCode(200)
                .body("values.version", contains("1.34", "1.35", "1.36", "1.37"))
                .body("values.find { it.version == '1.36' }.isDefault", equalTo(true))
                .body("values.find { it.version == '1.35' }.patchVersions.'1.35.10'.upgrades", contains("1.36.6"))
                .body("values.find { it.version == '1.37' }.patchVersions.'1.37.2'.upgrades", empty())
                .body("values[0].capabilities.supportPlan", contains("KubernetesOfficial"));
    }

    @Test
    @DisplayName("PUT without a version runs the default minor and reports its patch")
    void noVersionMeansTheDefault() {
        put("v1", null).then().statusCode(201)
                .header("Azure-AsyncOperation", containsString("/operations/"))
                .body("properties.kubernetesVersion", equalTo("1.36"))
                .body("properties.currentKubernetesVersion", equalTo("1.36.6"))
                .body("properties.agentPoolProfiles[0].orchestratorVersion", equalTo("1.36"))
                .body("properties.agentPoolProfiles[0].currentOrchestratorVersion", equalTo("1.36.6"));
    }

    @Test
    @DisplayName("an unsupported version fails with K8sVersionNotSupported")
    void anUnsupportedVersionIsRefused() {
        put("v2", "1.29").then().statusCode(400)
                .body("error.code", equalTo("K8sVersionNotSupported"))
                .body("error.message", equalTo("Managed cluster v2 is on version 1.29 which is not supported in this"
                        + " region. Please use [az aks get-versions] command to get the supported version list in this"
                        + " region. For more information, please check https://aka.ms/supported-version-list"));
        given().get(CLUSTERS + "v2" + API).then().statusCode(404);
    }

    @Test
    @DisplayName("an upgrade that skips a minor or goes down is not allowed")
    void upgradesGoOneMinorAtATime() {
        put("v3", "1.35").then().statusCode(201);

        put("v3", "1.37").then().statusCode(400)
                .body("error.code", equalTo("OperationNotAllowed"))
                .body("error.message", equalTo("Upgrading Kubernetes version 1.35.10 to 1.37.2 is not allowed."
                        + " Available upgrades: [1.36.6]. Please see https://aka.ms/aks-supported-k8s-ver for version"
                        + " details."));
        put("v3", "1.34").then().statusCode(400).body("error.code", equalTo("OperationNotAllowed"));
        put("v3", "1.36").then().statusCode(200).body("properties.currentKubernetesVersion", equalTo("1.36.6"));
    }

    @Test
    @DisplayName("the same minor requested again is no upgrade")
    void theSameMinorIsNoUpgrade() {
        put("v4", "1.36").then().statusCode(201);
        put("v4", "1.36.6").then().statusCode(200).body("properties.currentKubernetesVersion", equalTo("1.36.6"));
    }

    @Test
    @DisplayName("upgradeProfiles/default names the next minor for the control plane and each pool")
    void clusterUpgradeProfile() {
        put("v5", "1.35").then().statusCode(201);

        given().get(CLUSTERS + "v5/upgradeProfiles/default" + API).then().statusCode(200)
                .body("name", equalTo("default"))
                .body("type", equalTo("Microsoft.ContainerService/managedClusters/upgradeprofiles"))
                .body("properties.controlPlaneProfile.kubernetesVersion", equalTo("1.35.10"))
                .body("properties.controlPlaneProfile.osType", equalTo("Linux"))
                .body("properties.controlPlaneProfile.upgrades.kubernetesVersion", contains("1.36.6"))
                .body("properties.agentPoolProfiles[0].name", equalTo("nodepool1"))
                .body("properties.agentPoolProfiles[0].kubernetesVersion", equalTo("1.35.10"));
    }

    @Test
    @DisplayName("an agent pool may not run ahead of the control plane")
    void aPoolAheadOfTheControlPlaneIsRefused() {
        put("v6", "1.35").then().statusCode(201);

        given().contentType("application/json")
                .body("{\"properties\":{\"count\":1,\"mode\":\"User\",\"orchestratorVersion\":\"1.36\"}}")
                .put(CLUSTERS + "v6/agentPools/userpool" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("NodePoolMcVersionIncompatible"))
                .body("error.message", equalTo("Node pool version 1.36.6 and control plane version 1.35.10 are"
                        + " incompatible. Minor version of node pool version 36 is bigger than control plane version"
                        + " 35. For more information, please check https://aka.ms/aks/UpgradeVersionRules"));
        given().contentType("application/json")
                .body("{\"properties\":{\"count\":1,\"mode\":\"User\",\"orchestratorVersion\":\"1.34\"}}")
                .put(CLUSTERS + "v6/agentPools/userpool" + API)
                .then().statusCode(201)
                .header("Azure-AsyncOperation", containsString("/operations/"))
                .body("properties.currentOrchestratorVersion", equalTo("1.34.13"));
    }

    @Test
    @DisplayName("an agent pool version the emulator has no image for fails with AgentPoolK8sVersionNotSupported")
    void anUnsupportedPoolVersionIsRefused() {
        put("v7", "1.36").then().statusCode(201);

        given().contentType("application/json")
                .body("{\"properties\":{\"count\":1,\"orchestratorVersion\":\"1.30\"}}")
                .put(CLUSTERS + "v7/agentPools/userpool" + API)
                .then().statusCode(400).body("error.code", equalTo("AgentPoolK8sVersionNotSupported"))
                .body("error.message", equalTo("Version 1.30 is not supported in this region. Please use"
                        + " [az aks get-versions] command to get the supported version list in this region. For more"
                        + " information, please check https://aka.ms/supported-version-list"));
    }

    @Test
    @DisplayName("the agent pool upgrade profile lists versions up to the control plane")
    void agentPoolUpgradeProfile() {
        put("v8", "1.36").then().statusCode(201);
        given().contentType("application/json")
                .body("{\"properties\":{\"count\":1,\"mode\":\"User\",\"orchestratorVersion\":\"1.34\"}}")
                .put(CLUSTERS + "v8/agentPools/userpool" + API).then().statusCode(201);

        given().get(CLUSTERS + "v8/agentPools/userpool/upgradeProfiles/default" + API).then().statusCode(200)
                .body("properties.kubernetesVersion", equalTo("1.34.13"))
                .body("properties.upgrades.kubernetesVersion", contains("1.35.10", "1.36.6"));
        given().get(CLUSTERS + "v8/availableAgentPoolVersions" + API).then().statusCode(200)
                .body("properties.agentPoolVersions.kubernetesVersion",
                        contains("1.34.13", "1.35.10", "1.36.6", "1.37.2"));
    }

    @Test
    @DisplayName("the managed-cluster API refuses a per-pool version change")
    void aPerPoolVersionChangeThroughTheClusterIsRefused() {
        put("v9", "1.36").then().statusCode(201);

        given().contentType("application/json").body("{\"location\":\"eastus\",\"properties\":{"
                        + "\"kubernetesVersion\":\"1.36\",\"agentPoolProfiles\":[{\"name\":\"nodepool1\","
                        + "\"orchestratorVersion\":\"1.35\"}]}}")
                .put(CLUSTERS + "v9" + API)
                .then().statusCode(400)
                .body("error.code", equalTo("NotAllAgentPoolOrchestratorVersionSpecifiedAndUnchanged"));
    }

    @Test
    @DisplayName("a re-PUT keeps agent pools it does not list")
    void aRePutKeepsUnlistedPools() {
        put("v10", null).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\":{\"count\":2,\"mode\":\"User\"}}")
                .put(CLUSTERS + "v10/agentPools/userpool" + API).then().statusCode(201);

        put("v10", null).then().statusCode(200)
                .body("properties.agentPoolProfiles.name", containsInAnyOrder("nodepool1", "userpool"));
    }

    @Test
    @DisplayName("stop and start hand out a Location monitor and set powerState")
    void stopAndStart() {
        put("v11", null).then().statusCode(201);

        Response stopped = given().post(CLUSTERS + "v11/stop" + API);
        stopped.then().statusCode(202).header("Location", containsString("/operationresults/"));
        given().get(pathOf(stopped.header("Location"))).then().statusCode(204);
        given().get(CLUSTERS + "v11" + API).then().body("properties.powerState.code", equalTo("Stopped"));

        given().post(CLUSTERS + "v11/start" + API).then().statusCode(202);
        given().get(CLUSTERS + "v11" + API).then().body("properties.powerState.code", equalTo("Running"));
    }

    @Test
    @DisplayName("deleting an agent pool is a long-running operation; a missing pool is 204")
    void agentPoolDelete() {
        put("v12", null).then().statusCode(201);
        given().contentType("application/json").body("{\"properties\":{\"count\":1,\"mode\":\"User\"}}")
                .put(CLUSTERS + "v12/agentPools/userpool" + API).then().statusCode(201);

        given().delete(CLUSTERS + "v12/agentPools/userpool" + API).then().statusCode(202)
                .header("Azure-AsyncOperation", containsString("/operations/"))
                .header("Location", containsString("/operationresults/"));
        given().get(CLUSTERS + "v12/agentPools/userpool" + API).then().statusCode(404);
        given().delete(CLUSTERS + "v12/agentPools/userpool" + API).then().statusCode(204);
    }
}
