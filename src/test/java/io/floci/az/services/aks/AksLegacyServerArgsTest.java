package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.AadProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

/**
 * A server created before arguments were recorded runs the arguments of a cluster with none of the round-2 features:
 * no Entra webhooks. That is the hash a restored record takes, so a feature its record carries replaces the server at
 * the next change rather than being assumed to run.
 */
class AksLegacyServerArgsTest {

    private static AksClusterRuntime runtime() {
        return new AksClusterRuntime(mock(AksClusterManager.class), new AksVersions("rancher/k3s", Optional.empty()), 5);
    }

    @Test
    void aClusterWithoutRoundTwoFeaturesRunsItsLegacyArguments() {
        ManagedCluster cluster = new ManagedCluster();
        cluster.setInstanceId("abc");
        AksClusterRuntime runtime = runtime();
        assertEquals(runtime.legacyServerHash(cluster),
                AksClusterManager.serverHash(runtime.serverArgs(cluster), runtime.startupFiles(cluster)));
    }

    @Test
    void anEntraClusterDiffersFromItsLegacyArguments() {
        ManagedCluster cluster = new ManagedCluster();
        cluster.setInstanceId("abc");
        AadProfile profile = new AadProfile();
        profile.setManaged(true);
        profile.setAdminGroupObjectIDs(List.of("g1"));
        cluster.setAadProfile(profile);
        AksClusterRuntime runtime = runtime();
        assertNotEquals(runtime.legacyServerHash(cluster),
                AksClusterManager.serverHash(runtime.serverArgs(cluster), runtime.startupFiles(cluster)));
    }
}
