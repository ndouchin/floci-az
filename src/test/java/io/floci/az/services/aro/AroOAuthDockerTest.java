package io.floci.az.services.aro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.az.services.aro.AroDockerTest.KubeApi;
import io.floci.az.services.aro.oauth.OAuthTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code oc login} against a real ARO cluster through its API proxy: the requests {@code oc} 4.19 sends during
 * {@code oc login -u kubeadmin -p <password> <api-url>}, replayed by a Java client with TLS verification on against
 * the admin kubeconfig's CA, then what the token, the admin kubeconfig and a ServiceAccount token can do through the
 * proxy, exec over WebSocket, a watch, logout, and kubeadmin's removal. When an {@code oc} binary is on the
 * {@code PATH} (or named by {@code FLOCI_OC}), the same is checked with {@code oc} itself.
 *
 * <p>Skipped without Docker, like {@link AroDockerTest}.</p>
 */
@QuarkusTest
@TestProfile(AroDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("ARO oc login through the API proxy (Docker required)")
class AroOAuthDockerTest {

    private static final String SUB = "00000000-0000-0000-0000-0000000000d2";
    private static final String RG = "aro-oauth-rg";
    private static final String NAME = "aro-oauth";
    private static final String API = "?api-version=2025-07-25";
    private static final String CLUSTER = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.RedHatOpenShift/openShiftClusters/" + NAME;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DockerClient dockerClient;

    private String apiUrl;
    private String password;
    private KubeApi admin;
    private HttpClient http;
    private SSLContext tls;
    private String caPem;
    private String token;
    private String podName;

    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock")) || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available: skipping the MicroShift tests");
    }

    @Test
    @Order(1)
    void theClusterIsSucceededWithItsApiUrlOnTheProxy() throws Exception {
        given().post("/_admin/reset").then().statusCode(204);
        Response created = given().contentType("application/json").body("""
            {"location": "eastus", "properties": {"clusterProfile": {"domain": "arooauth", "version": "4.19.2"}}}
            """).when().put(CLUSTER + API);
        created.then().statusCode(201);
        String operation = URI.create(created.header("Azure-AsyncOperation")).getPath() + API;
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(15).toMillis();
        String status = "Creating";
        while (!List.of("Succeeded", "Failed").contains(status) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000);
            status = given().when().get(operation).path("status");
        }
        assertEquals("Succeeded", status, given().when().get(operation).asString());
        apiUrl = given().when().get(CLUSTER + API).path("properties.apiserverProfile.url");
        apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        assertTrue(apiUrl.matches("https://localhost:77\\d\\d"), apiUrl);

        password = given().contentType("application/json").when().post(CLUSTER + "/listCredentials" + API)
                .then().statusCode(200).extract().path("kubeadminPassword");
        String kubeconfig = new String(Base64.getDecoder().decode((String) given().contentType("application/json")
                .when().post(CLUSTER + "/listAdminCredentials" + API).then().statusCode(200).extract()
                .path("kubeconfig")), StandardCharsets.UTF_8);
        assertTrue(kubeconfig.contains("server: " + apiUrl), kubeconfig);
        admin = KubeApi.from(kubeconfig);
        caPem = new String(Base64.getDecoder().decode(field(kubeconfig, "certificate-authority-data")),
                StandardCharsets.UTF_8);
        tls = trusting(caPem);
        http = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    @Order(2)
    void theKubeadminSecretHoldsABcryptOfThePasswordAndTheClusterAdminsBindingExists() throws Exception {
        JsonNode secret = admin.get("/api/v1/namespaces/kube-system/secrets/kubeadmin");
        String hash = new String(Base64.getDecoder().decode(secret.path("data").path("kubeadmin").asText()),
                StandardCharsets.UTF_8);
        assertTrue(hash.startsWith("$2a$10$") || hash.startsWith("$2y$"), hash);
        JsonNode binding = admin.get("/apis/rbac.authorization.k8s.io/v1/clusterrolebindings/cluster-admins");
        assertEquals("cluster-admin", binding.path("roleRef").path("name").asText());
        assertEquals("system:cluster-admins", binding.path("subjects").get(0).path("name").asText());
    }

    @Test
    @Order(3)
    void ocLoginsRequestsGiveATokenForKubeAdmin() throws Exception {
        send("HEAD", "/", null, Map.of());
        assertEquals(200, send("GET", "/version", null, Map.of()).statusCode());
        HttpResponse<String> metadata = send("GET", "/.well-known/oauth-authorization-server", null, Map.of());
        assertEquals(200, metadata.statusCode());
        JsonNode discovery = MAPPER.readTree(metadata.body());
        String issuer = discovery.path("issuer").asText();
        assertEquals(apiUrl, issuer);
        send("HEAD", "", null, Map.of());

        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random(96));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String redirect = issuer + "/oauth/token/implicit";
        String authorize = "/oauth/authorize?client_id=openshift-challenging-client&code_challenge=" + challenge
                + "&code_challenge_method=S256&redirect_uri=" + enc(redirect) + "&response_type=code";
        HttpResponse<String> challenged = send("GET", authorize, null, Map.of("X-CSRF-Token", "1"));
        assertEquals(401, challenged.statusCode());
        assertEquals("Basic realm=\"openshift\"", challenged.headers().firstValue("WWW-Authenticate").orElse(""));
        HttpResponse<String> wrong = send("GET", authorize, null, Map.of("X-CSRF-Token", "1",
                "Authorization", basic("kubeadmin", "wrong-wrong-wrong-wrong")));
        assertEquals(401, wrong.statusCode());
        HttpResponse<String> granted = send("GET", authorize, null, Map.of("X-CSRF-Token", "1",
                "Authorization", basic("kubeadmin", password)));
        assertEquals(302, granted.statusCode(), granted.body());
        Matcher code = Pattern.compile("code=([^&]+)").matcher(granted.headers().firstValue("Location").orElseThrow());
        assertTrue(code.find());

        HttpResponse<String> exchanged = send("POST", "/oauth/token", "grant_type=authorization_code&code="
                + code.group(1) + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + verifier, Map.of(
                "Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json",
                "Authorization", basic("openshift-challenging-client", "")));
        assertEquals(200, exchanged.statusCode(), exchanged.body());
        token = MAPPER.readTree(exchanged.body()).path("access_token").asText();
        assertTrue(token.matches("sha256~[A-Za-z0-9_-]{43}"), token);
    }

    @Test
    @Order(4)
    void theTokenIsKubeAdminWithTheClusterAdminGroupsAndFullScope() throws Exception {
        JsonNode user = selfSubjectReview(Map.of("Authorization", "Bearer " + token));
        assertEquals("kube:admin", user.path("username").asText());
        assertEquals("[\"system:cluster-admins\",\"system:authenticated\"]", user.path("groups").toString());
        assertEquals("[\"user:full\"]", user.path("extra").path("scopes.authorization.openshift.io").toString());
        assertEquals(200, send("GET", "/api/v1/nodes", null, bearer()).statusCode());
        HttpResponse<String> virtual = send("GET", "/apis/user.openshift.io/v1/users/~", null, bearer());
        assertEquals(200, virtual.statusCode());
        assertEquals("kube:admin", MAPPER.readTree(virtual.body()).path("metadata").path("name").asText());
    }

    @Test
    @Order(5)
    void theAdminKubeconfigAndServiceAccountTokensStillWorkThroughTheProxy() throws Exception {
        JsonNode review = MAPPER.readTree(adminPost("/apis/authentication.k8s.io/v1/selfsubjectreviews",
                "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"SelfSubjectReview\"}"));
        assertEquals("system:admin", review.path("status").path("userInfo").path("username").asText());

        admin.post("/api/v1/namespaces/default/serviceaccounts",
                "{\"apiVersion\":\"v1\",\"kind\":\"ServiceAccount\",\"metadata\":{\"name\":\"probe\"}}");
        String saToken = MAPPER.readTree(adminPost("/api/v1/namespaces/default/serviceaccounts/probe/token",
                "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"TokenRequest\",\"spec\":{}}"))
                .path("status").path("token").asText();
        JsonNode user = selfSubjectReview(Map.of("Authorization", "Bearer " + saToken));
        assertEquals("system:serviceaccount:default:probe", user.path("username").asText());
        assertEquals(401, send("GET", "/api/v1/nodes", null, Map.of("Authorization",
                "Bearer " + OAuthTokens.newToken(new SecureRandom()))).statusCode());
    }

    @Test
    @Order(6)
    void execOverWebSocketAndAWatchWorkThroughTheProxy() throws Exception {
        admin.post("/api/v1/namespaces", "{\"apiVersion\":\"v1\",\"kind\":\"Namespace\",\"metadata\":{\"name\":\"oauth\"}}");
        admin.await("/api/v1/namespaces/oauth/serviceaccounts/default", sa -> true);
        admin.post("/apis/apps/v1/namespaces/oauth/deployments", """
            {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"web"},
             "spec":{"replicas":1,"selector":{"matchLabels":{"app":"web"}},
              "template":{"metadata":{"labels":{"app":"web"}},
               "spec":{"containers":[{"name":"web","image":"docker.io/library/busybox:1.37",
                 "command":["sh","-c","echo hello > /tmp/index.html && httpd -f -p 8080 -h /tmp"],
                 "ports":[{"containerPort":8080}]}]}}}}
            """);
        JsonNode pods = admin.await("/api/v1/namespaces/oauth/pods?labelSelector=app%3Dweb", list ->
                list.path("items").size() == 1
                        && "Running".equals(list.path("items").get(0).path("status").path("phase").asText()));
        podName = pods.path("items").get(0).path("metadata").path("name").asText();

        assertEquals("hi\n", exec(List.of("echo", "hi")));

        HttpResponse<InputStream> watch = http.send(request("/api/v1/namespaces/oauth/configmaps?watch=1",
                bearer()).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, watch.statusCode());
        BufferedReader events = new BufferedReader(new InputStreamReader(watch.body(), StandardCharsets.UTF_8));
        CompletableFuture<String> added = CompletableFuture.supplyAsync(() -> {
            try {
                for (String line = events.readLine(); line != null; line = events.readLine()) {
                    if (line.contains("\"watched\"")) {
                        return line;
                    }
                }
                return null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        admin.post("/api/v1/namespaces/oauth/configmaps",
                "{\"apiVersion\":\"v1\",\"kind\":\"ConfigMap\",\"metadata\":{\"name\":\"watched\"}}");
        String event = added.get(30, TimeUnit.SECONDS);
        assertEquals("ADDED", MAPPER.readTree(event).path("type").asText());
        watch.body().close();
    }

    @Test
    @Order(7)
    void theOcBinaryLogsInExecsPortForwardsAndLogsOut() throws Exception {
        String oc = ocBinary();
        assumeTrue(oc != null, "no oc binary on the PATH or in FLOCI_OC");
        Path dir = Files.createTempDirectory("aro-oc");
        Path ca = dir.resolve("ca.crt");
        Files.writeString(ca, caPem);
        Map<String, String> ocEnvironment = Map.of("KUBECONFIG", dir.resolve("kubeconfig").toString(),
                "HOME", dir.toString());

        String login = run(ocEnvironment, oc, "login", "-u", "kubeadmin", "-p", password, apiUrl, "--certificate-authority="
                + ca);
        assertTrue(login.contains("Login successful."), login);
        assertEquals("kube:admin", run(ocEnvironment, oc, "whoami").strip());
        String ocToken = run(ocEnvironment, oc, "whoami", "--show-token").strip();
        assertTrue(ocToken.matches("sha256~[A-Za-z0-9_-]{43}"), ocToken);
        assertTrue(run(ocEnvironment, oc, "get", "nodes").contains(" Ready "));
        assertEquals("hi", run(ocEnvironment, oc, "-n", "oauth", "exec", podName, "--", "echo", "hi").strip());
        assertEquals("hi", run(ocEnvironment, oc, "-n", "oauth", "rsh", podName, "echo", "hi").strip());

        ProcessBuilder forwarding = new ProcessBuilder(oc, "-n", "oauth", "port-forward", "pod/" + podName,
                "18080:8080").redirectErrorStream(true).redirectOutput(dir.resolve("port-forward.log").toFile());
        forwarding.environment().putAll(ocEnvironment);
        Process forward = forwarding.start();
        try {
            String page = "";
            long deadline = System.currentTimeMillis() + 30_000;
            while (!page.contains("hello") && System.currentTimeMillis() < deadline) {
                Thread.sleep(1_000);
                try {
                    page = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:18080/")).timeout(Duration.ofSeconds(5)).build(),
                            HttpResponse.BodyHandlers.ofString()).body();
                } catch (Exception notYet) {
                    page = notYet.toString();
                }
            }
            assertTrue(page.contains("hello"), page + "\n" + Files.readString(dir.resolve("port-forward.log")));
        } finally {
            forward.destroy();
            forward.waitFor(10, TimeUnit.SECONDS);
        }

        String logout = run(ocEnvironment, oc, "logout");
        assertTrue(logout.contains("Logged \"kube:admin\" out"), logout);
        assertEquals(401, send("GET", "/api/v1/nodes", null, Map.of("Authorization", "Bearer " + ocToken))
                .statusCode());
    }

    @Test
    @Order(8)
    void deletingTheTokenObjectLogsOut() throws Exception {
        String name = OAuthTokens.objectName(token);
        HttpResponse<String> deleted = send("DELETE", "/apis/oauth.openshift.io/v1/oauthaccesstokens/" + name,
                "{\"kind\":\"DeleteOptions\",\"apiVersion\":\"v1\"}", Map.of("Authorization", "Bearer " + token,
                        "Content-Type", "application/json"));
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertEquals(401, send("GET", "/api/v1/nodes", null, bearer()).statusCode());
    }

    @Test
    @Order(9)
    void deletingTheKubeadminSecretDisablesKubeadmin() throws Exception {
        String before = loginToken(password);
        HttpResponse<String> deleted = adminSend("DELETE", "/api/v1/namespaces/kube-system/secrets/kubeadmin");
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertNull(loginToken(password));
        Thread.sleep(11_000);
        assertEquals(401, send("GET", "/api/v1/nodes", null, Map.of("Authorization", "Bearer " + before))
                .statusCode());
    }

    @Test
    @Order(10)
    void deletingTheClusterStopsTheProxy() throws Exception {
        given().when().delete(CLUSTER + API).then().statusCode(202);
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        int status = 200;
        while (status != 404 && System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000);
            status = given().when().get(CLUSTER + API).statusCode();
        }
        assertEquals(404, status);
        assertTrue(dockerClient.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("io.floci.resource-id", CLUSTER)).exec().isEmpty());
        assertThrows(Exception.class, () -> send("GET", "/version", null, Map.of()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** The challenge flow in one go: the token for {@code kubeadmin} and {@code pw}, or null when refused. */
    private String loginToken(String pw) throws Exception {
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(random(64));
        String redirect = apiUrl + "/oauth/token/implicit";
        HttpResponse<String> granted = send("GET", "/oauth/authorize?client_id=openshift-challenging-client"
                + "&code_challenge=" + verifier + "&code_challenge_method=plain&redirect_uri=" + enc(redirect)
                + "&response_type=code", null, Map.of("X-CSRF-Token", "1", "Authorization", basic("kubeadmin", pw)));
        if (granted.statusCode() == 401) {
            return null;
        }
        Matcher code = Pattern.compile("code=([^&]+)").matcher(granted.headers().firstValue("Location").orElseThrow());
        assertTrue(code.find());
        HttpResponse<String> exchanged = send("POST", "/oauth/token", "grant_type=authorization_code&code="
                + code.group(1) + "&redirect_uri=" + enc(redirect) + "&code_verifier=" + verifier, Map.of(
                "Content-Type", "application/x-www-form-urlencoded", "Authorization",
                basic("openshift-challenging-client", "")));
        return MAPPER.readTree(exchanged.body()).path("access_token").asText();
    }

    private String exec(List<String> command) throws Exception {
        StringBuilder query = new StringBuilder("?stdout=true&stderr=true");
        command.forEach(part -> query.append("&command=").append(enc(part)));
        URI uri = URI.create(apiUrl.replace("https://", "wss://") + "/api/v1/namespaces/oauth/pods/" + podName
                + "/exec" + query);
        StringBuilder stdout = new StringBuilder();
        CompletableFuture<String> done = new CompletableFuture<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final ByteArrayOutputStream frame = new ByteArrayOutputStream();

            @Override
            public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
                byte[] bytes = new byte[data.remaining()];
                data.get(bytes);
                frame.writeBytes(bytes);
                if (last) {
                    byte[] message = frame.toByteArray();
                    frame.reset();
                    if (message.length > 0 && message[0] == 1) {
                        stdout.append(new String(message, 1, message.length - 1, StandardCharsets.UTF_8));
                    }
                }
                socket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
                done.complete(stdout.toString());
                return null;
            }

            @Override
            public void onError(WebSocket socket, Throwable error) {
                done.completeExceptionally(error);
            }
        };
        http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                .subprotocols("v5.channel.k8s.io", "v4.channel.k8s.io").buildAsync(uri, listener)
                .get(30, TimeUnit.SECONDS);
        return done.get(30, TimeUnit.SECONDS);
    }

    private JsonNode selfSubjectReview(Map<String, String> headers) throws Exception {
        Map<String, String> all = new HashMap<>(headers);
        all.put("Content-Type", "application/json");
        HttpResponse<String> response = send("POST", "/apis/authentication.k8s.io/v1/selfsubjectreviews",
                "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"SelfSubjectReview\"}", all);
        assertEquals(201, response.statusCode(), response.body());
        return MAPPER.readTree(response.body()).path("status").path("userInfo");
    }

    private String adminPost(String path, String json) throws Exception {
        HttpResponse<String> response = adminClient().send(HttpRequest.newBuilder(URI.create(apiUrl + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201, response.body());
        return response.body();
    }

    private HttpResponse<String> adminSend(String method, String path) throws Exception {
        return adminClient().send(HttpRequest.newBuilder(URI.create(apiUrl + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient adminClient() {
        return admin.http;
    }

    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder = request(path, headers);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(Duration.ofSeconds(30));
        headers.forEach(builder::header);
        return builder;
    }

    private Map<String, String> bearer() {
        return Map.of("Authorization", "Bearer " + token);
    }

    private static SSLContext trusting(String pem) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        int i = 0;
        for (Certificate ca : CertificateFactory.getInstance("X.509").generateCertificates(
                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
            trust.setCertificateEntry("ca-" + i++, ca);
        }
        TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trusts.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trusts.getTrustManagers(), null);
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

    private static String run(Map<String, String> variables, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(variables);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), String.join(" ", command));
        assertEquals(0, process.exitValue(), String.join(" ", command) + ": " + output);
        return output;
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String basic(String user, String pw) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String field(String kubeconfig, String name) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + ":\\s*(\\S+)\\s*$").matcher(kubeconfig);
        assertTrue(m.find(), name + " missing from the kubeconfig");
        return m.group(1);
    }
}
