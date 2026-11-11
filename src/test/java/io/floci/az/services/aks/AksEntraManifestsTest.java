package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.AadProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * "AKS automatically generates a ClusterRoleBinding that binds all of the listed groups to the cluster-admin
 * Kubernetes role" ({@code concepts-identity.md}); with Azure RBAC the admin groups are irrelevant.
 */
class AksEntraManifestsTest {

    private static ManagedCluster cluster(boolean azureRbac, List<String> groups) {
        ManagedCluster cluster = new ManagedCluster();
        cluster.setInstanceId("abc");
        cluster.setCurrentKubernetesVersion("1.36.6");
        AadProfile profile = new AadProfile();
        profile.setManaged(true);
        profile.setEnableAzureRBAC(azureRbac);
        profile.setAdminGroupObjectIDs(groups);
        cluster.setAadProfile(profile);
        return cluster;
    }

    private static AksClusterRuntime runtime() {
        return new AksClusterRuntime(mock(AksClusterManager.class), new AksVersions("rancher/k3s", Optional.empty()), 5);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> subjects(ManagedCluster cluster) {
        String manifest = runtime().manifests(cluster).get(AksClusterRuntime.ADMIN_GROUPS_MANIFEST);
        Map<String, Object> binding = new Yaml().load(manifest);
        assertEquals("ClusterRoleBinding", binding.get("kind"));
        assertEquals("aks-cluster-admin-binding-aad", ((Map<String, Object>) binding.get("metadata")).get("name"));
        assertEquals("cluster-admin", ((Map<String, Object>) binding.get("roleRef")).get("name"));
        return (List<Map<String, Object>>) binding.get("subjects");
    }

    @Test
    void theAdminGroupsAreBoundToClusterAdmin() {
        List<Map<String, Object>> subjects = subjects(cluster(false, List.of("g1", "g2")));
        assertEquals(List.of("g1", "g2"), subjects.stream().map(s -> s.get("name")).toList());
        assertTrue(subjects.stream().allMatch(s -> "Group".equals(s.get("kind"))));
    }

    @Test
    void withoutAdminGroupsOrWithAzureRbacTheBindingNamesNoOne() {
        assertTrue(subjects(cluster(false, null)).isEmpty());
        assertTrue(subjects(cluster(true, List.of("g1"))).isEmpty());
    }

    @Test
    void aClusterWithoutEntraGetsNoBinding() {
        ManagedCluster cluster = cluster(false, List.of("g1"));
        cluster.setAadProfile(null);
        assertFalse(runtime().manifests(cluster).containsKey(AksClusterRuntime.ADMIN_GROUPS_MANIFEST));
    }
}
