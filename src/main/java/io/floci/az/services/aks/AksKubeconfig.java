package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Kubeconfigs handed out by the credential operations, base64-encoded as AKS returns them. */
final class AksKubeconfig {

    private AksKubeconfig() {
    }

    /** The cluster's k3s admin credential, addressed at the endpoint clients reach the API server on. */
    static String admin(ManagedCluster cluster) {
        String yaml = String.format("""
                apiVersion: v1
                clusters:
                - cluster:
                    certificate-authority-data: %s
                    server: %s
                  name: default
                contexts:
                - context:
                    cluster: default
                    user: default
                  name: default
                current-context: default
                kind: Config
                preferences: {}
                users:
                - name: default
                  user:
                    client-certificate-data: %s
                    client-key-data: %s
                """, cluster.getCaData(), cluster.getEndpoint(), cluster.getClientCertificateData(),
                cluster.getClientKeyData());
        return Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
