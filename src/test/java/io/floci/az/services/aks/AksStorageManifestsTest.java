package io.floci.az.services.aks;

import io.floci.az.services.aks.AksVersions.Version;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AksStorageManifestsTest {

    private static final Version V134 = Version.parse("1.34.13").orElseThrow();
    private static final Version V136 = Version.parse("1.36.6").orElseThrow();

    private static String storageClass(String yaml, String name) {
        for (String document : yaml.split("\n---\n")) {
            if (document.contains("kind: StorageClass") && document.contains("\n  name: " + name + "\n")) {
                return document;
            }
        }
        throw new AssertionError("no StorageClass " + name + " in\n" + yaml);
    }

    private static List<String> storageClassNames(String yaml) {
        Matcher m = Pattern.compile("kind: StorageClass\nmetadata:\n(?:  annotations:\n(?:    .*\n)+)?  name: (\\S+)")
                .matcher(yaml);
        List<String> names = new ArrayList<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    @Test
    void theClassesAksCreatesAreThereWithTheirCsiDrivers() {
        String yaml = AksStorageManifests.manifest(V134);

        assertEquals(List.of("default", "managed-csi", "managed-csi-premium", "managed", "managed-premium",
                "azurefile-csi", "azurefile-csi-premium", "azurefile", "azurefile-premium"), storageClassNames(yaml));
        assertTrue(storageClass(yaml, "managed-csi").contains("provisioner: disk.csi.azure.com"));
        assertTrue(storageClass(yaml, "managed-csi").contains("skuname: StandardSSD_LRS"));
        assertTrue(storageClass(yaml, "managed-csi-premium").contains("skuname: Premium_LRS"));
        assertTrue(storageClass(yaml, "azurefile-csi").contains("provisioner: file.csi.azure.com"));
        assertTrue(storageClass(yaml, "azurefile-csi-premium").contains("skuName: Premium_LRS"));
        assertTrue(storageClass(yaml, "managed-premium").contains(
                "  cachingmode: ReadOnly\n  kind: Managed\n  storageaccounttype: Premium_LRS\n"));
    }

    @Test
    void classesCarryTheAddonManagerLabelsAksSets() {
        String yaml = AksStorageManifests.manifest(V136);

        for (String name : storageClassNames(yaml)) {
            assertTrue(storageClass(yaml, name).contains("    addonmanager.kubernetes.io/mode: EnsureExists\n"), name);
            assertTrue(storageClass(yaml, name).contains("    kubernetes.io/cluster-service: \"true\"\n"), name);
        }
    }

    @Test
    void onlyTheDefaultClassIsTheDefault() {
        String yaml = AksStorageManifests.manifest(V136);

        assertTrue(storageClass(yaml, "default").contains("storageclass.kubernetes.io/is-default-class: \"true\""));
        assertFalse(storageClass(yaml, "managed-csi").contains("is-default-class"));
        assertEquals(1, yaml.split("is-default-class", -1).length - 1);
    }

    @Test
    void diskClassesWaitForTheirConsumerAndAllClassesExpandAndDelete() {
        String yaml = AksStorageManifests.manifest(V136);

        assertTrue(storageClass(yaml, "default").contains("volumeBindingMode: WaitForFirstConsumer"));
        assertTrue(storageClass(yaml, "azurefile-csi").contains("volumeBindingMode: Immediate"));
        for (String name : storageClassNames(yaml)) {
            assertTrue(storageClass(yaml, name).contains("allowVolumeExpansion: true"), name);
            assertTrue(storageClass(yaml, name).contains("reclaimPolicy: Delete"), name);
        }
    }

    @Test
    void premiumV2ArrivesWithKubernetes135() {
        assertFalse(storageClassNames(AksStorageManifests.manifest(V134)).contains("managed-csi-premium-v2"));
        assertTrue(storageClass(AksStorageManifests.manifest(V136), "managed-csi-premium-v2")
                .contains("skuname: PremiumV2_LRS"));
    }

    @Test
    void eachCsiDriverNameHasAProvisionerOnTheSharedStorageVolume() {
        String yaml = AksStorageManifests.manifest(V136);

        assertTrue(yaml.contains("- --provisioner-name\n        - disk.csi.azure.com"));
        assertTrue(yaml.contains("- --provisioner-name\n        - file.csi.azure.com"));
        assertTrue(yaml.contains("\"sharedFileSystemPath\": \"" + AksClusterManager.STORAGE_DIR + "/disk\""));
        assertTrue(yaml.contains("\"sharedFileSystemPath\": \"" + AksClusterManager.STORAGE_DIR + "/file\""));
        assertTrue(yaml.contains("image: " + AksStorageManifests.PROVISIONER_IMAGE));
    }
}
