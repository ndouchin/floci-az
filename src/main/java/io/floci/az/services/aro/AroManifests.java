package io.floci.az.services.aro;

import io.floci.az.services.aro.AroModels.OpenShiftCluster;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The files a cluster's MicroShift container starts with: a {@code config.d} drop-in and the kustomizations MicroShift
 * applies from {@code /etc/microshift/manifests.d} at every start (storage classes, the {@code config.openshift.io}
 * CRDs and their cluster objects), plus the status patches applied once the API answers.
 */
final class AroManifests {

    static final String CONFIG_DROP_IN = "/etc/microshift/config.d/10-floci-aro.yaml";
    static final String MANIFESTS_DIR = "/etc/microshift/manifests.d";
    /** The OKD release image the MINC builds come from. */
    static final String RELEASE_IMAGE_REPOSITORY = "quay.io/okd/scos-release";

    private static final List<String> CONFIG_CRDS = List.of(
            "0000_00_cluster-version-operator_01_clusterversions-Default.crd.yaml",
            "0000_10_config-operator_01_infrastructures-Default.crd.yaml",
            "0000_10_config-operator_01_ingresses.crd.yaml");

    private AroManifests() {
    }

    /** What the drop-in and the manifests are rendered from. */
    record Settings(String fullDomain, String nodeName, List<String> subjectAltNames, String podCidr,
                    String serviceCidr, String clusterUuid, String provisionerImage, String helperImage,
                    boolean azureFileClass) {
    }

    /** Every file to copy into the container before it starts, keyed by absolute path. */
    static Map<String, byte[]> files(Settings settings) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(CONFIG_DROP_IN, utf8(configDropIn(settings)));
        String storage = MANIFESTS_DIR + "/010-floci-aro-storage/";
        List<String> storageFiles = settings.azureFileClass()
                ? List.of("storage.yaml", "storage-azurefile.yaml") : List.of("storage.yaml");
        files.put(storage + "kustomization.yaml", utf8(kustomization(storageFiles)));
        for (String file : storageFiles) {
            files.put(storage + file, utf8(resource(file)
                    .replace("${PROVISIONER_IMAGE}", settings.provisionerImage())
                    .replace("${HELPER_IMAGE}", settings.helperImage())));
        }
        String crds = MANIFESTS_DIR + "/020-floci-aro-config-crds/";
        files.put(crds + "kustomization.yaml", utf8(kustomization(CONFIG_CRDS)));
        for (String crd : CONFIG_CRDS) {
            files.put(crds + crd, utf8(resource("config-crds/" + crd)));
        }
        String config = MANIFESTS_DIR + "/030-floci-aro-config/";
        files.put(config + "kustomization.yaml", utf8(kustomization(List.of("cluster-config.yaml"))));
        files.put(config + "cluster-config.yaml", utf8(resource("cluster-config.yaml")
                .replace("${CLUSTER_ID}", settings.clusterUuid())
                .replace("${DOMAIN}", settings.fullDomain())));
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

    /** The labels and provider id an ARO master node carries, applied with {@code oc label} and {@code oc patch}. */
    static List<String> nodeLabels(OpenShiftCluster cluster, String vmSize) {
        String region = cluster.getLocation().toLowerCase(Locale.ROOT).replace(" ", "");
        return List.of(
                "topology.kubernetes.io/region=" + region,
                "topology.kubernetes.io/zone=" + region + "-1",
                "node.kubernetes.io/instance-type=" + vmSize);
    }

    /** {@code azure:///subscriptions/<sub>/resourceGroups/<cluster group>/providers/Microsoft.Compute/...}. */
    static String providerId(OpenShiftCluster cluster, String clusterResourceGroup, String nodeName) {
        return "azure:///subscriptions/" + cluster.getSubscriptionId() + "/resourceGroups/" + clusterResourceGroup
                + "/providers/Microsoft.Compute/virtualMachines/" + nodeName;
    }

    /** The {@code ClusterVersion/version} status: the OKD version the image runs, installed and available. */
    static String clusterVersionStatus(String imageTag, Instant installedAt) {
        String image = RELEASE_IMAGE_REPOSITORY + ":" + imageTag;
        String time = installedAt.toString();
        return "{\"status\":{"
                + "\"desired\":{\"version\":\"" + imageTag + "\",\"image\":\"" + image + "\"},"
                + "\"history\":[{\"state\":\"Completed\",\"version\":\"" + imageTag + "\",\"image\":\"" + image
                + "\",\"startedTime\":\"" + time + "\",\"completionTime\":\"" + time + "\",\"verified\":false}],"
                + "\"observedGeneration\":1,\"versionHash\":\"" + versionHash(imageTag) + "\","
                + "\"availableUpdates\":[],"
                + "\"conditions\":["
                + condition("Available", "True", "", "Done applying " + imageTag, time) + ","
                + condition("Failing", "False", "", "", time) + ","
                + condition("Progressing", "False", "", "Cluster version is " + imageTag, time) + ","
                + condition("RetrievedUpdates", "False", "NoChannel", "The update channel has not been configured.",
                        time)
                + "]}}";
    }

    /** The {@code Infrastructure/cluster} status of an ARO cluster: platform Azure and its API URLs. */
    static String infrastructureStatus(String fullDomain, String infraId, String clusterResourceGroup,
                                       String networkResourceGroup) {
        return "{\"status\":{"
                + "\"apiServerURL\":\"https://api." + fullDomain + ":6443\","
                + "\"apiServerInternalURI\":\"https://api-int." + fullDomain + ":6443\","
                + "\"controlPlaneTopology\":\"HighlyAvailable\",\"infrastructureTopology\":\"HighlyAvailable\","
                + "\"cpuPartitioning\":\"None\",\"etcdDiscoveryDomain\":\"\","
                + "\"infrastructureName\":\"" + infraId + "\",\"platform\":\"Azure\","
                + "\"platformStatus\":{\"type\":\"Azure\",\"azure\":{\"cloudName\":\"AzurePublicCloud\","
                + "\"resourceGroupName\":\"" + clusterResourceGroup + "\","
                + "\"networkResourceGroupName\":\"" + networkResourceGroup + "\"}}}}";
    }

    static String ingressStatus() {
        return "{\"status\":{\"defaultPlacement\":\"Workers\"}}";
    }

    private static String condition(String type, String status, String reason, String message, String time) {
        StringBuilder json = new StringBuilder("{\"type\":\"").append(type).append("\",\"status\":\"")
                .append(status).append("\",\"lastTransitionTime\":\"").append(time).append('"');
        if (!reason.isEmpty()) {
            json.append(",\"reason\":\"").append(reason).append('"');
        }
        if (!message.isEmpty()) {
            json.append(",\"message\":\"").append(message).append('"');
        }
        return json.append('}').toString();
    }

    /** A stable stand-in for the cluster-version operator's hash of the payload it applied. */
    private static String versionHash(String imageTag) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(utf8(imageTag));
            return Base64.getUrlEncoder().encodeToString(digest).substring(0, 11) + "=";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String kustomization(List<String> resources) {
        StringBuilder yaml = new StringBuilder("apiVersion: kustomize.config.k8s.io/v1beta1\n"
                + "kind: Kustomization\nresources:\n");
        resources.forEach(resource -> yaml.append("- ").append(resource).append('\n'));
        return yaml.toString();
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String resource(String name) {
        String path = "aro/" + name;
        try (InputStream in = AroManifests.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
