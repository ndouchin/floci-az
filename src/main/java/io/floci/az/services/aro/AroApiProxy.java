package io.floci.az.services.aro;

import io.floci.az.services.aro.AroProxyTls.CertificateUser;
import io.floci.az.services.aro.AroProxyTls.KeyMaterial;
import io.floci.az.services.aro.oauth.OAuthRequest;
import io.floci.az.services.aro.oauth.OAuthResponse;
import io.floci.az.services.aro.oauth.OAuthTokens;
import io.floci.az.services.aro.oauth.OpenShiftOAuthServer;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ClientAuth;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.Http1xServerConnection;
import io.vertx.core.net.JdkSSLEngineOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.TrustOptions;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * One ARO cluster's API endpoint: a TLS listener in floci-az in front of the MicroShift API server, because MicroShift
 * serves no OAuth metadata and takes no token webhook. It serves the OpenShift OAuth server and forwards every other
 * request to MicroShift:
 *
 * <ul>
 *   <li>a request with a token the OAuth server minted, or with a client certificate from MicroShift's client CA, is
 *       forwarded over the asserting pool with {@code X-Remote-User}, one {@code X-Remote-Group} per group and the
 *       token's scopes as {@code X-Remote-Extra-Scopes.authorization.openshift.io}, without its
 *       {@code Authorization};</li>
 *   <li>any other request (ServiceAccount tokens, unknown tokens, anonymous) is forwarded unchanged over the pool
 *       without a client certificate;</li>
 *   <li>incoming {@code X-Remote-*} headers are always dropped; {@code Impersonate-*} headers pass, and kube-apiserver
 *       authorizes them against the asserted user;</li>
 *   <li>bodies stream both ways with no idle timeout, so watches work, and any {@code Upgrade} request (WebSocket for
 *       exec, attach, cp and port-forward, SPDY/3.1 for older clients) becomes a byte tunnel;</li>
 *   <li>{@code DELETE .../oauthaccesstokens/<name>} revokes, {@code GET .../users/~} answers the virtual User of an
 *       asserted user, and {@code GET /console} redirects to the console, as OpenShift's API server does.</li>
 * </ul>
 *
 * <p>HTTP/1.1 only (ALPN offers nothing else; Go clients fall back from h2). The listener serves MicroShift's own
 * serving certificates, so every kubeconfig MicroShift issues keeps verifying.</p>
 */
final class AroApiProxy {

    private static final Logger LOG = Logger.getLogger(AroApiProxy.class);

