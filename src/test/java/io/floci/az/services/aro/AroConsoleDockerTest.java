package io.floci.az.services.aro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.services.aro.AroDockerTest.KubeApi;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.File;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The OpenShift web console on a real ARO cluster whose domain resolves to the host ({@code *.127.0.0.1.nip.io}): a
 * cookie-jar client logs in as {@code kube:admin} the way a browser does, calls the API through the console and logs
 * out. Skipped without Docker, and when the Docker daemon is not amd64 (the console image has no arm64 build).
 */
@QuarkusTest
@TestProfile(AroDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ARO OpenShift console (Docker required)")
class AroConsoleDockerTest {

    private static final String SUB = "00000000-0000-0000-0000-0000000000d3";
    private static final String RG = "aro-console-rg";
    private static final String NAME = "aro-console";
    private static final String DOMAIN = "arocon.127.0.0.1.nip.io";
    private static final String API = "?api-version=2025-07-25";
    private static final String CLUSTER = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.RedHatOpenShift/openShiftClusters/" + NAME;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String consoleUrl;
    private String apiUrl;
    private String password;
    private String kubeconfig;
    private KubeApi admin;
    private HttpClient browser;
    private CookieManager cookies;

    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock")) || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available: skipping the MicroShift tests");
    }

    @Test
    @Order(1)
    void theClusterReportsItsConsoleUrlWithTheRouterPort() throws Exception {
        given().post("/_admin/reset").then().statusCode(204);
        Response created = given().contentType("application/json").body("""
            {"location": "eastus", "properties": {"clusterProfile": {"domain": "%s", "version": "4.19.2"}}}
            """.formatted(DOMAIN)).when().put(CLUSTER + API);
        created.then().statusCode(201);
        String operation = URI.create(created.header("Azure-AsyncOperation")).getPath() + API;
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(15).toMillis();
        String status = "Creating";
        while (!List.of("Succeeded", "Failed").contains(status) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000);
            status = given().when().get(operation).path("status");
        }
        assertEquals("Succeeded", status, given().when().get(operation).asString());
        JsonNode cluster = MAPPER.readTree(given().when().get(CLUSTER + API).asString());
        consoleUrl = cluster.path("properties").path("consoleProfile").path("url").asText();
        assertTrue(consoleUrl.matches("https://console-openshift-console\\.apps\\." + DOMAIN.replace(".", "\\.")
                + ":\\d+/"), consoleUrl);
        consoleUrl = consoleUrl.substring(0, consoleUrl.length() - 1);
        apiUrl = cluster.path("properties").path("apiserverProfile").path("url").asText().replaceAll("/$", "");
        password = given().contentType("application/json").when().post(CLUSTER + "/listCredentials" + API)
                .then().statusCode(200).extract().path("kubeadminPassword");
        kubeconfig = new String(Base64.getDecoder().decode((String) given().contentType("application/json")
                .when().post(CLUSTER + "/listAdminCredentials" + API).then().statusCode(200).extract()
                .path("kubeconfig")), StandardCharsets.UTF_8);
        admin = KubeApi.from(kubeconfig);
        assumeTrue(admin.get("/api/v1/nodes").path("items").get(0).path("status").path("nodeInfo")
                .path("architecture").asText().equals("amd64"), "the console image is amd64 only");
    }

    @Test
    @Order(2)
    void theConsoleBecomesAvailableWithItsRouteAndConsolePublic() throws Exception {
        admin.await("/apis/apps/v1/namespaces/openshift-console/deployments/console",
                deployment -> deployment.path("status").path("availableReplicas").asInt() >= 1, Duration.ofMinutes(6));
        assertEquals(consoleUrl, admin.get("/api/v1/namespaces/openshift-config-managed/configmaps/console-public")
                .path("data").path("consoleURL").asText());
        JsonNode route = admin.get("/apis/route.openshift.io/v1/namespaces/openshift-console/routes/console");
        assertEquals("console-openshift-console.apps." + DOMAIN, route.path("spec").path("host").asText());

        HttpResponse<String> redirect = HttpClient.newBuilder().sslContext(trustAll()).build()
                .send(HttpRequest.newBuilder(URI.create(apiUrl + "/console")).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertEquals(302, redirect.statusCode());
        assertEquals(consoleUrl, redirect.headers().firstValue("Location").orElseThrow());
    }

    @Test
    @Order(3)
    void aBrowserLogsInAsKubeAdminAndCallsTheApiThroughTheConsole() throws Exception {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        browser = HttpClient.newBuilder().sslContext(trustAll()).cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> login = awaitConsole(consoleUrl + "/auth/login");
        String authorize = location(login);
        assertTrue(authorize.startsWith("https://api." + DOMAIN + ":"), authorize);
        assertTrue(authorize.contains("client_id=console"), authorize);
        assertTrue(authorize.contains("redirect_uri=" + URLEncoder.encode(consoleUrl + "/auth/callback",
                StandardCharsets.UTF_8)), authorize);

        HttpResponse<String> toLoginPage = get(authorize);
        assertEquals(302, toLoginPage.statusCode());
        String loginPage = location(toLoginPage);
        assertTrue(loginPage.contains("/login/kube:admin?then="), loginPage);
        HttpResponse<String> page = get(loginPage);
        assertEquals(200, page.statusCode());
        Matcher then = Pattern.compile("name=\"then\" value=\"([^\"]+)\"").matcher(page.body());
        assertTrue(then.find(), page.body());

        HttpResponse<String> posted = browser.send(HttpRequest.newBuilder(URI.create(loginPage.replaceAll("\\?.*$", "")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("then=" + URLEncoder.encode(then.group(1)
                        .replace("&amp;", "&"), StandardCharsets.UTF_8) + "&username=kubeadmin&password="
                        + URLEncoder.encode(password, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(302, posted.statusCode(), posted.body());
        HttpResponse<String> granted = get(location(posted));
        assertEquals(302, granted.statusCode(), granted.body());
        String callback = location(granted);
        assertTrue(callback.startsWith(consoleUrl + "/auth/callback?code="), callback);

        HttpResponse<String> landed = get(callback);
        assertTrue(landed.statusCode() == 302 || landed.statusCode() == 303 || landed.statusCode() == 200,
                landed.statusCode() + " " + landed.body());
        get(consoleUrl + "/");

        HttpResponse<String> review = consoleApi("POST",
                "/api/kubernetes/apis/authentication.k8s.io/v1/selfsubjectreviews",
                "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"SelfSubjectReview\"}");
        assertEquals(201, review.statusCode(), review.body());
        JsonNode user = MAPPER.readTree(review.body()).path("status").path("userInfo");
        assertEquals("kube:admin", user.path("username").asText());
        assertTrue(user.path("groups").toString().contains("system:cluster-admins"), user.toString());
        assertEquals(200, consoleApi("GET", "/api/kubernetes/api/v1/namespaces", null).statusCode());
    }

    @Test
    @Order(4)
    void loggingOutOfTheConsoleRevokesItsToken() throws Exception {
        HttpResponse<String> logout = consoleApi("POST", "/api/console/logout", "");
        assertEquals(204, logout.statusCode(), logout.body());
        assertEquals(401, consoleApi("GET", "/api/kubernetes/api/v1/namespaces", null).statusCode());
    }

    @Test
    @Order(5)
    void ocWhoamiShowsTheConsole() throws Exception {
        String oc = ocBinary();
        assumeTrue(oc != null, "no oc binary on the PATH or in FLOCI_OC");
        Path dir = Files.createTempDirectory("aro-console-oc");
        Path file = dir.resolve("kubeconfig");
        Files.writeString(file, kubeconfig);
        ProcessBuilder builder = new ProcessBuilder(oc, "--kubeconfig", file.toString(), "whoami", "--show-console")
                .redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(consoleUrl, output.strip());
    }

    @Test
    @Order(6)
    void deletingTheClusterRemovesIt() throws Exception {
        given().when().delete(CLUSTER + API).then().statusCode(202);
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        int status = 200;
        while (status != 404 && System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000);
            status = given().when().get(CLUSTER + API).statusCode();
        }
        assertEquals(404, status);
    }

    /** The console's first page through the router, retried while the Route syncs. */
    private HttpResponse<String> awaitConsole(String url) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        HttpResponse<String> response = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                response = get(url);
                if (response.statusCode() == 302 || response.statusCode() == 303) {
                    return response;
                }
            } catch (Exception notYet) {
                response = null;
            }
            Thread.sleep(2_000);
        }
        fail("the console did not answer " + url + ": " + (response == null ? "no answer"
                : response.statusCode() + " " + response.body()));
        return null;
    }

    private HttpResponse<String> consoleApi(String method, String path, String body) throws Exception {
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
        return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String url) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String location(HttpResponse<String> response) {
        String location = response.headers().firstValue("Location")
                .orElseThrow(() -> new AssertionError(response.statusCode() + " without Location: " + response.body()));
        return response.uri().resolve(location).toString();
    }

    /** Trusts any certificate and host name: the router's and the proxy's CAs are checked in the other tests. */
    private static SSLContext trustAll() throws Exception {
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

    private static String ocBinary() {
        String configured = System.getenv("FLOCI_OC");
        if (configured != null && new File(configured).canExecute()) {
            return configured;
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            File candidate = new File(dir, "oc");
            if (candidate.canExecute()) {
                return candidate.getPath();
            }
        }
        return null;
    }
}
