package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Records written before aadProfile was typed kept it among the echoed properties. */
class AksEntraMigrationTest {

    @Test
    void anEchoedAadProfileBecomesTyped() {
        ManagedCluster cluster = new ManagedCluster();
        Map<String, Object> echoed = new LinkedHashMap<>();
        echoed.put("aadProfile", Map.of("managed", true, "enableAzureRBAC", true,
                "adminGroupObjectIDs", List.of("g1"), "tenantID", "t1"));
        echoed.put("addonProfiles", Map.of());
        cluster.setEchoedProperties(echoed);

        assertTrue(AksEntra.migrate(cluster));

        assertTrue(cluster.getAadProfile().managed());
        assertTrue(cluster.getAadProfile().azureRbac());
        assertEquals(List.of("g1"), cluster.getAadProfile().getAdminGroupObjectIDs());
        assertEquals("t1", cluster.getAadProfile().getTenantID());
        assertFalse(cluster.getEchoedProperties().containsKey("aadProfile"));
        assertTrue(cluster.getEchoedProperties().containsKey("addonProfiles"));
        assertFalse(AksEntra.migrate(cluster));
    }
}
