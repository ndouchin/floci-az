package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.WebhookCertificates;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Workload Identity add-on as upstream ships it ({@code deploy/azure-wi-webhook.yaml} v1.6.3) in {@code kube-system},
 * with certificates cert-controller keeps ({@code rotator.go}: {@code validCACert}, {@code validServerCert}).
 */
class AksWorkloadIdentityWebhookTest {

    private static final WebhookCertificates CERTS = AksWorkloadIdentityWebhook.certificates();

    private static X509Certificate certificate(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> dnsNames(X509Certificate certificate) throws Exception {
        List<String> names = new ArrayList<>();
        certificate.getSubjectAlternativeNames().forEach(name -> names.add(String.valueOf(name.get(1))));
        return names;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> documents(String yaml) {
        List<Map<String, Object>> documents = new ArrayList<>();
        new Yaml().loadAll(yaml).forEach(document -> documents.add((Map<String, Object>) document));
        return documents;
    }

    private static Map<String, Object> kind(List<Map<String, Object>> documents, String kind, String name) {
        return documents.stream().filter(d -> kind.equals(d.get("kind")))
                .filter(d -> name.equals(((Map<?, ?>) d.get("metadata")).get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> podSpec(List<Map<String, Object>> documents) {
        Map<String, Object> deployment = kind(documents, "Deployment", "azure-wi-webhook-controller-manager");
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) deployment.get("spec"))
                .get("template")).get("spec");
    }

    @Test
    void theCertificatesAreTheOnesCertControllerKeeps() throws Exception {
        X509Certificate ca = certificate(CERTS.caCert());
        X509Certificate server = certificate(CERTS.tlsCert());
        Date lookahead = Date.from(Instant.now().plus(Duration.ofDays(90)));
        ca.verify(ca.getPublicKey());
        server.verify(ca.getPublicKey());
        assertTrue(ca.getSubjectX500Principal().getName().contains("CN=azure-workload-identity-ca"));
        assertTrue(ca.getSubjectX500Principal().getName().contains("O=azure-workload-identity"));
        assertEquals(List.of("azure-workload-identity-ca"), dnsNames(ca));
        assertEquals(List.of("azure-wi-webhook-webhook-service.kube-system.svc"), dnsNames(server));
        assertTrue(ca.getBasicConstraints() >= 0);
        ca.checkValidity(lookahead);
        server.checkValidity(lookahead);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAddOnRunsInKubeSystemWithThePreSeededCertificate() {
        List<Map<String, Object>> documents = documents(AksWorkloadIdentityWebhook.manifest(
                "mcr.microsoft.com/oss/v2/azure/workload-identity/webhook:v1.6.3", "t1", CERTS, null));
        for (Map<String, Object> document : documents) {
            Object namespace = ((Map<?, ?>) document.get("metadata")).get("namespace");
            assertTrue(namespace == null || "kube-system".equals(namespace), document.toString());
        }
        Map<String, Object> config = kind(documents, "ConfigMap", "azure-wi-webhook-config");
        assertEquals(Map.of("AZURE_ENVIRONMENT", "AzurePublicCloud", "AZURE_TENANT_ID", "t1"), config.get("data"));
        Map<String, Object> secret = kind(documents, "Secret", "azure-wi-webhook-server-cert");
        assertEquals(CERTS.tlsCert(), new String(Base64.getDecoder().decode((String) ((Map<String, Object>)
                secret.get("data")).get("tls.crt")), StandardCharsets.UTF_8));
        assertEquals("mcr.microsoft.com/oss/v2/azure/workload-identity/webhook:v1.6.3",
                ((List<Map<String, Object>>) podSpec(documents).get("containers")).getFirst().get("image"));
        Map<String, Object> webhook = ((List<Map<String, Object>>) kind(documents, "MutatingWebhookConfiguration",
                "azure-wi-webhook-mutating-webhook-configuration").get("webhooks")).getFirst();
        Map<String, Object> clientConfig = (Map<String, Object>) webhook.get("clientConfig");
        assertEquals(CERTS.caCert(), new String(Base64.getDecoder().decode((String) clientConfig.get("caBundle")),
                StandardCharsets.UTF_8));
        assertEquals("kube-system", ((Map<String, Object>) clientConfig.get("service")).get("namespace"));
        assertEquals(Map.of("matchLabels", Map.of("azure.workload.identity/use", "true")),
                webhook.get("objectSelector"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void authorityModeNamesFlociAzAsAnAzureStackCloud() {
        List<Map<String, Object>> documents = documents(AksWorkloadIdentityWebhook.manifest("image", "t1", CERTS,
                "https://floci-az.kube-system.svc.cluster.local/"));
        Map<String, String> config = (Map<String, String>) kind(documents, "ConfigMap", "azure-wi-webhook-config")
                .get("data");
        assertEquals("AzureStackCloud", config.get("AZURE_ENVIRONMENT"));
        assertEquals("/etc/azure-env/env.json", config.get("AZURE_ENVIRONMENT_FILEPATH"));
        Map<String, String> environment = (Map<String, String>) kind(documents, "ConfigMap",
                "azure-wi-webhook-environment").get("data");
        assertTrue(environment.get("env.json").contains("\"activeDirectoryEndpoint\": "
                + "\"https://floci-az.kube-system.svc.cluster.local/\""));
        assertEquals(2, ((List<?>) podSpec(documents).get("volumes")).size());
    }
}
