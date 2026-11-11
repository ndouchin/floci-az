package io.floci.az.services.aks;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** The networkProfile AKS reports and the rules it enforces on it (mocked mode). */
@QuarkusTest
@TestProfile(AksHandlerTest.MockedProfile.class)
@DisplayName("AksHandler: networkProfile defaults and validation (mocked mode)")
class AksNetworkApiTest {

    private static final String CLUSTERS = "/subscriptions/test-sub-aks-network/resourceGroups/rg-n"
            + "/providers/Microsoft.ContainerService/managedClusters/";
    private static final String API = "?api-version=2025-10-01";

    @BeforeEach
    void reset() {
        given().post("/_admin/reset").then().statusCode(204);
    }

    private static io.restassured.response.Response put(String name, String properties) {
        return given().contentType("application/json")
                .body("{\"location\":\"eastus\",\"properties\":" + properties + "}")
                .put(CLUSTERS + name + API);
    }

    @Test
    @DisplayName("a cluster created without a networkProfile reports Azure CNI Overlay and the AKS ranges")
    void defaults() {
        put("n1", "{}").then().statusCode(201);

        given().get(CLUSTERS + "n1" + API).then().statusCode(200)
                .body("properties.networkProfile.networkPlugin", equalTo("azure"))
                .body("properties.networkProfile.networkPluginMode", equalTo("overlay"))
                .body("properties.networkProfile.podCidr", equalTo("10.244.0.0/16"))
                .body("properties.networkProfile.podCidrs", contains("10.244.0.0/16"))
                .body("properties.networkProfile.serviceCidr", equalTo("10.0.0.0/16"))
                .body("properties.networkProfile.serviceCidrs", contains("10.0.0.0/16"))
                .body("properties.networkProfile.dnsServiceIP", equalTo("10.0.0.10"))
                .body("properties.networkProfile.outboundType", equalTo("loadBalancer"))
                .body("properties.networkProfile.loadBalancerSku", equalTo("standard"))
                .body("properties.networkProfile.ipFamilies", contains("IPv4"))
                .body("properties.agentPoolProfiles[0].maxPods", equalTo(250));
    }

    @Test
    @DisplayName("requested ranges are reported, and pools default their maxPods from the plugin")
    void requestedRanges() {
        put("n2", """
                {"networkProfile":{"networkPlugin":"kubenet","podCidr":"10.100.0.0/16",
                  "serviceCidr":"10.200.0.0/16","dnsServiceIP":"10.200.0.10","networkPolicy":"calico"}}""")
                .then().statusCode(201)
                .body("properties.networkProfile.networkPlugin", equalTo("kubenet"))
                .body("properties.networkProfile.networkPolicy", equalTo("calico"))
                .body("properties.networkProfile.podCidr", equalTo("10.100.0.0/16"))
                .body("properties.networkProfile.serviceCidr", equalTo("10.200.0.0/16"))
                .body("properties.networkProfile.dnsServiceIP", equalTo("10.200.0.10"))
                .body("properties.agentPoolProfiles[0].maxPods", equalTo(110));

        given().contentType("application/json").body("{\"properties\":{\"count\":1,\"mode\":\"User\"}}")
                .put(CLUSTERS + "n2/agentPools/user1" + API)
                .then().statusCode(201).body("properties.maxPods", equalTo(110));
    }

    @Test
    @DisplayName("a DNS service IP outside the service CIDR fails with DnsServiceIpOutOfServiceCidr")
    void dnsOutsideTheServiceCidr() {
        put("n3", "{\"networkProfile\":{\"serviceCidr\":\"10.200.0.0/16\"}}").then().statusCode(400)
                .body("error.code", equalTo("DnsServiceIpOutOfServiceCidr"))
                .body("error.message", equalTo("The DNS service IP 10.0.0.10 is out of the range defined by"
                        + " service CIDR 10.200.0.0/16."));
        given().get(CLUSTERS + "n3" + API).then().statusCode(404);
    }

    @Test
    @DisplayName("an update keeps the ranges the cluster was created with")
    void updatesKeepTheCreationRanges() {
        put("n4", "{\"networkProfile\":{\"serviceCidr\":\"10.1.0.0/16\",\"dnsServiceIP\":\"10.1.0.10\"}}")
                .then().statusCode(201);

        put("n4", "{\"networkProfile\":{\"serviceCidr\":\"10.2.0.0/16\",\"dnsServiceIP\":\"10.2.0.10\"}}")
                .then().statusCode(200)
                .body("properties.networkProfile.serviceCidr", equalTo("10.1.0.0/16"))
                .body("properties.networkProfile.dnsServiceIP", equalTo("10.1.0.10"));
    }
}
