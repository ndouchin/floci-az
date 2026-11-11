package io.floci.az.services.aro;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.services.aro.AroModels.OpenShiftCluster;
import io.floci.az.services.aro.oauth.OAuthToken;
import io.floci.az.services.aro.oauth.OAuthTokenStore;
import io.floci.az.services.aro.oauth.OpenShiftOAuthServer;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The API proxies of the ARO clusters that have one, by instance id: each with its upstream connections to MicroShift,
 * its OAuth server and the kubeadmin authenticator. A proxy starts once its cluster is up (it serves MicroShift's own
 * certificates) and stops when the cluster is deleted or floci-az shuts down.
 */
@ApplicationScoped
public class AroProxyManager {

    private static final Logger LOG = Logger.getLogger(AroProxyManager.class);

    private record Running(AroApiProxy proxy, AroUpstream upstream) {
    }

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final AroClusterManager clusterManager;
    private final Map<String, Running> proxies = new ConcurrentHashMap<>();

    @Inject
    public AroProxyManager(Vertx vertx, EmulatorConfig config, AroClusterManager clusterManager) {
        this.vertx = vertx;
        this.config = config;
        this.clusterManager = clusterManager;
    }

    public boolean running(OpenShiftCluster cluster) {
        return proxies.containsKey(cluster.getInstanceId());
    }

    /**
     * Starts the cluster's proxy on its proxy port unless it runs, and waits until it listens. {@code persistTokens}
     * receives the token store after each change. Throws when MicroShift's certificates cannot be read or the port
     * cannot be bound; the caller retries.
     */
    public synchronized void ensureStarted(OpenShiftCluster cluster, Consumer<Map<String, OAuthToken>> persistTokens) {
        if (cluster.getProxyPort() <= 0 || running(cluster)) {
            return;
        }
        AroApiProxy.Certificates certificates = clusterManager.proxyCertificates(cluster);
        URI internal = URI.create(cluster.getInternalEndpoint());
        AroUpstream upstream = new AroUpstream(vertx, internal.getHost(), internal.getPort(),
                AroProxyTls.KeyMaterial.read(certificates.aggregatorChain(), certificates.aggregatorKey()));
        try {
            OAuthTokenStore tokens = new OAuthTokenStore(cluster.getOauthTokens(), persistTokens);
            KubeadminAuthenticator kubeadmin = new KubeadminAuthenticator(upstream::readKubeadminSecret,
                    Clock.systemUTC());
            String consoleUrl = cluster.isConsoleEnabled() ? AroClusterManager.consoleUrl(cluster) : null;
            OpenShiftOAuthServer oauth = new OpenShiftOAuthServer(List.of(kubeadmin), tokens,
                    new OpenShiftOAuthServer.Settings(config.services().aro().oauth().accessTokenMaxAgeSeconds(),
                            cluster.getConsoleClientSecret(), consoleUrl), Clock.systemUTC());
            AroApiProxy proxy = new AroApiProxy(vertx, cluster.getProxyPort(), certificates, upstream, oauth,
                    () -> consoleUrl);
            proxy.listen().toCompletionStage().toCompletableFuture().get(15, TimeUnit.SECONDS);
            proxies.put(cluster.getInstanceId(), new Running(proxy, upstream));
            LOG.infov("API proxy of ARO cluster {0} listening on {1}, in front of {2}", cluster.getName(),
                    String.valueOf(cluster.getProxyPort()), cluster.getInternalEndpoint());
        } catch (Exception e) {
            upstream.close();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("cannot start the API proxy on port " + cluster.getProxyPort() + ": "
                    + e.getMessage(), e);
        }
    }

    /** Whether the proxy answers the OAuth metadata request {@code oc login} starts with. */
    public boolean metadataAnswers(OpenShiftCluster cluster) {
        if (!running(cluster)) {
            return false;
        }
        try {
            HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://localhost:"
                    + cluster.getProxyPort() + OpenShiftOAuthServer.METADATA_PATH).toURL().openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            trustAll(connection);
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            LOG.debugv("OAuth metadata of ARO cluster {0} not answering: {1}", cluster.getName(), e.toString());
            return false;
        }
    }

    public void stop(OpenShiftCluster cluster) {
        stop(cluster.getInstanceId());
    }

    public void stopAll() {
        proxies.keySet().forEach(this::stop);
    }

    private void stop(String instanceId) {
        Running running = proxies.remove(instanceId);
        if (running == null) {
            return;
        }
        try {
            running.proxy().close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.debugv("Closing the API proxy of ARO cluster {0}: {1}", instanceId, e.toString());
        } finally {
            running.upstream().close();
        }
    }

    @SuppressWarnings("java:S4830")
    private static void trustAll(HttpsURLConnection connection) throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());
        connection.setSSLSocketFactory(context.getSocketFactory());
        connection.setHostnameVerifier((h, s) -> true);
    }
}
