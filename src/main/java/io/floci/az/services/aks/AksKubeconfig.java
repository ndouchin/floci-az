package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Kubeconfigs handed out by the credential operations, base64-encoded and laid out as AKS writes them: cluster and
 * context named after the managed cluster, user {@code clusterUser_<resourceGroup>_<cluster>} or
 * {@code clusterAdmin_<resourceGroup>_<cluster>}.
 */
final class AksKubeconfig {

    enum Role {
        USER("clusterUser"), ADMIN("clusterAdmin");

        private final String credentialName;

        Role(String credentialName) {
            this.credentialName = credentialName;
        }

        /** The {@code name} of the entry in the {@code kubeconfigs} list. */
        String credentialName() {
            return credentialName;
        }
    }

    private AksKubeconfig() {
    }

    /** The cluster's k3s CA and client credential, addressed at the endpoint clients reach the API server on. */
    static String of(ManagedCluster cluster, Role role) {
        return encode(cluster, role, """
                    certificate-authority-data: %s
                    server: %s
                """.formatted(cluster.getCaData(), cluster.getEndpoint()), """
                    client-certificate-data: %s
                    client-key-data: %s
                """.formatted(cluster.getClientCertificateData(), cluster.getClientKeyData()));
    }

    /** Mocked mode: no API server runs, so a placeholder server and token. */
    static String mock(ManagedCluster cluster, Role role) {
        String endpoint = cluster.getEndpoint() != null ? cluster.getEndpoint() : "https://localhost:6443";
        return encode(cluster, role, """
                    insecure-skip-tls-verify: true
                    server: %s
                """.formatted(endpoint), """
                    token: floci-az-mock-token
                """);
    }

    static String userName(ManagedCluster cluster, Role role) {
        return role.credentialName() + "_" + cluster.getResourceGroup() + "_" + cluster.getName();
    }

    private static String encode(ManagedCluster cluster, Role role, String clusterFields, String userFields) {
        String name = cluster.getName();
        String user = userName(cluster, role);
        String yaml = "apiVersion: v1\n"
                + "clusters:\n"
                + "- cluster:\n" + clusterFields
                + "  name: " + name + "\n"
                + "contexts:\n"
                + "- context:\n"
                + "    cluster: " + name + "\n"
                + "    user: " + user + "\n"
                + "  name: " + name + "\n"
                + "current-context: " + name + "\n"
                + "kind: Config\n"
                + "preferences: {}\n"
                + "users:\n"
                + "- name: " + user + "\n"
                + "  user:\n" + userFields;
        return Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
