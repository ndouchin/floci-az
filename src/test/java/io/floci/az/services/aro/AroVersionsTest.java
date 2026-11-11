package io.floci.az.services.aro;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AroVersionsTest {

    private final AroVersions versions = AroVersions.parse(List.of("4.19=4.19.0-okd-scos.17", "4.18=4.18.0-okd-scos.9"));

    @Test
    void anyPatchOfAConfiguredMinorRunsThatMinorsImage() {
        assertEquals("4.19.0-okd-scos.17", versions.resolve("4.19.7").orElseThrow().tag());
        assertEquals("4.18.0-okd-scos.9", versions.resolve("4.18.0").orElseThrow().tag());
    }

    @Test
    void aMinorWithNoImageOrAMalformedVersionResolvesToNothing() {
        assertTrue(versions.resolve("4.17.3").isEmpty());
        assertTrue(versions.resolve("4.19").isEmpty());
        assertTrue(versions.resolve("latest").isEmpty());
        assertTrue(versions.resolve(null).isEmpty());
    }

    @Test
    void theAdvertisedVersionsAreTheTagVersionsNewestFirst() {
        assertEquals(List.of("4.19.0", "4.18.0"), versions.advertised());
        assertEquals("4.19.0", versions.defaultVersion());
    }

    @Test
    void aMalformedEntryOrATagOfAnotherMinorIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> AroVersions.parse(List.of("4.19:4.19.0")));
        assertThrows(IllegalArgumentException.class, () -> AroVersions.parse(List.of("4.19=4.18.0-okd-scos.9")));
        assertThrows(IllegalArgumentException.class, () -> AroVersions.parse(List.of()));
    }

    @Test
    void theTagSuffixFollowsTheDockerDaemonsArchitecture() {
        assertEquals("-arm64", AroVersions.architectureSuffix("aarch64"));
        assertEquals("-amd64", AroVersions.architectureSuffix("x86_64"));
        assertThrows(IllegalStateException.class, () -> AroVersions.architectureSuffix("s390x"));
    }
}
