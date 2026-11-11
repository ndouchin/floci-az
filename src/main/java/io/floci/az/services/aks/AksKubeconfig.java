package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

    /**
     * The {@code clusterUser} credential of an Entra cluster: an exec user running {@code kubelogin get-token} with the
     * AKS applications and {@code --login devicecode}, the default AKS writes from Kubernetes 1.24
     * ({@code kubelogin-authentication.md}). {@code authorityHost}, when set, is appended with
     * {@code --disable-instance-discovery} so kubelogin signs in at floci-az instead of Microsoft Entra.
     */
    static String entraExec(ManagedCluster cluster, String tenantId, String environment, String authorityHost) {
        List<String> args = new ArrayList<>(List.of("get-token", "--environment", environment,
                "--server-id", AksEntra.SERVER_APP_ID, "--client-id", AksEntra.CLIENT_APP_ID,
                "--tenant-id", tenantId, "--login", "devicecode"));
        if (authorityHost != null) {
            args.addAll(List.of("--authority-host", authorityHost, "--disable-instance-discovery"));
        }
        StringBuilder user = new StringBuilder("""
                    exec:
                      apiVersion: client.authentication.k8s.io/v1beta1
                      args:
                """);
        args.forEach(arg -> user.append("      - ").append(arg).append('\n'));
        user.append("""
                      command: kubelogin
                      env: null
                      installHint: |2

                        kubelogin is not installed which is required to connect to AAD enabled cluster.

                        To learn more, please go to https://aka.ms/aks/kubelogin
                      provideClusterInfo: false
                """);
        return encode(cluster, Role.USER, entraClusterFields(cluster), user.toString());
    }

    /**
     * The legacy {@code azure} auth-provider form {@code format=azure} asks for, with the keys kubelogin's
     * {@code convert-kubeconfig} reads; {@code config-mode: "1"} means the audience is the server application itself,
     * not the legacy {@code spn:} form.
     */
    static String entraAuthProvider(ManagedCluster cluster, String tenantId, String environment) {
        return encode(cluster, Role.USER, entraClusterFields(cluster), """
                    auth-provider:
                      config:
                        apiserver-id: %s
                        client-id: %s
                        config-mode: '1'
                        environment: %s
                        tenant-id: %s
                      name: azure
                """.formatted(AksEntra.SERVER_APP_ID, AksEntra.CLIENT_APP_ID, environment, tenantId));
    }

    private static String entraClusterFields(ManagedCluster cluster) {
        if (cluster.getCaData() == null || cluster.getEndpoint() == null) {
            String endpoint = cluster.getEndpoint() != null ? cluster.getEndpoint() : "https://localhost:6443";
            return """
                        insecure-skip-tls-verify: true
                        server: %s
                    """.formatted(endpoint);
        }
        return """
                    certificate-authority-data: %s
                    server: %s
                """.formatted(cluster.getCaData(), cluster.getEndpoint());
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
