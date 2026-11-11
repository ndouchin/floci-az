package io.floci.az.services.aro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aro.KubeadminAuthenticator.KubeadminSecret;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetClientOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The proxy's connections to the MicroShift API server, in two pools: one without a client certificate, for requests
 * forwarded as the client sent them, and one presenting MicroShift's aggregator client certificate, for requests whose
 * user the proxy asserts with request-header authentication. Never present the aggregator certificate on a request
 * without {@code X-Remote-User}: MicroShift answers it 401.
 */
final class AroUpstream {

    static final String ASSERTED_USER_HEADER = "X-Remote-User";
    static final String ASSERTED_GROUP_HEADER = "X-Remote-Group";
    static final String ASSERTED_EXTRA_PREFIX = "X-Remote-Extra-";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_POOL_SIZE = 256;

    private final String host;
    private final int port;
    private final HttpClient plain;
    private final HttpClient asserting;
    private final NetClient plainNet;
    private final NetClient assertingNet;

    AroUpstream(Vertx vertx, String host, int port, AroProxyTls.KeyMaterial aggregatorClient) {
        this.host = host;
        this.port = port;
        KeyCertOptions aggregator = KeyCertOptions.wrap(aggregatorClient.keyManager());
        this.plain = vertx.createHttpClient(httpOptions());
        this.asserting = vertx.createHttpClient(httpOptions().setKeyCertOptions(aggregator));
        this.plainNet = vertx.createNetClient(netOptions());
        this.assertingNet = vertx.createNetClient(netOptions().setKeyCertOptions(aggregator));
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    HttpClient http(boolean asserted) {
        return asserted ? asserting : plain;
    }

    NetClient net(boolean asserted) {
        return asserted ? assertingNet : plainNet;
    }

    /**
     * GETs {@code path} as {@code system:admin} (group {@code system:masters}) over the asserting pool, the way
     * oauth-server reads the cluster with its own credentials; empty on 404. Blocks: call it from a worker thread.
     */
    Optional<JsonNode> getAsAdmin(String path) {
        Future<Optional<JsonNode>> result = asserting.request(new RequestOptions().setMethod(HttpMethod.GET)
                        .setHost(host).setPort(port).setURI(path)
                        .putHeader(ASSERTED_USER_HEADER, "system:admin")
                        .putHeader(ASSERTED_GROUP_HEADER, "system:masters")
                        .putHeader("Accept", "application/json"))
                .compose(request -> request.send().compose(response -> response.body().map(body -> {
                    if (response.statusCode() == 404) {
                        return Optional.<JsonNode>empty();
                    }
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("GET " + path + " answered " + response.statusCode());
                    }
                    return Optional.of(parse(body));
                })));
        try {
            return result.toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading " + path, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    /** Secret {@code kube-system/kubeadmin} and the creation time of {@code kube-system}, for kubeadmin's checks. */
    Optional<KubeadminSecret> readKubeadminSecret() {
        Optional<JsonNode> secret = getAsAdmin("/api/v1/namespaces/kube-system/secrets/kubeadmin");
        if (secret.isEmpty()) {
            return Optional.empty();
        }
        JsonNode namespace = getAsAdmin("/api/v1/namespaces/kube-system")
                .orElseThrow(() -> new IllegalStateException("namespace kube-system is missing"));
        JsonNode metadata = secret.get().path("metadata");
        String encoded = secret.get().path("data").path(KubeadminAuthenticator.BASIC_AUTH_NAME).asText(null);
        String hash = encoded == null ? null : new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        return Optional.of(new KubeadminSecret(hash, metadata.path("uid").asText(""),
                metadata.path("resourceVersion").asText(""), Instant.parse(metadata.path("creationTimestamp").asText()),
                metadata.hasNonNull("deletionTimestamp"),
                Instant.parse(namespace.path("metadata").path("creationTimestamp").asText())));
    }

    void close() {
        plain.close();
        asserting.close();
        plainNet.close();
        assertingNet.close();
    }

    private static JsonNode parse(Buffer body) {
        try {
            return MAPPER.readTree(body.getBytes());
        } catch (IOException e) {
            throw new IllegalStateException("unreadable JSON: " + e.getMessage(), e);
        }
    }

    private static HttpClientOptions httpOptions() {
        return new HttpClientOptions()
                .setSsl(true)
                .setTrustAll(true)
                .setVerifyHost(false)
                .setProtocolVersion(HttpVersion.HTTP_1_1)
                .setMaxPoolSize(MAX_POOL_SIZE)
                .setMaxHeaderSize(64 * 1024)
                .setConnectTimeout(10_000);
    }

    private static NetClientOptions netOptions() {
        return new NetClientOptions()
                .setSsl(true)
                .setTrustAll(true)
                .setHostnameVerificationAlgorithm("")
                .setConnectTimeout(10_000);
    }
}
