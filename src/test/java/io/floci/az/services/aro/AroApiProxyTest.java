package io.floci.az.services.aro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aro.oauth.IdentityProvider;
import io.floci.az.services.aro.oauth.OAuthToken;
import io.floci.az.services.aro.oauth.OAuthTokenStore;
import io.floci.az.services.aro.oauth.OAuthTokens;
import io.floci.az.services.aro.oauth.OAuthUser;
import io.floci.az.services.aro.oauth.OpenShiftOAuthServer;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.Http1xServerConnection;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The API proxy against an in-process HTTPS upstream that stands in for MicroShift: it echoes what it received,
 * including the client certificate it was shown, streams a watch on {@code /watch} and echoes bytes after a 101 on
 * {@code /upgrade}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AroApiProxyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = OAuthTokens.newToken(new SecureRandom());
    private static final String CONSOLE = "https://console-openshift-console.apps.demo.example:7602";

    private Vertx vertx;
    private Pki localhostSigner;
    private Pki externalSigner;
    private Pki clientCa;
    private Pki aggregatorSigner;
    private Pki strangerCa;
    private Leaf adminCert;
    private Leaf strangerCert;
    private HttpServer upstream;
    private AroUpstream upstreamClients;
    private AroApiProxy proxy;
    private int proxyPort;
    private final CompletableFuture<Void> releaseWatch = new CompletableFuture<>();
    private final OAuthTokenStore tokens = OAuthTokenStore.inMemory();
    private final AtomicReference<String> console = new AtomicReference<>(CONSOLE);

    @BeforeAll
    void start() throws Exception {
        vertx = Vertx.vertx();
        localhostSigner = Pki.ca("kube-apiserver-localhost-signer");
        externalSigner = Pki.ca("kube-apiserver-external-signer");
        clientCa = Pki.ca("admin-kubeconfig-signer");
        aggregatorSigner = Pki.ca("aggregator-signer");
        strangerCa = Pki.ca("stranger");
        Pki upstreamSigner = Pki.ca("upstream");
        Leaf localhostServing = localhostSigner.leaf("localhost", List.of(), List.of("localhost", "127.0.0.1"), true);
        Leaf externalServing = externalSigner.leaf("api.demo.example", List.of(), List.of("api.demo.example",
                "floci-az-aro-1"), true);
        Leaf aggregatorClient = aggregatorSigner.leaf("system:openshift-aggregator", List.of(), List.of(), false);
        adminCert = clientCa.leaf("system:admin", List.of("system:masters"), List.of(), false);
        strangerCert = strangerCa.leaf("intruder", List.of("system:masters"), List.of(), false);
        Leaf upstreamServing = upstreamSigner.leaf("localhost", List.of(), List.of("localhost"), true);

        upstream = vertx.createHttpServer(new HttpServerOptions().setSsl(true).setPort(0).setHost("127.0.0.1")
                        .setKeyCertOptions(new PemKeyCertOptions().addCertValue(Buffer.buffer(upstreamServing.chainPem()))
                                .addKeyValue(Buffer.buffer(upstreamServing.keyPem())))
                        .setTrustOptions(new PemTrustOptions().addCertValue(Buffer.buffer(aggregatorSigner.certPem())))
                        .setClientAuth(ClientAuth.REQUEST))
                .requestHandler(this::upstreamHandler);
        int upstreamPort = upstream.listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS)
                .actualPort();

        tokens.put(OAuthTokens.objectName(TOKEN), new OAuthToken("kube:admin", "uid-1", "kube:admin",
                "openshift-challenging-client", List.of("user:full"), Instant.now(), 86400), Instant.now());
        OpenShiftOAuthServer oauth = new OpenShiftOAuthServer(List.of(new AlwaysValid()), tokens,
                new OpenShiftOAuthServer.Settings(86400, null, null), Clock.systemUTC());
        upstreamClients = new AroUpstream(vertx, "127.0.0.1", upstreamPort,
                AroProxyTls.KeyMaterial.read(aggregatorClient.chainPem(), aggregatorClient.keyPem()));
        try (ServerSocket socket = new ServerSocket(0)) {
            proxyPort = socket.getLocalPort();
        }
        proxy = new AroApiProxy(vertx, proxyPort, new AroApiProxy.Certificates(localhostServing.chainPem(),
                localhostServing.keyPem(), externalServing.chainPem(), externalServing.keyPem(), clientCa.certPem(),
                aggregatorClient.chainPem(), aggregatorClient.keyPem()), upstreamClients, oauth, console::get);
        proxy.listen().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @AfterAll
    void stop() throws Exception {
        releaseWatch.complete(null);
        proxy.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        upstreamClients.close();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void aMintedTokenIsAssertedOverTheAggregatorConnectionWithoutItsAuthorization() throws Exception {
        JsonNode echo = echo(client(null), Map.of("Authorization", "Bearer " + TOKEN, "X-Remote-User", "evil",
                "X-Remote-Group", "system:masters", "Impersonate-User", "someone"));
        assertEquals("system:openshift-aggregator", echo.path("clientCn").asText());
        assertEquals("[\"kube:admin\"]", echo.path("headers").path("x-remote-user").toString());
        assertEquals("[\"system:cluster-admins\"]", echo.path("headers").path("x-remote-group").toString());
        assertEquals("[\"user:full\"]",
                echo.path("headers").path("x-remote-extra-scopes.authorization.openshift.io").toString());
        assertFalse(echo.path("headers").has("authorization"));
        assertEquals("[\"someone\"]", echo.path("headers").path("impersonate-user").toString());
    }

    @Test
    void aClientCertificateFromTheClientCaIsAssertedAsItsCommonNameAndOrganizations() throws Exception {
        JsonNode echo = echo(client(adminCert), Map.of("X-Remote-User", "evil"));
        assertEquals("system:openshift-aggregator", echo.path("clientCn").asText());
        assertEquals("[\"system:admin\"]", echo.path("headers").path("x-remote-user").toString());
        assertEquals("[\"system:masters\"]", echo.path("headers").path("x-remote-group").toString());
    }

    @Test
    void anUnknownBearerTokenPassesUnchangedWithoutAClientCertificate() throws Exception {
        JsonNode echo = echo(client(null), Map.of("Authorization", "Bearer eyJhbGciOi.sa.token",
                "X-Remote-User", "evil", "X-Remote-Extra-Scopes", "user:full"));
        assertTrue(echo.path("clientCn").isNull(), echo.toString());
        assertEquals("[\"Bearer eyJhbGciOi.sa.token\"]", echo.path("headers").path("authorization").toString());
        assertFalse(echo.path("headers").has("x-remote-user"));
        assertFalse(echo.path("headers").has("x-remote-extra-scopes"));

        JsonNode unknownSha = echo(client(null), Map.of("Authorization", "Bearer sha256~unknown"));
        assertTrue(unknownSha.path("clientCn").isNull());
        assertEquals("[\"Bearer sha256~unknown\"]", unknownSha.path("headers").path("authorization").toString());
    }

    @Test
    void anUntrustedClientCertificateWithoutATokenIsUnauthorized() throws Exception {
        HttpResponse<String> response = client(strangerCert).send(request("/api/v1/nodes", Map.of()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals("Unauthorized", MAPPER.readTree(response.body()).path("reason").asText());
    }

    @Test
    void requestBodiesAndQueriesReachTheApiServer() throws Exception {
        HttpResponse<String> response = client(adminCert).send(request("/api/v1/namespaces?dryRun=All", Map.of())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"kind\":\"Namespace\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        JsonNode echo = MAPPER.readTree(response.body());
        assertEquals("POST", echo.path("method").asText());
        assertEquals("/api/v1/namespaces?dryRun=All", echo.path("uri").asText());
        assertEquals("{\"kind\":\"Namespace\"}", echo.path("body").asText());
    }

    @Test
    void aChunkedWatchStreamsBeforeTheApiServerEndsIt() throws Exception {
        HttpResponse<InputStream> response = client(adminCert).send(request("/watch", Map.of()).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
        String first = CompletableFuture.supplyAsync(() -> readLine(reader)).get(10, TimeUnit.SECONDS);
        assertEquals("{\"type\":\"ADDED\"}", first);
        assertFalse(releaseWatch.isDone());
        releaseWatch.complete(null);
        assertEquals("{\"type\":\"DELETED\"}", CompletableFuture.supplyAsync(() -> readLine(reader))
                .get(10, TimeUnit.SECONDS));
    }

    @Test
    void aWebSocketUpgradeIsTunnelledBothWaysWithTheAssertedUser() throws Exception {
        String reply = upgrade("GET", "websocket", "Authorization: Bearer " + TOKEN + "\r\n");
        assertTrue(reply.startsWith("HTTP/1.1 101"), reply);
        assertTrue(reply.contains("X-Seen-User: kube:admin"), reply);
        assertTrue(reply.contains("X-Seen-Authorization: -"), reply);
        assertTrue(reply.endsWith("ping\n"), reply);
    }

    @Test
    void aSpdyUpgradeOverPostIsTunnelledToo() throws Exception {
        String reply = upgrade("POST", "SPDY/3.1", "Authorization: Bearer eyJ.sa\r\n");
        assertTrue(reply.startsWith("HTTP/1.1 101"), reply);
        assertTrue(reply.contains("Upgrade: SPDY/3.1"), reply);
        assertTrue(reply.contains("X-Seen-User: -"), reply);
        assertTrue(reply.contains("X-Seen-Authorization: Bearer eyJ.sa"), reply);
        assertTrue(reply.endsWith("ping\n"), reply);
    }

    @Test
    void theOAuthMetadataComesFromTheProxyWithTheIssuerTheClientUsed() throws Exception {
        HttpResponse<String> response = client(null).send(request("/.well-known/oauth-authorization-server",
                Map.of()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("https://localhost:" + proxyPort, MAPPER.readTree(response.body()).path("issuer").asText());
    }

    @Test
    void everyConsolePathRedirectsToTheConsoleOrFailsWithoutOne() throws Exception {
        for (String path : List.of("/console", "/console/k8s/cluster/projects", "/consoles")) {
            HttpResponse<String> redirect = client(null).send(request(path, Map.of())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(302, redirect.statusCode(), path);
            assertEquals(CONSOLE, redirect.headers().firstValue("Location").orElseThrow());
        }
        console.set(null);
        try {
            HttpResponse<String> missing = client(null).send(request("/console", Map.of()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(500, missing.statusCode());
            assertEquals("redirection failed: console URL not found\n", missing.body());
        } finally {
            console.set(CONSOLE);
        }
    }

    @Test
    void usersTildeAnswersForAnAssertedUserAndIsForwardedOtherwise() throws Exception {

        HttpResponse<String> user = client(null).send(request(AroApiProxy.CURRENT_USER_PATH,
                Map.of("Authorization", "Bearer " + TOKEN)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, user.statusCode());
        assertEquals("kube:admin", MAPPER.readTree(user.body()).path("metadata").path("name").asText());

        JsonNode forwarded = echo(client(null), Map.of(), AroApiProxy.CURRENT_USER_PATH);
        assertEquals(AroApiProxy.CURRENT_USER_PATH, forwarded.path("uri").asText());
    }

    @Test
    void deletingTheTokenObjectRevokesIt() throws Exception {
        String token = OAuthTokens.newToken(new SecureRandom());
        tokens.put(OAuthTokens.objectName(token), new OAuthToken("kube:admin", "uid-1", "kube:admin",
                "openshift-challenging-client", List.of("user:full"), Instant.now(), 86400), Instant.now());
        HttpResponse<String> deleted = client(null).send(request(AroApiProxy.TOKENS_PATH
                        + OAuthTokens.objectName(token), Map.of("Authorization", "Bearer " + token,
                        "Content-Type", "application/json"))
                .method("DELETE", HttpRequest.BodyPublishers.ofString("{\"kind\":\"DeleteOptions\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, deleted.statusCode(), deleted.body());
        JsonNode after = echo(client(null), Map.of("Authorization", "Bearer " + token));
        assertTrue(after.path("clientCn").isNull());
        assertEquals("[\"Bearer " + token + "\"]", after.path("headers").path("authorization").toString());
    }

    @Test
    void theServingCertificateFollowsSniAndOnlyHttp11IsOffered() throws Exception {
        assertEquals("CN=localhost", servedSubject(null));
        assertEquals("CN=api.demo.example", servedSubject("api.demo.example"));
        HttpResponse<String> response = HttpClient.newBuilder().sslContext(context(null))
                .version(HttpClient.Version.HTTP_2).build()
                .send(request("/.well-known/oauth-authorization-server", Map.of()).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(HttpClient.Version.HTTP_1_1, response.version());
    }

    // ── Upstream stand-in ────────────────────────────────────────────────────

    private void upstreamHandler(HttpServerRequest req) {
        if (req.path().equals("/watch")) {
            req.response().setChunked(true).putHeader("Content-Type", "application/json");
            req.response().write("{\"type\":\"ADDED\"}\n");
            releaseWatch.thenRun(() -> vertx.runOnContext(ignored -> req.response().end("{\"type\":\"DELETED\"}\n")));
            return;
        }
        if (req.path().equals("/upgrade")) {
            String user = Optional.ofNullable(req.getHeader("X-Remote-User")).orElse("-");
            String authorization = Optional.ofNullable(req.getHeader("Authorization")).orElse("-");
            String protocol = req.getHeader("Upgrade");
            ((Http1xServerConnection) req.connection()).netSocket().onSuccess(socket -> {
                socket.write("HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: " + protocol
                        + "\r\nX-Seen-User: " + user + "\r\nX-Seen-Authorization: " + authorization + "\r\n\r\n");
                socket.handler(socket::write);
            });
            return;
        }
        req.body().onSuccess(body -> {
            Map<String, Object> echo = new LinkedHashMap<>();
            echo.put("method", req.method().name());
            echo.put("uri", req.uri());
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (String name : req.headers().names()) {
                headers.put(name.toLowerCase(), req.headers().getAll(name));
            }
            echo.put("headers", headers);
            echo.put("body", body.toString());
            String clientCn = null;
            try {
                X509Certificate peer = (X509Certificate) req.connection().sslSession().getPeerCertificates()[0];
                clientCn = peer.getSubjectX500Principal().getName().replace("CN=", "");
            } catch (Exception expected) {
                // No client certificate on this connection: the echo says so with a null CN.
            }
            echo.put("clientCn", clientCn);
            try {
                req.response().putHeader("Content-Type", "application/json").end(MAPPER.writeValueAsString(echo));
            } catch (Exception e) {
                req.response().setStatusCode(500).end(e.toString());
            }
        });
    }

    // ── Client side ──────────────────────────────────────────────────────────

    private JsonNode echo(HttpClient client, Map<String, String> headers) throws Exception {
        return echo(client, headers, "/api/v1/nodes");
    }

    private JsonNode echo(HttpClient client, Map<String, String> headers, String path) throws Exception {
        HttpResponse<String> response = client.send(request(path, headers).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body());
    }

    private HttpRequest.Builder request(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://localhost:" + proxyPort + path))
                .timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        return builder;
    }

    private HttpClient client(Leaf clientCertificate) throws Exception {
        return HttpClient.newBuilder().sslContext(context(clientCertificate)).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private SSLContext context(Leaf clientCertificate) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("localhost", localhostSigner.cert());
        trust.setCertificateEntry("external", externalSigner.cert());
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(trust);
        KeyManager[] keys = null;
        if (clientCertificate != null) {
            KeyStore identity = KeyStore.getInstance("PKCS12");
            identity.load(null, null);
            identity.setKeyEntry("client", clientCertificate.keys().getPrivate(), new char[0],
                    new X509Certificate[]{clientCertificate.cert()});
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(identity, new char[0]);
            keys = new KeyManager[]{new AlwaysPresent((X509KeyManager) factory.getKeyManagers()[0])};
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys, trusts.getTrustManagers(), null);
        return context;
    }

    private String upgrade(String method, String protocol, String extraHeaders) throws Exception {
        try (SSLSocket socket = (SSLSocket) context(null).getSocketFactory().createSocket("localhost", proxyPort)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write((method + " /upgrade HTTP/1.1\r\nHost: localhost:" + proxyPort + "\r\nConnection: Upgrade\r\n"
                    + "Upgrade: " + protocol + "\r\nX-Remote-User: evil\r\n" + extraHeaders + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                int b = in.read();
                if (b < 0) {
                    return head.toString(StandardCharsets.US_ASCII);
                }
                head.write(b);
            }
            out.write("ping\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] echoed = in.readNBytes(5);
            return head.toString(StandardCharsets.US_ASCII) + new String(echoed, StandardCharsets.US_ASCII);
        }
    }

    private String servedSubject(String serverName) throws Exception {
        try (SSLSocket socket = (SSLSocket) context(null).getSocketFactory().createSocket("localhost", proxyPort)) {
            if (serverName != null) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setServerNames(List.of(new SNIHostName(serverName)));
                socket.setSSLParameters(parameters);
            }
            socket.startHandshake();
            return ((X509Certificate) socket.getSession().getPeerCertificates()[0]).getSubjectX500Principal()
                    .getName();
        }
    }

    private static String readLine(BufferedReader reader) {
        try {
            return reader.readLine();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Presents its certificate whatever issuers the server names, as a client with a stray certificate does. */
    private static final class AlwaysPresent extends X509ExtendedKeyManager {
        private final X509KeyManager delegate;

        AlwaysPresent(X509KeyManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return "client";
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return "client";
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{"client"};
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[0];
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return delegate.getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return delegate.getPrivateKey(alias);
        }
    }

    private static final class AlwaysValid implements IdentityProvider {
        @Override
        public String name() {
            return "kube:admin";
        }

        @Override
        public boolean challenge() {
            return true;
        }

        @Override
        public boolean login() {
            return true;
        }

        @Override
        public Optional<OAuthUser> authenticate(String username, String password) {
            return Optional.empty();
        }

        @Override
        public Optional<OAuthUser> revalidate(OAuthToken token) {
            return Optional.of(new OAuthUser(token.userName(), token.userUid(), name(),
                    List.of("system:cluster-admins")));
        }
    }

    // ── Test PKI ─────────────────────────────────────────────────────────────

    record Leaf(X509Certificate cert, KeyPair keys, X509Certificate issuer) {
        String chainPem() throws Exception {
            return pem(cert) + pem(issuer);
        }

        String keyPem() throws Exception {
            return pem(keys.getPrivate());
        }
    }

    record Pki(X509Certificate cert, KeyPair keys) {

        static Pki ca(String name) throws Exception {
            KeyPair keys = keyPair();
            X500Name subject = new X500Name("CN=" + name);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject,
                    BigInteger.valueOf(System.nanoTime()), Date.from(Instant.now().minusSeconds(60)),
                    Date.from(Instant.now().plus(Duration.ofDays(1))), subject, keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            return new Pki(new JcaX509CertificateConverter().getCertificate(builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()))), keys);
        }

        String certPem() throws Exception {
            return pem(cert);
        }

        Leaf leaf(String commonName, List<String> organizations, List<String> dnsNames, boolean server)
                throws Exception {
            KeyPair leafKeys = keyPair();
            StringBuilder subject = new StringBuilder();
            organizations.forEach(o -> subject.append("O=").append(o).append(','));
            subject.append("CN=").append(commonName);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    new X500Name(cert.getSubjectX500Principal().getName()), BigInteger.valueOf(System.nanoTime()),
                    Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plus(Duration.ofDays(1))),
                    new X500Name(subject.toString()), leafKeys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(
                    server ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
            if (!dnsNames.isEmpty()) {
                builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(dnsNames.stream()
                        .map(name -> name.matches("[0-9.]+") ? new GeneralName(GeneralName.iPAddress, name)
                                : new GeneralName(GeneralName.dNSName, name))
                        .toArray(GeneralName[]::new)));
            }
            return new Leaf(new JcaX509CertificateConverter().getCertificate(builder.build(
                    new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()))), leafKeys, cert);
        }

        private static KeyPair keyPair() throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        }
    }

    private static String pem(Object value) throws Exception {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(value);
        }
        return out.toString();
    }
}
