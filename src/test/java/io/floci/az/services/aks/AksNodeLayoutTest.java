package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.AgentPoolProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AksNodeLayoutTest {

    private static ManagedCluster cluster() {
        ManagedCluster cluster = new ManagedCluster();
        cluster.setInstanceId("1a2b3c4d");
        cluster.setSubscriptionId("sub1");
        cluster.setResourceGroup("rg1");
        cluster.setName("aks1");
        cluster.setLocation("eastus");
        cluster.setNodeResourceGroup("MC_rg1_aks1_eastus");
        return cluster;
    }

    private static AgentPoolProfile pool(String name, String mode, int count) {
        AgentPoolProfile pool = new AgentPoolProfile();
        pool.setName(name);
        pool.setMode(mode);
        pool.setCount(count);
        pool.setVmSize("Standard_D4s_v5");
        pool.setOsSku("Ubuntu");
        pool.setProvisioningState("Creating");
        return pool;
    }

    @Test
    void theClusterHashIsEightDigitsAndStable() {
        String hash = AksNodeLayout.clusterHash("1a2b3c4d");
        assertTrue(hash.matches("\\d{8}"), hash);
        assertEquals(hash, AksNodeLayout.clusterHash("1a2b3c4d"));
        assertFalse(hash.equals(AksNodeLayout.clusterHash("ffff0000")));
    }

    @Test
    void nodeNamesFollowTheVmssPatternWithABase36InstanceSuffix() {
        assertEquals("aks-nodepool1-12345678-vmss000000", AksNodeLayout.nodeName("nodepool1", "12345678", 0));
        assertEquals("aks-nodepool1-12345678-vmss00000a", AksNodeLayout.nodeName("nodepool1", "12345678", 10));
        assertEquals("aks-gpu-12345678-vmss000010", AksNodeLayout.nodeName("gpu", "12345678", 36));
    }

    @Test
    void theProviderIdNamesTheScaleSetInstance() {
        ManagedCluster cluster = cluster();
        assertEquals("azure:///subscriptions/sub1/resourceGroups/MC_rg1_aks1_eastus/providers/Microsoft.Compute"
                        + "/virtualMachineScaleSets/aks-nodepool1-12345678-vmss/virtualMachines/2",
                AksNodeLayout.providerId(cluster, pool("nodepool1", "System", 3), "12345678", 2));
    }

    @Test
    void labelsCarryTheAksReservedSetAndTheUserLabels() {
        ManagedCluster cluster = cluster();
        AgentPoolProfile pool = pool("userpool", "User", 2);
        pool.setNodeLabels(Map.of("team", "payments"));

        Map<String, String> labels = AksNodeLayout.labels(cluster, pool, 1, AksVmSizes.of("Standard_D4s_v5"));

        assertEquals("userpool", labels.get("agentpool"));
        assertEquals("userpool", labels.get("kubernetes.azure.com/agentpool"));
        assertEquals("MC_rg1_aks1_eastus", labels.get("kubernetes.azure.com/cluster"));
        assertEquals("user", labels.get("kubernetes.azure.com/mode"));
        assertEquals("agent", labels.get("kubernetes.azure.com/role"));
        assertEquals("aks", labels.get("kubernetes.azure.com/managedby"));
        assertEquals("Ubuntu", labels.get("kubernetes.azure.com/os-sku"));
        assertEquals("VirtualMachineScaleSets", labels.get("kubernetes.azure.com/nodepool-type"));
        assertEquals("4", labels.get("kubernetes.azure.com/sku-cpu"));
        assertEquals("16", labels.get("kubernetes.azure.com/sku-memory"));
        assertEquals("Standard_D4s_v5", labels.get("node.kubernetes.io/instance-type"));
        assertEquals("Standard_D4s_v5", labels.get("beta.kubernetes.io/instance-type"));
        assertEquals("eastus", labels.get("topology.kubernetes.io/region"));
        assertEquals("0", labels.get("topology.kubernetes.io/zone"));
        assertEquals("payments", labels.get("team"));
    }

    @Test
    void aGpuSizeCarriesTheAcceleratorLabels() {
        AgentPoolProfile gpu = pool("gpu", "User", 1);
        gpu.setVmSize("Standard_NC4as_T4_v3");

        Map<String, String> labels = AksNodeLayout.labels(cluster(), gpu, 0, AksVmSizes.of("Standard_NC4as_T4_v3"));

        assertEquals("nvidia", labels.get("kubernetes.azure.com/accelerator"));
        assertEquals("nvidia", labels.get("accelerator"));
    }

    @Test
    void aCpuSizeHasNoAcceleratorLabel() {
        Map<String, String> labels = AksNodeLayout.labels(cluster(), pool("cpu", "User", 1), 0,
                AksVmSizes.of("Standard_D4s_v5"));

        assertFalse(labels.containsKey("kubernetes.azure.com/accelerator"));
        assertFalse(labels.containsKey("accelerator"));
    }

    @Test
    void aSystemPoolIsLabelledSystem() {
        assertEquals("system", AksNodeLayout.labels(cluster(), pool("sys", "System", 1), 0,
                AksVmSizes.of("Standard_DS2_v2")).get("kubernetes.azure.com/mode"));
    }

    @Test
    void nodesSpreadOverThePoolZones() {
        AgentPoolProfile pool = pool("zoned", "User", 3);
        pool.setAvailabilityZones(List.of("1", "2", "3"));

        assertEquals("eastus-1", AksNodeLayout.zone(cluster(), pool, 0));
        assertEquals("eastus-2", AksNodeLayout.zone(cluster(), pool, 1));
        assertEquals("eastus-3", AksNodeLayout.zone(cluster(), pool, 2));
        assertEquals("eastus-1", AksNodeLayout.zone(cluster(), pool, 3));
    }

    @Test
    void theCapServesSystemPoolsFirstThenPoolsInCreationOrder() {
        AgentPoolProfile user1 = pool("user1", "User", 3);
        AgentPoolProfile system = pool("sys", "System", 2);
        AgentPoolProfile user2 = pool("user2", "User", 2);

        Map<String, Integer> nodes = AksNodeLayout.allocate(List.of(user1, system, user2), 5);

        assertEquals(Map.of("user1", 3, "sys", 2, "user2", 0), nodes);
        assertEquals(List.of("user1", "sys", "user2"), List.copyOf(nodes.keySet()));
    }

    @Test
    void aPoolWithCountZeroOrBeingDeletedHasNoNode() {
        AgentPoolProfile empty = pool("empty", "User", 0);
        AgentPoolProfile leaving = pool("leaving", "User", 2);
        leaving.setProvisioningState("Deleting");

        assertEquals(Map.of("empty", 0, "leaving", 0), AksNodeLayout.allocate(List.of(empty, leaving), 5));
    }

    @Test
    void taintsAreValidated() {
        AksNodeLayout.validateTaints(List.of("sku=gpu:NoSchedule", "dedicated:PreferNoSchedule", "a=b:NoExecute"));

        assertThrows(IllegalArgumentException.class, () -> AksNodeLayout.validateTaints(List.of("sku=gpu")));
        assertThrows(IllegalArgumentException.class,
                () -> AksNodeLayout.validateTaints(List.of("sku=gpu:Sometimes")));
    }

    @Test
    void reservedLabelPrefixesAreRefused() {
        AksNodeLayout.validateLabels(Map.of("team", "a", "example.com/owner", "b"));

        assertThrows(IllegalArgumentException.class,
                () -> AksNodeLayout.validateLabels(Map.of("kubernetes.azure.com/mode", "user")));
        assertThrows(IllegalArgumentException.class,
                () -> AksNodeLayout.validateLabels(Map.of("node.kubernetes.io/role", "x")));
    }
}
