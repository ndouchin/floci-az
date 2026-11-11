package io.floci.az.services.aro;

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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code Microsoft.RedHatOpenShift} ARM surface in mocked mode (no MicroShift container): request and response
 * shapes after the 2025-07-25 spec and examples, and the ARO resource provider's validation and error answers.
 */
@QuarkusTest
@TestProfile(AroHandlerTest.MockedProfile.class)
@DisplayName("AroHandler: ARM surface (mocked mode)")
class AroHandlerTest {

    public static class MockedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aro.mocked", "true");
        }
    }

    static final String SUB = "00000000-0000-0000-0000-0000000000a1";
    static final String RG = "aro-rg";
    static final String API = "?api-version=2025-07-25";
    static final String PROVIDER = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.RedHatOpenShift/openShiftClusters";
    static final String CLIENT_ID = "11111111-2222-3333-4444-555555555555";

    static String fullBody(String domain) {
        return """
            {
              "location": "eastus",
              "tags": {"env": "test"},
              "properties": {
                "clusterProfile": {
                  "domain": "%s",
                  "version": "4.19.7",
                  "pullSecret": "{\\"auths\\":{}}",
                  "resourceGroupId": "/subscriptions/%s/resourceGroups/aro-infra",
                  "fipsValidatedModules": "Disabled"
                },
                "servicePrincipalProfile": {"clientId": "%s", "clientSecret": "s3cret"},
                "networkProfile": {"podCidr": "10.128.0.0/14", "serviceCidr": "172.30.0.0/16"},
                "masterProfile": {
                  "vmSize": "Standard_D8s_v5",
                  "subnetId": "/subscriptions/%s/resourceGroups/vnet-rg/providers/Microsoft.Network/virtualNetworks/vnet/subnets/master"
                },
                "workerProfiles": [{
                  "name": "worker", "vmSize": "Standard_D4s_v5", "diskSizeGB": 128, "count": 3,
                  "subnetId": "/subscriptions/%s/resourceGroups/vnet-rg/providers/Microsoft.Network/virtualNetworks/vnet/subnets/worker"
                }],
                "apiserverProfile": {"visibility": "Public"},
                "ingressProfiles": [{"name": "default", "visibility": "Public"}]
              }
            }
            """.formatted(domain, SUB, CLIENT_ID, SUB, SUB);
    }

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private Response put(String name, String body) {
        return given().contentType("application/json").body(body).when().put(PROVIDER + "/" + name + API);
    }

    @Test
    void creatingAClusterAnswers201WithAnAsyncOperationAndTheClusterShape() {
        Response response = put("cluster1", fullBody("mydomain"));
        response.then().statusCode(201)
                .header("Azure-AsyncOperation", containsString("/subscriptions/" + SUB
                        + "/providers/Microsoft.RedHatOpenShift/locations/eastus/operationsstatus/"))
                .header("Azure-AsyncOperation", endsWith("?api-version=2025-07-25"))
                .body("id", equalTo(PROVIDER + "/cluster1"))
                .body("name", equalTo("cluster1"))
                .body("type", equalTo("Microsoft.RedHatOpenShift/openShiftClusters"))
                .body("location", equalTo("eastus"))
                .body("tags.env", equalTo("test"))
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.clusterProfile.domain", equalTo("mydomain"))
                .body("properties.clusterProfile.version", equalTo("4.19.7"))
                .body("properties.clusterProfile.resourceGroupId",
                        equalTo("/subscriptions/" + SUB + "/resourcegroups/aro-infra"))
                .body("properties.clusterProfile.fipsValidatedModules", equalTo("Disabled"))
                .body("properties.clusterProfile.pullSecret", nullValue())
                .body("properties.consoleProfile.url",
                        equalTo("https://console-openshift-console.apps.mydomain.eastus.aroapp.io/"))
                .body("properties.servicePrincipalProfile.clientId", equalTo(CLIENT_ID))
                .body("properties.servicePrincipalProfile.clientSecret", nullValue())
                .body("properties.networkProfile.podCidr", equalTo("10.128.0.0/14"))
                .body("properties.networkProfile.serviceCidr", equalTo("172.30.0.0/16"))
                .body("properties.networkProfile.outboundType", equalTo("Loadbalancer"))
                .body("properties.networkProfile.preconfiguredNSG", equalTo("Disabled"))
                .body("properties.networkProfile.loadBalancerProfile.managedOutboundIps.count", equalTo(1))
                .body("properties.masterProfile.vmSize", equalTo("Standard_D8s_v5"))
                .body("properties.masterProfile.encryptionAtHost", equalTo("Disabled"))
                .body("properties.workerProfiles[0].count", equalTo(3))
                .body("properties.workerProfiles[0].encryptionAtHost", equalTo("Disabled"))
                .body("properties.workerProfilesStatus", nullValue())
                .body("properties.apiserverProfile.visibility", equalTo("Public"))
                .body("properties.apiserverProfile.url", equalTo("https://api.mydomain.eastus.aroapp.io:6443/"))
                .body("properties.apiserverProfile.ip", equalTo("127.0.0.1"))
                .body("properties.ingressProfiles[0].name", equalTo("default"))
                .body("properties.ingressProfiles[0].ip", equalTo("127.0.0.1"));
    }

    @Test
    void aMinimalBodyGetsTheDefaultsAzCliSends() {
        put("minimal", "{\"location\":\"westeurope\",\"properties\":{}}").then().statusCode(201)
                .body("properties.clusterProfile.domain", matchesPattern("[a-z][a-z0-9]{7}"))
                .body("properties.clusterProfile.version", equalTo("4.19.0"))
                .body("properties.clusterProfile.resourceGroupId",
                        startsWith("/subscriptions/" + SUB + "/resourcegroups/aro-"))
                .body("properties.networkProfile.podCidr", equalTo("10.128.0.0/14"))
                .body("properties.networkProfile.serviceCidr", equalTo("172.30.0.0/16"))
                .body("properties.masterProfile.vmSize", equalTo("Standard_D8s_v5"))
                .body("properties.workerProfiles[0].name", equalTo("worker"))
                .body("properties.workerProfiles[0].vmSize", equalTo("Standard_D4s_v5"))
                .body("properties.workerProfiles[0].diskSizeGB", equalTo(128))
                .body("properties.workerProfiles[0].count", equalTo(3))
                .body("properties.apiserverProfile.visibility", equalTo("Public"))
                .body("properties.ingressProfiles[0].visibility", equalTo("Public"))
                .body("properties.consoleProfile.url", endsWith(".westeurope.aroapp.io/"));
    }

    @Test
    void aCustomDomainWithDotsIsUsedAsItIs() {
        put("custom", fullBody("apps.example.com")).then().statusCode(201)
                .body("properties.clusterProfile.domain", equalTo("apps.example.com"))
                .body("properties.consoleProfile.url",
                        equalTo("https://console-openshift-console.apps.apps.example.com/"))
                .body("properties.apiserverProfile.url", equalTo("https://api.apps.example.com:6443/"));
    }

    @Test
    void getReturnsTheClusterWithWorkerProfilesStatus() {
        put("cluster1", fullBody("mydomain")).then().statusCode(201);
        given().when().get(PROVIDER + "/cluster1" + API).then().statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.workerProfilesStatus", hasSize(3))
                .body("properties.workerProfilesStatus[0].name", matchesPattern("cluster1-[a-z0-9]{5}-worker-eastus1"))
                .body("properties.workerProfilesStatus.count", contains(1, 1, 1))
                .body("properties.workerProfilesStatus[2].name", endsWith("-worker-eastus3"));
    }

    @Test
    void anOlderApiVersionIsServed() {
        given().contentType("application/json").body(fullBody("olddomain"))
                .when().put(PROVIDER + "/old?api-version=2023-11-22").then().statusCode(201);
        given().when().get(PROVIDER + "/old?api-version=2022-09-04").then().statusCode(200)
                .body("name", equalTo("old"));
    }

    @Test
    void anUnknownClusterAnswersArosResourceNotFound() {
        given().when().get(PROVIDER + "/Missing" + API).then().statusCode(404)
                .body("error.code", equalTo("ResourceNotFound"))
                .body("error.message",
                        equalTo("The Resource 'openshiftclusters/missing' under resource group 'aro-rg' was not found."));
    }

    @Test
    void clustersAreListedByResourceGroupAndSubscription() {
        put("one", fullBody("one")).then().statusCode(201);
        put("two", fullBody("two")).then().statusCode(201);
        given().when().get(PROVIDER + API).then().statusCode(200).body("value.name", containsInAnyOrder("one", "two"));
        given().when().get("/subscriptions/" + SUB + "/providers/Microsoft.RedHatOpenShift/openShiftClusters" + API)
                .then().statusCode(200).body("value.name", hasItems("one", "two"));
        given().when().get("/subscriptions/" + SUB + "/resourceGroups/" + RG + "/resources?api-version=2021-04-01")
                .then().statusCode(200).body("value.type", hasItem("Microsoft.RedHatOpenShift/openShiftClusters"));
    }

    @Test
    void anUnsupportedVersionIsRefusedLikeAro() {
        put("v418", fullBody("v418").replace("4.19.7", "4.18.3")).then().statusCode(400)
                .body("error.code", equalTo("InvalidParameter"))
                .body("error.target", equalTo("properties.clusterProfile.version"))
                .body("error.message", equalTo("The requested OpenShift version '4.18.3' is invalid."));
    }

    @Test
    void anInvalidDomainIsRefused() {
        put("baddomain", fullBody("1abc")).then().statusCode(400)
                .body("error.code", equalTo("InvalidParameter"))
                .body("error.target", equalTo("properties.clusterProfile.domain"))
                .body("error.message", equalTo("The provided domain '1abc' is invalid."));
        put("otherregion", fullBody("foo.westus.aroapp.io")).then().statusCode(400)
                .body("error.target", equalTo("properties.clusterProfile.domain"));
    }

    @Test
    void networkCidrsAreValidatedLikeAro() {
        put("smallpods", fullBody("smallpods").replace("10.128.0.0/14", "10.128.0.0/20")).then().statusCode(400)
                .body("error.code", equalTo("InvalidParameter"))
                .body("error.target", equalTo("properties.networkProfile.podCidr"))
                .body("error.message", equalTo("The provided vnet CIDR '10.128.0.0/20' is invalid: must be /18 or larger."));
        put("joinrange", fullBody("joinrange").replace("172.30.0.0/16", "100.64.0.0/16")).then().statusCode(400)
                .body("error.code", equalTo("InvalidCIDRRange"));
        put("hostbits", fullBody("hostbits").replace("10.128.0.0/14", "10.129.0.0/14")).then().statusCode(400)
                .body("error.code", equalTo("InvalidNetworkAddress"))
                .body("error.message", containsString("expecting: '10.128.0.0/14'"));
    }

    @Test
    void aServicePrincipalNeedsAUuidClientIdAndASecret() {
        put("badclient", fullBody("badclient").replace(CLIENT_ID, "not-a-uuid")).then().statusCode(400)
                .body("error.target", equalTo("properties.servicePrincipalProfile.clientId"));
        put("nosecret", fullBody("nosecret").replace(", \"clientSecret\": \"s3cret\"", "")).then().statusCode(400)
                .body("error.target", equalTo("properties.servicePrincipalProfile.clientSecret"));
    }

    @Test
    void theClusterResourceGroupMustDifferFromTheClustersOwn() {
        put("samerg", fullBody("samerg").replace("resourceGroups/aro-infra", "resourceGroups/" + RG))
                .then().statusCode(400)
                .body("error.target", equalTo("properties.clusterProfile.resourceGroupId"))
                .body("error.message", endsWith("must be different from resourceGroup of the OpenShift cluster object."));
    }

    @Test
    void reputtingAClusterAnswers200AndRefusesImmutableChanges() {
        put("cluster1", fullBody("mydomain")).then().statusCode(201);
        put("cluster1", fullBody("mydomain").replace("\"env\": \"test\"", "\"env\": \"prod\"")).then().statusCode(200)
                .header("Azure-AsyncOperation", containsString("/operationsstatus/"))
                .body("tags.env", equalTo("prod"));
        put("cluster1", fullBody("otherdomain")).then().statusCode(400)
                .body("error.code", equalTo("PropertyChangeNotAllowed"))
                .body("error.target", equalTo("properties.clusterProfile.domain"))
                .body("error.message", equalTo("Changing property 'properties.clusterProfile.domain' is not allowed."));
    }

    @Test
    void patchUpdatesTagsAndKeepsTheRest() {
        put("cluster1", fullBody("mydomain")).then().statusCode(201);
        given().contentType("application/json").body("{\"tags\":{\"team\":\"a\"}}")
                .when().patch(PROVIDER + "/cluster1" + API).then().statusCode(200)
                .header("Azure-AsyncOperation", containsString("/operationsstatus/"))
                .body("tags.team", equalTo("a"))
                .body("tags.env", nullValue())
                .body("properties.clusterProfile.domain", equalTo("mydomain"))
                .body("properties.servicePrincipalProfile.clientId", equalTo(CLIENT_ID));
        given().contentType("application/json")
                .body("{\"properties\":{\"masterProfile\":{\"vmSize\":\"Standard_D16s_v5\"}}}")
                .when().patch(PROVIDER + "/cluster1" + API).then().statusCode(400)
                .body("error.code", equalTo("PropertyChangeNotAllowed"))
                .body("error.target", equalTo("properties.masterProfile.vmSize"));
    }

    @Test
    void theAsyncOperationReportsTheStateOfTheClusterItChanged() {
        String operation = put("cluster1", fullBody("mydomain")).then().statusCode(201)
                .extract().header("Azure-AsyncOperation");
        String operationId = operation.substring(operation.lastIndexOf('/') + 1, operation.indexOf('?'));
        given().when().get(URI.create(operation).getPath() + API).then().statusCode(200)
                .body("name", equalTo(operationId))
                .body("id", equalTo("/subscriptions/" + SUB
                        + "/providers/Microsoft.RedHatOpenShift/locations/eastus/operationsstatus/" + operationId))
                .body("status", equalTo("Succeeded"))
                .body("startTime", notNullValue())
                .body("endTime", notNullValue());
        given().when().get("/subscriptions/" + SUB
                        + "/providers/Microsoft.RedHatOpenShift/locations/eastus/operationsstatus/no-such-op" + API)
                .then().statusCode(404).body("error.code", equalTo("NotFound"))
                .body("error.message", equalTo("The entity was not found."));
        given().when().get("/subscriptions/00000000-0000-0000-0000-0000000000ff"
                        + "/providers/Microsoft.RedHatOpenShift/locations/eastus/operationsstatus/" + operationId + API)
                .then().statusCode(404);
    }

    @Test
    void deletingAnswers202WithLroHeadersThenTheClusterIsGone() {
        put("cluster1", fullBody("mydomain")).then().statusCode(201);
        Response deleted = given().when().delete(PROVIDER + "/cluster1" + API);
        deleted.then().statusCode(202)
                .header("Location", containsString("/providers/Microsoft.RedHatOpenShift/locations/eastus/operationresults/"))
                .header("Azure-AsyncOperation", containsString("/locations/eastus/operationsstatus/"));
        given().when().get(URI.create(deleted.header("Azure-AsyncOperation")).getPath() + API).then().statusCode(200)
                .body("status", equalTo("Succeeded"));
        given().when().get(URI.create(deleted.header("Location")).getPath() + API).then().statusCode(204);
        given().when().get(PROVIDER + "/cluster1" + API).then().statusCode(404);
        given().when().delete(PROVIDER + "/cluster1" + API).then().statusCode(204);
    }

    @Test
    void listCredentialsReturnsKubeadminAndAnInstallerStylePassword() {
        put("cluster1", fullBody("mydomain")).then().statusCode(201);
        String password = given().contentType("application/json").when()
                .post(PROVIDER + "/cluster1/listCredentials" + API).then().statusCode(200)
                .body("kubeadminUsername", equalTo("kubeadmin"))
                .extract().path("kubeadminPassword");
        assertTrue(password.matches("[a-km-zA-NP-Z2-9]{5}-[a-km-zA-NP-Z2-9]{5}-[a-km-zA-NP-Z2-9]{5}-[a-km-zA-NP-Z2-9]{5}"),
                password);
        given().contentType("application/json").when().post(PROVIDER + "/cluster1/listCredentials" + API)
                .then().body("kubeadminPassword", equalTo(password));
    }

    @Test
    void listAdminCredentialsReturnsABase64Kubeconfig() {
        put("cluster1", fullBody("mydomain")).then().statusCode(201);
        String encoded = given().contentType("application/json").when()
                .post(PROVIDER + "/cluster1/listAdminCredentials" + API).then().statusCode(200)
                .extract().path("kubeconfig");
        String kubeconfig = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        assertTrue(kubeconfig.contains("server: https://api.mydomain.eastus.aroapp.io:6443"), kubeconfig);
        assertTrue(kubeconfig.contains("current-context: admin"), kubeconfig);
    }

    @Test
    void credentialsOfAnUnknownClusterAnswer404() {
        given().contentType("application/json").when().post(PROVIDER + "/nope/listCredentials" + API)
                .then().statusCode(404).body("error.code", equalTo("ResourceNotFound"));
    }

    @Test
    void openShiftVersionsListsOnlyVersionsWithAnImage() {
        String base = "/subscriptions/" + SUB + "/providers/Microsoft.RedHatOpenShift/locations/eastus/openShiftVersions";
        given().when().get(base + API).then().statusCode(200)
                .body("value", hasSize(1))
                .body("value[0].name", equalTo("4.19.0"))
                .body("value[0].type", equalTo("Microsoft.RedHatOpenShift/OpenShiftVersion"))
                .body("value[0].properties.version", equalTo("4.19.0"));
        given().when().get(base.toLowerCase() + "/4.19.0" + API).then().statusCode(200)
                .body("properties.version", equalTo("4.19.0"));
        given().when().get(base + "/4.14.40" + API).then().statusCode(400)
                .body("error.code", equalTo("ResourceNotFound"))
                .body("error.message", equalTo("The Resource openShiftVersion with version '4.14.40' was not found in "
                        + "the namespace 'microsoft.redhatopenshift' for api version '2025-07-25'."));
    }
}
