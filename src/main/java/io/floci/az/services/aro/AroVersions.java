package io.floci.az.services.aro;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OpenShift versions clusters can be created at, from {@code floci-az.services.aro.versions}: each entry maps a
 * minor ({@code 4.19}) to the MicroShift image tag that runs it ({@code 4.19.0-okd-scos.17}). A requested
 * {@code 4.19.x} runs that minor's image and is echoed as requested; the advertised version of a minor is the
 * {@code x.y.z} its tag starts with.
 */
public final class AroVersions {

    private static final Pattern ENTRY = Pattern.compile("\\s*(\\d+\\.\\d+)\\s*=\\s*(\\S+)\\s*");
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final Pattern TAG_VERSION = Pattern.compile("(\\d+\\.\\d+\\.\\d+).*");

    /** One runnable minor. */
    public record Minor(String minor, String tag, String advertised) {
    }

    private final Map<String, Minor> byMinor;

    private AroVersions(Map<String, Minor> byMinor) {
        this.byMinor = byMinor;
    }

    /** Parses {@code minor=tag} entries; a malformed entry is refused, since it would silently drop a version. */
    public static AroVersions parse(List<String> entries) {
        Map<String, Minor> byMinor = new LinkedHashMap<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Matcher m = ENTRY.matcher(entry);
            if (!m.matches()) {
                throw new IllegalArgumentException("floci-az.services.aro.versions: '" + entry
                        + "' is not minor=tag, such as 4.19=4.19.0-okd-scos.17");
            }
            String tag = m.group(2);
            Matcher tagVersion = TAG_VERSION.matcher(tag);
            String advertised = tagVersion.matches() ? tagVersion.group(1) : m.group(1) + ".0";
            if (!advertised.startsWith(m.group(1) + ".")) {
                throw new IllegalArgumentException("floci-az.services.aro.versions: tag '" + tag
                        + "' does not run minor " + m.group(1));
            }
            byMinor.put(m.group(1), new Minor(m.group(1), tag, advertised));
        }
        if (byMinor.isEmpty()) {
            throw new IllegalArgumentException("floci-az.services.aro.versions lists no version");
        }
        return new AroVersions(byMinor);
    }

    /** The minor that runs {@code version} ({@code major.minor.patch}), or empty for a version no image runs. */
    public Optional<Minor> resolve(String version) {
        if (version == null) {
            return Optional.empty();
        }
        Matcher m = VERSION.matcher(version.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byMinor.get(Integer.parseInt(m.group(1)) + "." + Integer.parseInt(m.group(2))));
    }

    /** The versions {@code openShiftVersions} lists, newest first. */
    public List<String> advertised() {
        List<String> versions = new ArrayList<>();
        byMinor.values().forEach(minor -> versions.add(minor.advertised()));
        versions.sort(Comparator.comparing(AroVersions::sortKey).reversed());
        return versions;
    }

    /** The version a cluster created without one gets: the newest advertised. */
    public String defaultVersion() {
        return advertised().getFirst();
    }

    /** {@code -arm64} or {@code -amd64} after the Docker daemon's reported architecture. */
    public static String architectureSuffix(String daemonArchitecture) {
        String arch = daemonArchitecture == null ? "" : daemonArchitecture.toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "aarch64", "arm64" -> "-arm64";
            case "x86_64", "amd64" -> "-amd64";
            default -> throw new IllegalStateException("no MicroShift image for Docker architecture '"
                    + daemonArchitecture + "'");
        };
    }

    private static String sortKey(String version) {
        String[] parts = version.split("\\.");
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            key.append(String.format("%08d", Integer.parseInt(part)));
        }
        return key.toString();
    }
}
