package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aks.AksModels.NetworkProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AksNetworkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NetworkProfile resolve(String json) throws Exception {
        JsonNode node = json == null ? MAPPER.missingNode() : MAPPER.readTree(json);
        return AksNetwork.resolve(node);
    }

    @Test
    void noProfileGivesAzureCniOverlayWithTheAksDefaults() throws Exception {
        NetworkProfile profile = resolve(null);

        assertEquals("azure", profile.getNetworkPlugin());
        assertEquals("overlay", profile.getNetworkPluginMode());
        assertEquals("10.244.0.0/16", profile.getPodCidr());
        assertEquals("10.0.0.0/16", profile.getServiceCidr());
        assertEquals("10.0.0.10", profile.getDnsServiceIP());
        assertEquals("loadBalancer", profile.getOutboundType());
        assertEquals("standard", profile.getLoadBalancerSku());
        assertNull(profile.getNetworkPolicy());
    }

    @Test
    void requestedRangesAreKept() throws Exception {
        NetworkProfile profile = resolve("""
                {"networkPlugin":"kubenet","podCidr":"10.100.0.0/16","serviceCidr":"10.200.0.0/16",
                 "dnsServiceIP":"10.200.0.10","networkPolicy":"calico","outboundType":"userDefinedRouting"}""");

        assertEquals("kubenet", profile.getNetworkPlugin());
        assertNull(profile.getNetworkPluginMode());
        assertEquals("10.100.0.0/16", profile.getPodCidr());
        assertEquals("10.200.0.0/16", profile.getServiceCidr());
        assertEquals("10.200.0.10", profile.getDnsServiceIP());
        assertEquals("calico", profile.getNetworkPolicy());
        assertEquals("userDefinedRouting", profile.getOutboundType());
    }

    @Test
    void theListFormsOfTheRangesAreRead() throws Exception {
        NetworkProfile profile = resolve("""
                {"podCidrs":["192.168.0.0/16"],"serviceCidrs":["172.16.0.0/16"],"dnsServiceIP":"172.16.0.10"}""");

        assertEquals("192.168.0.0/16", profile.getPodCidr());
        assertEquals("172.16.0.0/16", profile.getServiceCidr());
    }

    @Test
    void azureCniOnTheNodeSubnetHasNoPodCidr() throws Exception {
        NetworkProfile profile = resolve("{\"networkPlugin\":\"azure\"}");

        assertNull(profile.getNetworkPluginMode());
        assertNull(profile.getPodCidr());
        assertEquals("10.244.0.0/16", AksNetwork.clusterCidr(profile));
    }

    @Test
    void aDnsServiceIpOutsideTheServiceCidrIsRefused() {
        AksHandler.AksRequestException e = assertThrows(AksHandler.AksRequestException.class,
                () -> resolve("{\"serviceCidr\":\"10.200.0.0/16\"}"));

        assertEquals("DnsServiceIpOutOfServiceCidr", e.code());
        assertEquals("The DNS service IP 10.0.0.10 is out of the range defined by service CIDR 10.200.0.0/16.",
                e.getMessage());
    }

    @Test
    void aMalformedRangeIsAnInvalidParameter() {
        AksHandler.AksRequestException e = assertThrows(AksHandler.AksRequestException.class,
                () -> resolve("{\"serviceCidr\":\"10.0.0.0/33\"}"));

        assertEquals("InvalidParameter", e.code());
    }

    @Test
    void k3sGetsTheRangesTheDnsAddressAndNoNetworkPolicyByDefault() throws Exception {
        assertEquals(List.of("--cluster-cidr=10.244.0.0/16", "--service-cidr=10.0.0.0/16",
                "--cluster-dns=10.0.0.10", "--disable-network-policy"), AksNetwork.serverArgs(resolve(null)));
    }

    @Test
    void aNetworkPolicyIsEnforced() throws Exception {
        assertEquals(List.of("--cluster-cidr=10.244.0.0/16", "--service-cidr=10.0.0.0/16",
                "--cluster-dns=10.0.0.10"), AksNetwork.serverArgs(resolve("{\"networkPolicy\":\"azure\"}")));
        assertEquals(List.of("--cluster-cidr=10.244.0.0/16", "--service-cidr=10.0.0.0/16",
                "--cluster-dns=10.0.0.10"), AksNetwork.serverArgs(resolve("{\"networkDataplane\":\"cilium\"}")));
        assertEquals(List.of("--cluster-cidr=10.244.0.0/16", "--service-cidr=10.0.0.0/16",
                "--cluster-dns=10.0.0.10", "--disable-network-policy"),
                AksNetwork.serverArgs(resolve("{\"networkPolicy\":\"none\"}")));
    }

    @Test
    void aClusterRecordedWithoutAProfileKeepsK3sDefaults() {
        assertEquals(List.of(), AksNetwork.serverArgs(null));
    }

    @Test
    void maxPodsDefaultsFollowTheNetworkPlugin() throws Exception {
        assertEquals(250, AksNetwork.defaultMaxPods(resolve(null)));
        assertEquals(30, AksNetwork.defaultMaxPods(resolve("{\"networkPlugin\":\"azure\"}")));
        assertEquals(110, AksNetwork.defaultMaxPods(resolve("{\"networkPlugin\":\"kubenet\"}")));
        assertEquals(250, AksNetwork.defaultMaxPods(resolve("{\"networkPlugin\":\"none\"}")));
        assertEquals(110, AksNetwork.defaultMaxPods(null));
    }
}
