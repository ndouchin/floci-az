package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;

/**
 * A minimal Kubernetes API client built from a kubeconfig the credential operations returned, so a test proves that
 * kubeconfig works from outside the cluster: the server URL, the CA and the client certificate.
 */
final class KubeconfigClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A response: its status and body. */
    record Reply(int status, String body) {
        JsonNode json() throws Exception {
            return MAPPER.readTree(body);
        }
    }

    private final String server;
    private final HttpClient http;
    private final String bearer;

    private KubeconfigClient(String server, HttpClient http, String bearer) {
        this.server = server;
        this.http = http;
        this.bearer = bearer;
    }

    /**
     * A client that trusts the server and CA of {@code encoded} but authenticates with the bearer {@code token}, as
     * kubelogin's exec credential does.
     */
    static KubeconfigClient withBearer(String encoded, String token) throws Exception {
        String yaml = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        byte[] ca = Base64.getDecoder().decode(field(yaml, "certificate-authority-data"));
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        int i = 0;
        for (Certificate c : certificates.generateCertificates(new ByteArrayInputStream(ca))) {
            trust.setCertificateEntry("ca" + i++, c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        HttpClient http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
        return new KubeconfigClient(field(yaml, "server"), http, token);
    }

    static KubeconfigClient fromBase64(String encoded) throws Exception {
        String yaml = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        String server = field(yaml, "server");
        byte[] ca = Base64.getDecoder().decode(field(yaml, "certificate-authority-data"));
        byte[] cert = Base64.getDecoder().decode(field(yaml, "client-certificate-data"));
        byte[] key = Base64.getDecoder().decode(field(yaml, "client-key-data"));

        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        int i = 0;
        for (Certificate c : certificates.generateCertificates(new ByteArrayInputStream(ca))) {
            trust.setCertificateEntry("ca" + i++, c);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);

        Collection<? extends Certificate> chain = certificates.generateCertificates(new ByteArrayInputStream(cert));
        KeyStore identity = KeyStore.getInstance("PKCS12");
        identity.load(null, null);
        identity.setKeyEntry("client", privateKey(new String(key, StandardCharsets.UTF_8)), new char[0],
                chain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(identity, new char[0]);

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        HttpClient http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
        return new KubeconfigClient(server, http, null);
    }

    static String field(String yaml, String name) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(name + ":")) {
                return trimmed.substring(name.length() + 1).trim();
            }
        }
        throw new IllegalArgumentException("kubeconfig has no " + name + ":\n" + yaml);
    }

    private static PrivateKey privateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair pair) {
                return converter.getKeyPair(pair).getPrivate();
            }
            return converter.getPrivateKey((PrivateKeyInfo) parsed);
        }
    }

    JsonNode get(String path) throws Exception {
        return MAPPER.readTree(text(path));
    }

    String text(String path) throws Exception {
        Reply reply = exchange("GET", path, null);
        if (reply.status() != 200) {
            throw new IllegalStateException("GET " + path + " answered " + reply.status() + ": " + reply.body());
        }
        return reply.body();
    }

    int send(String method, String path, String json) throws Exception {
        return exchange(method, path, json).status();
    }

    Reply exchange(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        request.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }
}
