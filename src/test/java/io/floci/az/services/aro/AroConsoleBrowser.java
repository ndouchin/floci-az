package io.floci.az.services.aro;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A browser stand-in for the OpenShift console tests: a cookie jar, no automatic redirects, and trust in any
 * certificate (the router's and the API proxy's CAs are checked elsewhere). Calls the console's API with the
 * {@code X-CSRFToken} header the console's frontend sends.
 */
final class AroConsoleBrowser {

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private final String consoleUrl;

    AroConsoleBrowser(String consoleUrl) throws Exception {
        this.consoleUrl = consoleUrl;
        this.http = HttpClient.newBuilder().sslContext(trustAll()).cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> postForm(String url, String form) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type",
                        "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** {@code url} once the router serves a redirect for it (302 or 303), retried while the Route syncs. */
    HttpResponse<String> awaitRedirect(String url) throws Exception {
        return await(url, 303);
    }

    /**
     * {@code url} once the router serves it with {@code expectedStatus} (any redirect for 302 and 303), retried while
     * the Route syncs.
     */
    HttpResponse<String> await(String url, int expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        String last = "no answer";
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = get(url);
                boolean redirect = expectedStatus / 100 == 3 && response.statusCode() / 100 == 3;
                if (response.statusCode() == expectedStatus || redirect) {
                    return response;
                }
                last = response.statusCode() + " " + response.body();
            } catch (Exception notYet) {
                last = notYet.toString();
            }
            Thread.sleep(2_000);
        }
        fail("the console did not answer " + url + " with " + expectedStatus + ": " + last);
        return null;
    }

    /** A request to the console's own API ({@code /api/...}), with the CSRF cookie echoed as the console's UI does. */
    HttpResponse<String> api(String method, String path, String body) throws Exception {
        String csrf = cookies.getCookieStore().get(URI.create(consoleUrl)).stream()
                .filter(cookie -> cookie.getName().equals("csrf-token")).map(HttpCookie::getValue).findFirst()
                .orElse("");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(consoleUrl + path))
                .header("X-CSRFToken", csrf).header("Origin", consoleUrl).timeout(Duration.ofSeconds(30));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String location(HttpResponse<String> response) {
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new AssertionError(response.statusCode() + " without Location: " + response.body()));
        return response.uri().resolve(location).toString();
    }

    static SSLContext trustAll() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new X509ExtendedTrustManager[]{new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        return context;
    }
}
