package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How the pods of a Workload Identity cluster reach floci-az: a selector-less Service {@code floci-az} in
 * {@code kube-system} whose EndpointSlice points at floci-az's address (port 443 and 80 to floci-az's port, where its
 * TLS proxy tells HTTPS from HTTP), floci-az's CA in the ConfigMap {@code floci-az-ca}, and in "Microsoft hostnames"
 * mode a {@code coredns-custom} override that answers {@code login.microsoftonline.com} and the cluster's issuer host
 * with that Service. k3s's CoreDNS imports {@code /etc/coredns/custom/*.override} into its main server block.
 */
final class AksWorkloadIdentityNetwork {

    static final String SERVICE_NAME = "floci-az";
    static final String SERVICE_FQDN = SERVICE_NAME + ".kube-system.svc.cluster.local";
    static final String NETWORK_MANIFEST = "floci-aks-workload-identity-network.yaml";
    static final String CA_MANIFEST = "floci-aks-ca.yaml";
    static final String COREDNS_MANIFEST = "floci-aks-coredns-custom.yaml";
    static final String ENTRA_HOST = "login.microsoftonline.com";

    private AksWorkloadIdentityNetwork() {
    }

    /** The manifests for a cluster whose pods reach floci-az at {@code address}:{@code port}. */
    static Map<String, String> manifests(ManagedCluster cluster, String address, int port, String caPem,
                                         boolean microsoftHostnames) {
        Map<String, String> manifests = new LinkedHashMap<>();
        if (address != null) {
            manifests.put(NETWORK_MANIFEST, service(address, port));
            if (microsoftHostnames) {
                manifests.put(COREDNS_MANIFEST, coreDnsOverride(cluster));
            }
        }
        if (caPem != null && !caPem.isBlank()) {
            manifests.put(CA_MANIFEST, caConfigMap(caPem));
        }
        return manifests;
    }

    static String service(String address, int port) {
        return """
                apiVersion: v1
                kind: Service
                metadata:
                  name: %1$s
                  namespace: kube-system
                  labels:
                    kubernetes.azure.com/managed-by: floci-az
                spec:
                  ports:
                  - name: https
                    port: 443
                    protocol: TCP
                    targetPort: %3$d
                  - name: http
                    port: 80
                    protocol: TCP
                    targetPort: %3$d
                ---
                apiVersion: discovery.k8s.io/v1
                kind: EndpointSlice
                metadata:
                  name: %1$s
                  namespace: kube-system
                  labels:
                    kubernetes.io/service-name: %1$s
                    endpointslice.kubernetes.io/managed-by: floci-az
                addressType: %4$s
                endpoints:
                - addresses:
                  - "%2$s"
                ports:
                - name: https
                  port: %3$d
                  protocol: TCP
                - name: http
                  port: %3$d
                  protocol: TCP
                """.formatted(SERVICE_NAME, address, port, address.indexOf(':') >= 0 ? "IPv6" : "IPv4");
    }

    static String coreDnsOverride(ManagedCluster cluster) {
        StringBuilder rules = new StringBuilder();
        rules.append("    rewrite name exact ").append(ENTRA_HOST).append(' ').append(SERVICE_FQDN).append('\n');
        if (cluster.oidcIssuerEnabled()) {
            String issuerHost = URI.create(cluster.getOidcIssuerUrl()).getHost();
            rules.append("    rewrite name exact ").append(issuerHost).append(' ').append(SERVICE_FQDN).append('\n');
        }
        return """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: coredns-custom
                  namespace: kube-system
                  labels:
                    kubernetes.azure.com/managed-by: floci-az
                data:
                  floci-az.override: |
                """ + rules;
    }

    static String caConfigMap(String caPem) {
        StringBuilder yaml = new StringBuilder("""
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: floci-az-ca
                  namespace: kube-system
                  labels:
                    kubernetes.azure.com/managed-by: floci-az
                data:
                  ca.crt: |
                """);
        caPem.strip().lines().forEach(line -> yaml.append("    ").append(line).append('\n'));
        return yaml.toString();
    }
}
