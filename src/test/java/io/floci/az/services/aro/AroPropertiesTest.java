package io.floci.az.services.aro;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AroPropertiesTest {

    private static final AroProperties.Context CONTEXT = new AroProperties.Context("sub-1", "rg-1", "eastus",
            AroVersions.parse(List.of("4.19=4.19.0-okd-scos.17")));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) throws Exception {
        return new ObjectMapper().readValue(text, Map.class);
    }

    @Test
    void aShortDomainIsManagedUnderTheRegionsAroappDomain() {
        assertEquals("abc.eastus.aroapp.io", AroProperties.fullDomain("abc", "eastus"));
        assertEquals("abc.eastus.aroapp.io", AroProperties.fullDomain("abc.eastus.aroapp.io", "eastus"));
        assertEquals("apps.example.com", AroProperties.fullDomain("apps.example.com", "eastus"));
        assertEquals("abc.westeurope.aroapp.io", AroProperties.fullDomain("abc", "West Europe"));
    }

    @Test
    void secretsAndReadOnlyFieldsAreDropped() throws Exception {
        Map<String, Object> props = AroProperties.forCreate(json("""
            {"provisioningState":"Succeeded",
             "clusterProfile":{"domain":"abc","pullSecret":"x","oidcIssuer":"https://issuer"},
             "consoleProfile":{"url":"https://console"},
             "apiserverProfile":{"url":"https://api","ip":"1.2.3.4"},
             "workerProfilesStatus":[{}]}
            """), CONTEXT);
        assertFalse(props.containsKey("provisioningState"));
        assertFalse(props.containsKey("workerProfilesStatus"));
        assertEquals(Map.of("domain", "abc", "version", "4.19.0",
                "resourceGroupId", "/subscriptions/sub-1/resourcegroups/aro-abc", "fipsValidatedModules", "Disabled"),
                props.get("clusterProfile"));
        assertEquals(Map.of(), props.get("consoleProfile"));
        assertEquals(Map.of("visibility", "Public"), props.get("apiserverProfile"));
    }

    @Test
    void unknownFieldsOfOlderApiVersionsAreKept() throws Exception {
        Map<String, Object> props = AroProperties.forCreate(json("""
            {"networkProfile":{"softwareDefinedNetwork":"OVNKubernetes"}}
            """), CONTEXT);
        assertEquals("OVNKubernetes", AroProperties.cast(props.get("networkProfile")).get("softwareDefinedNetwork"));
    }

    @Test
    void userDefinedRoutingNeedsPrivateVisibility() throws Exception {
        AroProperties.InvalidProperty refused = assertThrows(AroProperties.InvalidProperty.class,
                () -> AroProperties.forCreate(json("{\"networkProfile\":{\"outboundType\":\"UserDefinedRouting\"}}"),
                        CONTEXT));
        assertEquals("properties.networkProfile.outboundType", refused.target());
        Map<String, Object> props = AroProperties.forCreate(json("""
            {"networkProfile":{"outboundType":"UserDefinedRouting"},
             "apiserverProfile":{"visibility":"Private"},
             "ingressProfiles":[{"name":"default","visibility":"Private"}]}
            """), CONTEXT);
        assertNull(AroProperties.cast(props.get("networkProfile")).get("loadBalancerProfile"));
    }

    @Test
    void anUpdateMayChangeOnlyTheMutableProperties() throws Exception {
        Map<String, Object> current = AroProperties.forCreate(json("{\"clusterProfile\":{\"domain\":\"abc\"}}"),
                CONTEXT);
        Map<String, Object> updated = AroProperties.forUpdate(current,
                json("{\"networkProfile\":{\"loadBalancerProfile\":{\"managedOutboundIps\":{\"count\":2}}}}"), true,
                CONTEXT);
        assertEquals(Map.of("managedOutboundIps", Map.of("count", 2)),
                AroProperties.cast(updated.get("networkProfile")).get("loadBalancerProfile"));
        AroProperties.InvalidProperty refused = assertThrows(AroProperties.InvalidProperty.class,
                () -> AroProperties.forUpdate(current, json("{\"workerProfiles\":[{\"count\":5}]}"), true, CONTEXT));
        assertEquals("PropertyChangeNotAllowed", refused.code());
        assertEquals("properties.workerProfiles", refused.target());
    }

    @Test
    void aPutUpdateKeepsTheVersionAndClusterResourceGroupItOmits() throws Exception {
        Map<String, Object> current = AroProperties.forCreate(json("{\"clusterProfile\":{\"domain\":\"abc\"}}"),
                CONTEXT);
        Map<String, Object> updated = AroProperties.forUpdate(current, json("{\"clusterProfile\":{\"domain\":\"abc\"}}"),
                false, CONTEXT);
        assertEquals(current.get("clusterProfile"), updated.get("clusterProfile"));
    }
}
