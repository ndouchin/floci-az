package io.floci.az.services.aks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Kubernetes versions the emulator can run, one pinned k3s image per minor version, and the AKS rules over
 * them: a {@code major.minor} request resolves to that minor's patch, upgrades go one minor at a time and never
 * down, and a node pool may lag the control plane by up to three minors but never lead it.
 */
final class AksVersions {

    /**
     * Minor versions AKS supports on 2026-11-10 (1.34 until its November 2026 end of life, 1.35 to 1.37 GA) and the
     * newest k3s release of each. Patches follow k3s, not AKS's own patch numbering.
     */
    static final Map<String, String> K3S_TAGS;

    /** AKS creates clusters on the N-1 minor when none is given. */
    static final String DEFAULT_MINOR = "1.36";

    static {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("1.34", "v1.34.13-k3s1");
        tags.put("1.35", "v1.35.10-k3s1");
        tags.put("1.36", "v1.36.6-k3s1");
        tags.put("1.37", "v1.37.2-k3s1");
        K3S_TAGS = Map.copyOf(tags);
    }

    private static final Pattern K3S_TAG = Pattern.compile("v(\\d+)\\.(\\d+)\\.(\\d+)-k3s\\d+");
    private static final int MAX_POOL_LAG = 3;

    enum Skew { AHEAD, TOO_FAR_BEHIND }

    record Version(int major, int minor, int patch) implements Comparable<Version> {

        private static final Pattern FULL = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

        static Optional<Version> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            Matcher m = FULL.matcher(value.trim());
            if (!m.matches()) {
                return Optional.empty();
            }
            return Optional.of(new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))));
        }

        String minorVersion() {
            return major + "." + minor;
        }

        @Override
        public int compareTo(Version other) {
            if (major != other.major) {
                return Integer.compare(major, other.major);
            }
            if (minor != other.minor) {
                return Integer.compare(minor, other.minor);
            }
            return Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    private static final Pattern MINOR_ONLY = Pattern.compile("(\\d+)\\.(\\d+)");

    private final String imageRepository;
    private final Optional<String> imageOverride;
    private final Map<String, Version> byMinor = new LinkedHashMap<>();
    private final Map<Version, String> tags = new LinkedHashMap<>();

    AksVersions(String imageRepository, Optional<String> imageOverride) {
        this.imageRepository = imageRepository;
        this.imageOverride = imageOverride.filter(image -> !image.isBlank());
        K3S_TAGS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey((a, b) -> Version.parse(a + ".0").orElseThrow()
                        .compareTo(Version.parse(b + ".0").orElseThrow())))
                .forEach(e -> {
                    Matcher m = K3S_TAG.matcher(e.getValue());
                    if (!m.matches()) {
                        throw new IllegalStateException("not a k3s tag: " + e.getValue());
                    }
                    Version v = new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3)));
                    byMinor.put(e.getKey(), v);
                    tags.put(v, e.getValue());
                });
    }

    /** Every version the emulator can run, oldest first. */
    List<Version> supported() {
        return List.copyOf(byMinor.values());
    }

    Version defaultVersion() {
        return byMinor.get(DEFAULT_MINOR);
    }

    /**
     * The version a {@code kubernetesVersion} or {@code orchestratorVersion} names: {@code major.minor} gives that
     * minor's patch, {@code major.minor.patch} must be the patch the emulator has, and no value gives the default.
     */
    Optional<Version> resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return Optional.of(defaultVersion());
        }
        String value = requested.trim();
        if (MINOR_ONLY.matcher(value).matches()) {
            return Optional.ofNullable(byMinor.get(value));
        }
        return Version.parse(value).filter(tags::containsKey);
    }

    /** True when {@code requested} names the version already running, which AKS does not treat as an upgrade. */
    boolean sameAsCurrent(String requested, Version current) {
        if (requested == null || requested.isBlank()) {
            return true;
        }
        String value = requested.trim();
        if (MINOR_ONLY.matcher(value).matches()) {
            return value.equals(current.minorVersion());
        }
        return Version.parse(value).map(current::equals).orElse(false);
    }

    String image(Version version) {
        return imageOverride.orElseGet(() -> imageRepository + ":" + tags.get(version));
    }

    /** Versions {@code current} may upgrade to: later patches of its minor and the next minor's patch. */
    List<Version> upgradesFrom(Version current) {
        List<Version> upgrades = new ArrayList<>();
        for (Version candidate : byMinor.values()) {
            if (candidate.compareTo(current) > 0 && upgradeAllowed(current, candidate)) {
                upgrades.add(candidate);
            }
        }
        return upgrades;
    }

    /** Same major, not lower, and at most one minor higher. */
    static boolean upgradeAllowed(Version from, Version to) {
        return from.major() == to.major() && to.compareTo(from) >= 0 && to.minor() - from.minor() <= 1;
    }

    /** Why a node pool version cannot run under a control plane version, empty when it can. */
    static Optional<Skew> poolSkew(Version pool, Version controlPlane) {
        if (pool.compareTo(controlPlane) > 0) {
            return Optional.of(Skew.AHEAD);
        }
        if (pool.major() != controlPlane.major() || controlPlane.minor() - pool.minor() > MAX_POOL_LAG) {
            return Optional.of(Skew.TOO_FAR_BEHIND);
        }
        return Optional.empty();
    }
}
