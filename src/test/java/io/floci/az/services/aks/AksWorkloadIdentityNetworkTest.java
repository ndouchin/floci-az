package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The objects that let a Workload Identity cluster's pods reach floci-az's Entra. */
class AksWorkloadIdentityNetworkTest {

    private static ManagedCluster cluster() {
        ManagedCluster cluster = new ManagedCluster();
        cluster.setLocation("eastus");
        AksWorkloadIdentity.enableIssuer(cluster, "t1");
        return cluster;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents(String yaml) {
        List<Map<String, Object>> documents = new ArrayList<>();
        new Yaml().loadAll(yaml).forEach(document -> documents.add((Map<String, Object>) document));
        return documents;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theServiceSendsPorts443And80ToFlociAz() {
        Map<String, String> manifests = AksWorkloadIdentityNetwork.manifests(cluster(), "10.1.2.3", 4577, null, true);
        List<Map<String, Object>> network = documents(manifests.get(AksWorkloadIdentityNetwork.NETWORK_MANIFEST));
        assertEquals("Service", network.get(0).get("kind"));
        List<Map<String, Object>> ports = (List<Map<String, Object>>) ((Map<String, Object>) network.get(0).get("spec"))
                .get("ports");
        assertEquals(List.of(443, 80), ports.stream().map(p -> p.get("port")).toList());
        assertEquals("EndpointSlice", network.get(1).get("kind"));
        assertEquals("10.1.2.3", ((List<String>) ((List<Map<String, Object>>) network.get(1).get("endpoints")).get(0)
                .get("addresses")).get(0));
        assertFalse(manifests.containsKey(AksWorkloadIdentityNetwork.CA_MANIFEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void microsoftHostnamesRewritesEntraAndTheIssuerHostToTheService() {
        ManagedCluster cluster = cluster();
        Map<String, Object> coredns = documents(AksWorkloadIdentityNetwork.manifests(cluster, "10.1.2.3", 4577, null,
                true).get(AksWorkloadIdentityNetwork.COREDNS_MANIFEST)).get(0);
        String override = ((Map<String, String>) coredns.get("data")).get("floci-az.override");
        assertTrue(override.contains("rewrite name exact login.microsoftonline.com "
                + "floci-az.kube-system.svc.cluster.local"), override);
        assertTrue(override.contains("rewrite name exact eastus.oic.prod-aks.azure.com "), override);
        assertFalse(AksWorkloadIdentityNetwork.manifests(cluster, "10.1.2.3", 4577, null, false)
                .containsKey(AksWorkloadIdentityNetwork.COREDNS_MANIFEST));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCaIsPublishedInKubeSystem() {
        String pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";
        Map<String, Object> ca = documents(AksWorkloadIdentityNetwork.manifests(cluster(), null, 4577, pem, true)
                .get(AksWorkloadIdentityNetwork.CA_MANIFEST)).get(0);
        assertEquals("floci-az-ca", ((Map<String, Object>) ca.get("metadata")).get("name"));
        assertEquals("kube-system", ((Map<String, Object>) ca.get("metadata")).get("namespace"));
        assertEquals(pem, ((Map<String, String>) ca.get("data")).get("ca.crt"));
    }
}
