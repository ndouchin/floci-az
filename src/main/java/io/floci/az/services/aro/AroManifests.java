package io.floci.az.services.aro;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files a cluster's MicroShift container starts with: a {@code config.d} drop-in with the cluster's settings.
 */
final class AroManifests {

    static final String CONFIG_DROP_IN = "/etc/microshift/config.d/10-floci-aro.yaml";

    private AroManifests() {
    }

    /** What the drop-in is rendered from. */
    record Settings(String fullDomain, String nodeName, List<String> subjectAltNames, String podCidr,
                    String serviceCidr) {
    }

    /** Every file to copy into the container before it starts, keyed by absolute path. */
    static Map<String, byte[]> files(Settings settings) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(CONFIG_DROP_IN, utf8(configDropIn(settings)));
        return files;
    }

    /**
     * MicroShift settings (see {@code /etc/microshift/config.yaml.default} in the image): the API certificate's extra
     * names, the base domain Routes are generated under ({@code *.apps.<domain>}), the cluster's pod and service
     * CIDRs and an ARO-shaped node name. MicroShift refuses {@code localhost} and {@code 127.0.0.1} as extra names,
     * since its own localhost certificate serves them.
     */
    static String configDropIn(Settings settings) {
        StringBuilder yaml = new StringBuilder("apiServer:\n  subjectAltNames:\n");
        for (String name : settings.subjectAltNames()) {
            yaml.append("  - ").append(quote(name)).append('\n');
        }
        yaml.append("dns:\n  baseDomain: ").append(quote(settings.fullDomain())).append('\n');
        yaml.append("network:\n  clusterNetwork:\n  - ").append(quote(settings.podCidr())).append('\n');
        yaml.append("  serviceNetwork:\n  - ").append(quote(settings.serviceCidr())).append('\n');
        yaml.append("node:\n  hostnameOverride: ").append(quote(settings.nodeName())).append('\n');
        return yaml.toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
