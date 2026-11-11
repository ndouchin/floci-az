package io.floci.az.services.aks;

import io.floci.az.services.aks.AksVersions.Version;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AksVersionsTest {

    private final AksVersions versions = new AksVersions("rancher/k3s", Optional.empty());

    @Test
    void aMinorVersionResolvesToItsPinnedPatch() {
        assertEquals(Optional.of(Version.parse("1.36.6").orElseThrow()), versions.resolve("1.36"));
    }

    @Test
    void theExactPinnedPatchResolvesToItself() {
        assertEquals("1.35.10", versions.resolve("1.35.10").orElseThrow().toString());
    }

    @Test
    void aPatchTheEmulatorHasNoImageForDoesNotResolve() {
        assertEquals(Optional.empty(), versions.resolve("1.36.1"));
    }

    @Test
    void anUnsupportedMinorDoesNotResolve() {
        assertEquals(Optional.empty(), versions.resolve("1.29"));
        assertEquals(Optional.empty(), versions.resolve("2.0"));
        assertEquals(Optional.empty(), versions.resolve("latest"));
    }

    @Test
    void noVersionMeansTheDefaultWhichIsTheSecondNewestMinor() {
        assertEquals("1.36.6", versions.resolve(null).orElseThrow().toString());
        assertEquals("1.36.6", versions.resolve("").orElseThrow().toString());
        assertEquals("1.36.6", versions.defaultVersion().toString());
    }

    @Test
    void eachMinorRunsItsOwnK3sImage() {
        assertEquals("rancher/k3s:v1.34.13-k3s1", versions.image(versions.resolve("1.34").orElseThrow()));
        assertEquals("rancher/k3s:v1.37.2-k3s1", versions.image(versions.resolve("1.37").orElseThrow()));
    }

    @Test
    void aConfiguredImageOverridesTheMap() {
        AksVersions pinned = new AksVersions("rancher/k3s", Optional.of("mirror.local/k3s:custom"));
        assertEquals("mirror.local/k3s:custom", pinned.image(pinned.resolve("1.34").orElseThrow()));
    }

    @Test
    void theImageRepositoryIsConfigurable() {
        AksVersions mirrored = new AksVersions("mirror.local/rancher/k3s", Optional.empty());
        assertEquals("mirror.local/rancher/k3s:v1.36.6-k3s1", mirrored.image(mirrored.defaultVersion()));
    }

    @Test
    void upgradesGoOneMinorAtATime() {
        assertEquals(List.of("1.36.6"), versions.upgradesFrom(Version.parse("1.35.10").orElseThrow()).stream()
                .map(Version::toString).toList());
        assertEquals(List.of(), versions.upgradesFrom(Version.parse("1.37.2").orElseThrow()));
    }

    @Test
    void anUpgradeMaySkipNoMinorAndNeverGoesDown() {
        Version v134 = Version.parse("1.34.13").orElseThrow();
        Version v135 = Version.parse("1.35.10").orElseThrow();
        Version v136 = Version.parse("1.36.6").orElseThrow();
        assertTrue(AksVersions.upgradeAllowed(v134, v135));
        assertTrue(AksVersions.upgradeAllowed(v135, v135));
        assertFalse(AksVersions.upgradeAllowed(v134, v136));
        assertFalse(AksVersions.upgradeAllowed(v136, v135));
    }

    @Test
    void aPoolVersionMayLagUpToThreeMinorsAndNeverLeads() {
        Version cp = Version.parse("1.37.2").orElseThrow();
        assertTrue(AksVersions.poolSkew(Version.parse("1.34.13").orElseThrow(), cp).isEmpty());
        assertEquals(Optional.of(AksVersions.Skew.AHEAD), AksVersions.poolSkew(Version.parse("1.37.3").orElseThrow(),
                cp));
        assertEquals(Optional.of(AksVersions.Skew.TOO_FAR_BEHIND),
                AksVersions.poolSkew(Version.parse("1.33.1").orElseThrow(), cp));
    }

    @Test
    void theSameMinorRequestedAgainIsNoUpgrade() {
        Version current = Version.parse("1.36.6").orElseThrow();
        assertTrue(versions.sameAsCurrent("1.36", current));
        assertTrue(versions.sameAsCurrent("1.36.6", current));
        assertFalse(versions.sameAsCurrent("1.37", current));
    }
}