    static final String TOKENS_PATH = "/apis/oauth.openshift.io/v1/oauthaccesstokens/";
    static final String CURRENT_USER_PATH = "/apis/user.openshift.io/v1/users/~";
    static final String SCOPES_EXTRA = "Scopes.authorization.openshift.io";
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-connection",
            "transfer-encoding", "te", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate", "host",
            "expect");
    private static final String UNAUTHORIZED = "{\"kind\":\"Status\",\"apiVersion\":\"v1\",\"metadata\":{},"
            + "\"status\":\"Failure\",\"message\":\"Unauthorized\",\"reason\":\"Unauthorized\",\"code\":401}";

    /** MicroShift's PEM files the proxy serves and presents. */
    record Certificates(String localhostChain, String localhostKey, String externalChain, String externalKey,
                        String clientCa, String aggregatorChain, String aggregatorKey) {
    }

    /** The user a request is forwarded as; {@link #NONE} forwards it unchanged, {@link #REJECTED} answers 401. */
    record Identity(String user, List<String> groups, Map<String, List<String>> extra) {
        static final Identity NONE = new Identity(null, List.of(), Map.of());
        static final Identity REJECTED = new Identity(null, List.of(), Map.of());

        boolean asserted() {
            return user != null;
        }
    }

    private final Vertx vertx;
    private final int port;
    private final AroUpstream upstream;
    private final OpenShiftOAuthServer oauth;
    private final Supplier<String> consoleUrl;
    private final AroProxyTls.ClientCertificates clientCertificates;
    private final HttpServer server;

    AroApiProxy(Vertx vertx, int port, Certificates certificates, AroUpstream upstream, OpenShiftOAuthServer oauth,
                Supplier<String> consoleUrl) {
        this.vertx = vertx;
        this.port = port;
        this.upstream = upstream;
        this.oauth = oauth;
        this.consoleUrl = consoleUrl;
        List<X509Certificate> clientCa = AroProxyTls.certificates(certificates.clientCa());
        this.clientCertificates = new AroProxyTls.ClientCertificates(clientCa);
        AroProxyTls.SniKeyManager keys = new AroProxyTls.SniKeyManager(
                KeyMaterial.read(certificates.localhostChain(), certificates.localhostKey()),
                KeyMaterial.read(certificates.externalChain(), certificates.externalKey()));
        HttpServerOptions options = new HttpServerOptions()
                .setHost("0.0.0.0")
                .setPort(port)
                .setReuseAddress(true)
                .setSsl(true)
                .setSslEngineOptions(new JdkSSLEngineOptions())
                .setKeyCertOptions(KeyCertOptions.wrap(keys))
                .setTrustOptions(TrustOptions.wrap(new AroProxyTls.RequestedClientCertificates(clientCa)))
                .setClientAuth(ClientAuth.REQUEST)
                .setUseAlpn(true)
                .setAlpnVersions(List.of(HttpVersion.HTTP_1_1))
                .setMaxHeaderSize(64 * 1024)
                .setMaxInitialLineLength(16 * 1024)
                .setIdleTimeout(0);
        this.server = vertx.createHttpServer(options).requestHandler(this::handle);
    }

    int port() {
        return port;
    }

    Future<Void> listen() {
        return server.listen().mapEmpty();
    }

    Future<Void> close() {
        return server.close();
    }

    // ── Dispatch ─────────────────────────────────────────────────────────────

    private void handle(HttpServerRequest req) {
        req.pause();
        String path = req.path();
        if (oauth.handles(path)) {
            serveOAuth(req);
            return;
        }
        String console = consoleUrl.get();
        if (req.method() == HttpMethod.GET && console != null && ("/console".equals(path)
                || path.startsWith("/console/"))) {
            req.resume();
            req.response().setStatusCode(302).putHeader(HttpHeaders.LOCATION, console).end();
            return;
        }
        identify(req).onComplete(result -> {
            Identity identity = result.succeeded() ? result.result() : Identity.NONE;
            if (result.failed()) {
                LOG.debugv("Could not resolve the user of {0} {1}: {2}", req.method(), path, result.cause().toString());
            }
            if (identity == Identity.REJECTED) {
                req.resume();
                req.response().setStatusCode(401).putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                        .end(UNAUTHORIZED);
            } else if (identity.asserted() && req.method() == HttpMethod.DELETE && path.startsWith(TOKENS_PATH)) {
                revoke(req, identity, path.substring(TOKENS_PATH.length()));
            } else if (identity.asserted() && req.method() == HttpMethod.GET && CURRENT_USER_PATH.equals(path)) {
                req.resume();
                respond(req, OpenShiftOAuthServer.virtualUser(identity.user(), "", identity.groups()));
            } else if (isUpgrade(req)) {
                tunnel(req, identity);
            } else {
                forward(req, identity);
            }
        });
    }

    private void serveOAuth(HttpServerRequest req) {
        String host = Optional.ofNullable(req.getHeader(HttpHeaders.HOST)).orElse("localhost:" + port);
        req.body().onSuccess(body -> {
            OAuthRequest request = OAuthRequest.of(req.method().name(), req.uri(), host, headers(req.headers()),
                    body.getBytes());
            vertx.executeBlocking(() -> oauth.handle(request), false)
                    .onSuccess(response -> respond(req, response))
                    .onFailure(error -> {
                        LOG.warnv(error, "OAuth request {0} {1} failed", req.method(), req.path());
                        req.response().setStatusCode(500).end();
                    });
        }).onFailure(error -> req.response().setStatusCode(400).end());
        req.resume();
    }

    private void revoke(HttpServerRequest req, Identity identity, String rawName) {
        String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
        req.body().onComplete(ignored -> vertx.executeBlocking(
                        () -> oauth.revoke(name, identity.user(), identity.groups()), false)
                .onSuccess(response -> respond(req, response))
                .onFailure(error -> req.response().setStatusCode(500).end()));
        req.resume();
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    /**
     * A verified client certificate first, as kube-apiserver's authenticator chain tries x509 before tokens; then a
     * token this cluster's OAuth server minted. An unverified certificate on a request without a token is refused.
     */
    private Future<Identity> identify(HttpServerRequest req) {
        X509Certificate[] chain = peerChain(req);
        if (chain != null) {
            Optional<CertificateUser> user = clientCertificates.verify(chain);
            if (user.isPresent()) {
                return Future.succeededFuture(new Identity(user.get().name(), user.get().groups(), Map.of()));
            }
        }
        String authorization = req.getHeader(HttpHeaders.AUTHORIZATION);
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim() : null;
        if (token != null && token.startsWith(OAuthTokens.PREFIX)) {
            return vertx.executeBlocking(() -> oauth.authenticateToken(token), false)
                    .map(found -> found.map(authenticated -> new Identity(authenticated.user().name(),
                                    authenticated.user().groups(), Map.of(SCOPES_EXTRA, authenticated.scopes())))
                            .orElse(Identity.NONE));
        }
        return Future.succeededFuture(chain != null && authorization == null ? Identity.REJECTED : Identity.NONE);
    }

    private static X509Certificate[] peerChain(HttpServerRequest req) {
        SSLSession session = req.connection().sslSession();
        if (session == null) {
            return null;
        }
        try {
            Certificate[] certificates = session.getPeerCertificates();
            List<X509Certificate> chain = new ArrayList<>();
            for (Certificate certificate : certificates) {
                if (certificate instanceof X509Certificate x509) {
                    chain.add(x509);
                }
            }
            return chain.isEmpty() ? null : chain.toArray(X509Certificate[]::new);
        } catch (SSLPeerUnverifiedException e) {
            return null;
        }
    }

    // ── Forwarding ───────────────────────────────────────────────────────────

    private void forward(HttpServerRequest req, Identity identity) {
        RequestOptions options = new RequestOptions().setMethod(req.method()).setURI(req.uri())
                .setHost(upstream.host()).setPort(upstream.port());
        upstream.http(identity.asserted()).request(options).onSuccess(upstreamRequest -> {
            MultiMap headers = upstreamRequest.headers();
            for (Map.Entry<String, String> header : forwardedHeaders(req.headers(), identity, false)) {
                headers.add(header.getKey(), header.getValue());
            }
            boolean hasBody = req.headers().contains(HttpHeaders.CONTENT_LENGTH)
                    || req.headers().contains(HttpHeaders.TRANSFER_ENCODING);
            if (hasBody && !headers.contains(HttpHeaders.CONTENT_LENGTH)) {
                upstreamRequest.setChunked(true);
            }
            AtomicBoolean finished = new AtomicBoolean();
            req.response().closeHandler(ignored -> {
                if (!finished.get()) {
                    upstreamRequest.reset();
                }
            });
            upstreamRequest.response()
                    .onSuccess(upstreamResponse -> relay(req, upstreamResponse, finished))
                    .onFailure(error -> {
                        finished.set(true);
                        fail(req, error);
                    });
            req.pipeTo(upstreamRequest).onFailure(error ->
                    LOG.debugv("Request body to the API server failed: {0}", error.getMessage()));
        }).onFailure(error -> {
            req.resume();
            fail(req, error);
        });
    }

    private static void relay(HttpServerRequest req, HttpClientResponse upstreamResponse, AtomicBoolean finished) {
        HttpServerResponse response = req.response();
        response.setStatusCode(upstreamResponse.statusCode());
        Set<String> connectionTokens = connectionTokens(upstreamResponse.headers());
        for (Map.Entry<String, String> header : upstreamResponse.headers()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP.contains(name) && !connectionTokens.contains(name)) {
                response.headers().add(header.getKey(), header.getValue());
            }
        }
        int status = upstreamResponse.statusCode();
        boolean bodiless = req.method() == HttpMethod.HEAD || status == 204 || status == 304 || status < 200;
        if (!bodiless && upstreamResponse.getHeader(HttpHeaders.CONTENT_LENGTH) == null) {
            response.setChunked(true);
        }
        upstreamResponse.pipeTo(response).onComplete(result -> {
            finished.set(true);
            if (result.failed()) {
                LOG.debugv("Response from the API server ended early: {0}", result.cause().getMessage());
                if (!response.ended() && !response.closed()) {
                    response.reset();
                }
            }
        });
    }

    private static void fail(HttpServerRequest req, Throwable error) {
        LOG.debugv("API server request {0} {1} failed: {2}", req.method(), req.path(), error.toString());
        HttpServerResponse response = req.response();
        if (response.closed() || response.ended()) {
            return;
        }
        if (response.headWritten()) {
            response.reset();
        } else {
            response.setStatusCode(502).putHeader(HttpHeaders.CONTENT_TYPE, "text/plain").end("Bad Gateway");
        }
    }

    // ── Upgrades ─────────────────────────────────────────────────────────────

    /** Whether the request asks to switch protocols: {@code Connection: upgrade} and an {@code Upgrade} header. */
    static boolean isUpgrade(HttpServerRequest req) {
        String upgrade = req.getHeader(HttpHeaders.UPGRADE);
        return upgrade != null && !upgrade.isBlank() && connectionTokens(req.headers()).contains("upgrade");
    }

    /**
     * Sends the request head to the API server as is (bar the identity rules), then joins the two connections into a
     * byte tunnel, so the server's answer, {@code 101} or not, reaches the client unchanged. Works for any method and
     * protocol: Vert.x's own upgrade support only covers GET, and SPDY exec uses POST.
     */
    private void tunnel(HttpServerRequest req, Identity identity) {
        StringBuilder head = new StringBuilder(req.method().name()).append(' ').append(req.uri())
                .append(" HTTP/1.1\r\nHost: ").append(upstream.host()).append(':').append(upstream.port())
                .append("\r\n");
        for (Map.Entry<String, String> header : forwardedHeaders(req.headers(), identity, true)) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        head.append("\r\n");
        upstream.net(identity.asserted()).connect(upstream.port(), upstream.host()).onSuccess(back -> {
            back.pause();
            back.write(Buffer.buffer(head.toString().getBytes(StandardCharsets.UTF_8)));
            takeOver(req).onSuccess(front -> join(front, back)).onFailure(error -> {
                LOG.debugv("Could not take over the client connection of {0}: {1}", req.path(), error.getMessage());
                back.close();
            });
        }).onFailure(error -> {
            req.resume();
            fail(req, error);
        });
    }

    private static Future<NetSocket> takeOver(HttpServerRequest req) {
        if (req.connection() instanceof Http1xServerConnection connection) {
            req.resume();
            return connection.netSocket();
        }
        return Future.failedFuture("not an HTTP/1.1 connection");
    }

    private static void join(NetSocket front, NetSocket back) {
        AtomicBoolean closed = new AtomicBoolean();
        Runnable close = () -> {
            if (closed.compareAndSet(false, true)) {
                front.close();
                back.close();
            }
        };
        pump(front, back, close);
        pump(back, front, close);
        back.resume();
        front.resume();
    }

    /** Copies {@code source} to {@code destination}, pausing the source while the destination's queue is full. */
    private static void pump(NetSocket source, NetSocket destination, Runnable close) {
        source.handler(buffer -> {
            destination.write(buffer);
            if (destination.writeQueueFull()) {
                source.pause();
                destination.drainHandler(ignored -> source.resume());
            }
        });
        source.endHandler(ignored -> close.run());
        source.closeHandler(ignored -> close.run());
        source.exceptionHandler(error -> close.run());
    }

    // ── Headers ──────────────────────────────────────────────────────────────

    /**
     * The headers sent upstream: the client's, without hop-by-hop headers (kept for an upgrade, which needs
     * {@code Connection} and {@code Upgrade}), without any {@code X-Remote-*}, and for an asserted user without
     * {@code Authorization} and with the request-header identity.
     */
    static List<Map.Entry<String, String>> forwardedHeaders(MultiMap incoming, Identity identity, boolean upgrade) {
        Set<String> connectionTokens = upgrade ? Set.of() : connectionTokens(incoming);
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (Map.Entry<String, String> header : incoming) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            boolean hopByHop = HOP_BY_HOP.contains(name) || connectionTokens.contains(name);
            boolean keptForUpgrade = upgrade && ("connection".equals(name) || "upgrade".equals(name));
            if ((hopByHop && !keptForUpgrade) || "host".equals(name) || name.startsWith("x-remote-")
                    || (identity.asserted() && "authorization".equals(name))) {
                continue;
            }
            headers.add(Map.entry(header.getKey(), header.getValue()));
        }
        if (identity.asserted()) {
            headers.add(Map.entry(AroUpstream.ASSERTED_USER_HEADER, identity.user()));
            for (String group : identity.groups()) {
                headers.add(Map.entry(AroUpstream.ASSERTED_GROUP_HEADER, group));
            }
            identity.extra().forEach((key, values) -> values.forEach(value ->
                    headers.add(Map.entry(AroUpstream.ASSERTED_EXTRA_PREFIX + key, value))));
        }
        return headers;
    }

    private static Set<String> connectionTokens(MultiMap headers) {
        Set<String> tokens = new HashSet<>();
        for (String value : headers.getAll(HttpHeaders.CONNECTION)) {
            for (String token : value.split(",")) {
                if (!token.isBlank()) {
                    tokens.add(token.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return tokens;
    }

    private static Map<String, List<String>> headers(MultiMap headers) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (String name : headers.names()) {
            copy.put(name, headers.getAll(name));
        }
        return copy;
    }

    private static void respond(HttpServerRequest req, OAuthResponse response) {
        HttpServerResponse out = req.response().setStatusCode(response.status());
        response.headers().forEach((name, values) -> out.headers().add(name, values));
        out.end(Buffer.buffer(response.body()));
    }
}
