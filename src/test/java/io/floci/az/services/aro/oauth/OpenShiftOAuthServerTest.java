package io.floci.az.services.aro.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class OpenShiftOAuthServerTest {

    private static final String HOST = "localhost:7700";
    private static final String ISSUER = "https://" + HOST;
    private static final String CHALLENGE_REDIRECT = ISSUER + "/oauth/token/implicit";
    private static final String PASSWORD = "abcde-fghij-kmnop-qrstu";
    private static final String CONSOLE_URL = "https://console-openshift-console.apps.demo.127.0.0.1.nip.io:7602";
    private static final String CONSOLE_SECRET = "console-secret";
    private static final Pattern TOKEN = Pattern.compile("^sha256~[A-Za-z0-9_][A-Za-z0-9_-]{42}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
    private final OAuthTokenStore tokens = OAuthTokenStore.inMemory();
    private final FakeProvider kubeadmin = new FakeProvider();
    private final OpenShiftOAuthServer server = new OpenShiftOAuthServer(List.of(kubeadmin), tokens,
            new OpenShiftOAuthServer.Settings(86400, CONSOLE_SECRET, CONSOLE_URL), clock);

    @Test
    void theMetadataIsTheAuthenticationOperatorsWithAnIssuerFromTheHostHeader() throws Exception {
        for (String host : List.of(HOST, "api.demo.eastus.aroapp.io:7700")) {
            OAuthResponse response = server.handle(get("/.well-known/oauth-authorization-server", host, Map.of()));
            assertEquals(200, response.status());
            assertEquals("application/json", response.header("Content-Type"));
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("issuer", "https://" + host);
            expected.put("authorization_endpoint", "https://" + host + "/oauth/authorize");
            expected.put("token_endpoint", "https://" + host + "/oauth/token");
            expected.put("scopes_supported", List.of("user:check-access", "user:full", "user:info",
                    "user:list-projects", "user:list-scoped-projects"));
            expected.put("response_types_supported", List.of("code", "token"));
            expected.put("grant_types_supported", List.of("authorization_code", "implicit"));
            expected.put("code_challenge_methods_supported", List.of("plain", "S256"));
            assertEquals(MAPPER.valueToTree(expected), MAPPER.readTree(response.body()));
        }
    }

    @Test
    void withoutACsrfHeaderTheChallengeIsAWarningAndNoBasicChallenge() {
        OAuthResponse response = server.handle(get(challengeAuthorize("S256", challenge("S256", verifier()), null),
                HOST, Map.of()));
        assertEquals(401, response.status());
        assertNull(response.header("WWW-Authenticate"));
        assertEquals("199 Origin \"A non-empty X-CSRF-Token header is required to receive basic-auth challenges\"",
                response.header("Warning"));
    }

    @Test
    void aCsrfHeaderWithoutCredentialsGetsABasicChallengeAndAWrongPasswordGetsItAgain() {
        String uri = challengeAuthorize("S256", challenge("S256", verifier()), null);
        OAuthResponse first = server.handle(get(uri, HOST, Map.of("X-CSRF-Token", List.of("1"))));
        assertEquals(401, first.status());
        assertEquals("Basic realm=\"openshift\"", first.header("WWW-Authenticate"));

        OAuthResponse wrong = server.handle(get(uri, HOST, Map.of("X-CSRF-Token", List.of("1"),
                "Authorization", List.of(basic("kubeadmin", "wrong-wrong-wrong-wrong")))));
        assertEquals(401, wrong.status());
        assertEquals("Basic realm=\"openshift\"", wrong.header("WWW-Authenticate"));
    }

    @Test
    void theRightPasswordRedirectsWithACodeAndTheStateEchoed() {
        OAuthResponse response = server.handle(get(challengeAuthorize("S256", challenge("S256", verifier()), "xyz"),
                HOST, challengeHeaders()));
        assertEquals(302, response.status());
        String location = response.header("Location");
        assertTrue(location.startsWith(CHALLENGE_REDIRECT + "?code=sha256~"), location);
        assertTrue(TOKEN.matcher(param(location, "code")).matches(), location);
        assertEquals("xyz", param(location, "state"));
    }

    @Test
    void theCodeIsExchangedForASha256TokenWithS256AndPlainPkce() throws Exception {
        for (String method : List.of("S256", "plain")) {
            String verifier = verifier();
            String code = code(challengeAuthorize(method, challenge(method, verifier), null));

            OAuthResponse response = server.handle(tokenRequest(code, CHALLENGE_REDIRECT, verifier));
            assertEquals(200, response.status(), response.bodyText());
            JsonNode body = MAPPER.readTree(response.body());
            String token = body.path("access_token").asText();
            assertTrue(TOKEN.matcher(token).matches(), token);
            assertEquals("Bearer", body.path("token_type").asText());
            assertEquals(86400, body.path("expires_in").asLong());
            assertEquals("user:full", body.path("scope").asText());
            assertFalse(body.has("refresh_token"));

            String objectName = "sha256~" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.substring(7).getBytes(StandardCharsets.UTF_8)));
            assertEquals(objectName, OAuthTokens.objectName(token));
            assertTrue(tokens.get(objectName).isPresent());
            assertTrue(tokens.snapshot().keySet().stream().noneMatch(name -> name.equals(token)));

            OpenShiftOAuthServer.Authenticated who = server.authenticateToken(token).orElseThrow();
            assertEquals("kube:admin", who.user().name());
            assertEquals(List.of("system:cluster-admins"), who.user().groups());
            assertEquals(List.of("user:full"), who.scopes());
        }
    }

    @Test
    void aWrongVerifierAReusedOrExpiredCodeGiveInvalidGrantAndAnotherRedirectInvalidRequest() throws Exception {
        String verifier = verifier();
        String code = code(challengeAuthorize("S256", challenge("S256", verifier), null));
        assertError(server.handle(tokenRequest(code, CHALLENGE_REDIRECT, verifier())), 400, "invalid_grant");

        code = code(challengeAuthorize("S256", challenge("S256", verifier), null));
        assertEquals(200, server.handle(tokenRequest(code, CHALLENGE_REDIRECT, verifier)).status());
        assertError(server.handle(tokenRequest(code, CHALLENGE_REDIRECT, verifier)), 400, "invalid_grant");

        code = code(challengeAuthorize("S256", challenge("S256", verifier), null));
        clock.advance(Duration.ofSeconds(301));
        assertError(server.handle(tokenRequest(code, CHALLENGE_REDIRECT, verifier)), 400, "invalid_grant");

        code = code(challengeAuthorize("S256", challenge("S256", verifier), null));
        assertError(server.handle(tokenRequest(code, ISSUER + "/oauth/token/implicit/other", verifier)), 400,
                "invalid_request");
        assertError(server.handle(tokenRequest(code, "https://elsewhere:7700/oauth/token/implicit", verifier)),
                400, "invalid_request");
    }

    @Test
    void anUnknownClientOrRedirectIsRefusedBeforeAnyChallenge() throws Exception {
        assertError(server.handle(get("/oauth/authorize?client_id=nobody&response_type=code", HOST,
                challengeHeaders())), 400, "unauthorized_client");
        assertError(server.handle(get("/oauth/authorize?client_id=openshift-challenging-client&response_type=code"
                + "&redirect_uri=" + enc("https://evil.example/oauth/token/implicit"), HOST, challengeHeaders())),
                400, "invalid_request");
        OAuthResponse unsupported = server.handle(get("/oauth/authorize?client_id=openshift-challenging-client"
                + "&response_type=id_token&redirect_uri=" + enc(CHALLENGE_REDIRECT), HOST, challengeHeaders()));
        assertEquals(302, unsupported.status());
        assertEquals("unsupported_response_type", param(unsupported.header("Location"), "error"));
    }

    @Test
    void anExpiredTokenOrAProviderThatDropsTheUserNoLongerAuthenticates() {
        String token = login();
        assertTrue(server.authenticateToken(token).isPresent());
        kubeadmin.valid = false;
        assertTrue(server.authenticateToken(token).isEmpty());
        kubeadmin.valid = true;
        clock.advance(Duration.ofSeconds(86400));
        assertTrue(server.authenticateToken(token).isEmpty());
        assertTrue(server.authenticateToken("sha256~unknown").isEmpty());
        assertTrue(server.authenticateToken("not-a-token").isEmpty());
    }

    @Test
    void revocationIsByObjectNameAndOnlyForTheOwnerOrAnAdministrator() throws Exception {
        String token = login();
        String name = OAuthTokens.objectName(token);
        assertEquals(403, server.revoke(name, "someone", List.of("system:authenticated")).status());
        OAuthResponse deleted = server.revoke(name, "kube:admin", List.of("system:cluster-admins"));
        assertEquals(200, deleted.status());
        JsonNode status = MAPPER.readTree(deleted.body());
        assertEquals("Success", status.path("status").asText());
        assertEquals("oauthaccesstokens", status.path("details").path("kind").asText());
        assertTrue(server.authenticateToken(token).isEmpty());
        OAuthResponse missing = server.revoke(name, "system:admin", List.of("system:masters"));
        assertEquals(404, missing.status());
        assertEquals("NotFound", MAPPER.readTree(missing.body()).path("reason").asText());
    }

    @Test
    void theVirtualUserHasTheSortedGroupsWithSystemAuthenticated() throws Exception {
        JsonNode user = MAPPER.readTree(OpenShiftOAuthServer.virtualUser("kube:admin", "",
                List.of("system:cluster-admins")).body());
        assertEquals("User", user.path("kind").asText());
        assertEquals("user.openshift.io/v1", user.path("apiVersion").asText());
        assertEquals("kube:admin", user.path("metadata").path("name").asText());
        assertEquals("[\"system:authenticated\",\"system:cluster-admins\"]", user.path("groups").toString());
        assertTrue(user.path("identities").isNull());
    }

    @Test
    void theConsoleLogsInThroughTheLoginPageAndASessionCookie() throws Exception {
        String callback = CONSOLE_URL + "/auth/callback";
        String authorize = "/oauth/authorize?client_id=console&redirect_uri=" + enc(callback)
                + "&response_type=code&scope=user%3Afull&state=s1";
        OAuthResponse toLogin = server.handle(get(authorize, HOST, Map.of()));
        assertEquals(302, toLogin.status());
        String loginUri = toLogin.header("Location");
        assertTrue(loginUri.startsWith("/login/kube:admin?then="), loginUri);
        assertEquals(authorize, param(loginUri, "then"));

        OAuthResponse page = server.handle(get(loginUri, HOST, Map.of()));
        assertEquals(200, page.status());
        assertTrue(page.bodyText().contains("name=\"password\""));

        OAuthResponse refused = server.handle(post("/login/kube%3Aadmin", HOST, Map.of(),
                "then=" + enc(authorize) + "&username=kubeadmin&password=wrong"));
        assertEquals(302, refused.status());
        assertTrue(refused.header("Location").contains("reason=access_denied"));

        OAuthResponse loggedIn = server.handle(post("/login/kube%3Aadmin", HOST, Map.of(),
                "then=" + enc(authorize) + "&username=kubeadmin&password=" + PASSWORD));
        assertEquals(302, loggedIn.status());
        assertEquals(authorize, loggedIn.header("Location"));
        String cookie = loggedIn.header("Set-Cookie").split(";")[0];
        assertTrue(cookie.startsWith("ssn="), cookie);

        OAuthResponse withCode = server.handle(get(authorize, HOST, Map.of("Cookie", List.of(cookie))));
        assertEquals(302, withCode.status());
        String location = withCode.header("Location");
        assertTrue(location.startsWith(callback + "?code="), location);
        assertEquals("s1", param(location, "state"));
        String code = param(location, "code");

        OAuthResponse badSecret = server.handle(post("/oauth/token", HOST, Map.of(), "grant_type=authorization_code"
                + "&code=" + code + "&redirect_uri=" + enc(callback) + "&client_id=console&client_secret=nope"));
        assertError(badSecret, 400, "unauthorized_client");
        OAuthResponse token = server.handle(post("/oauth/token", HOST, Map.of(), "grant_type=authorization_code"
                + "&code=" + code + "&redirect_uri=" + enc(callback) + "&client_id=console&client_secret="
                + CONSOLE_SECRET));
        assertEquals(200, token.status(), token.bodyText());
        String accessToken = MAPPER.readTree(token.body()).path("access_token").asText();
        assertEquals("console", tokens.get(OAuthTokens.objectName(accessToken)).orElseThrow().clientId());

        OAuthResponse logout = server.handle(get("/logout?then=" + enc(CONSOLE_URL), HOST,
                Map.of("Cookie", List.of(cookie))));
        assertEquals(302, logout.status());
        assertEquals(CONSOLE_URL, logout.header("Location"));
        assertTrue(logout.header("Set-Cookie").startsWith("ssn=;"));
        assertEquals(302, server.handle(get(authorize, HOST, Map.of("Cookie", List.of(cookie)))).status());
        assertTrue(server.handle(get(authorize, HOST, Map.of("Cookie", List.of(cookie)))).header("Location")
                .startsWith("/login/"));
    }

    @Test
    void theCliClientAcceptsAnyLoopbackPort() {
        OAuthResponse response = server.handle(get("/oauth/authorize?client_id=openshift-cli-client"
                + "&response_type=code&redirect_uri=" + enc("http://127.0.0.1:51234/callback"), HOST, Map.of()));
        assertEquals(302, response.status());
        assertTrue(response.header("Location").startsWith("/login/"));
    }

    @Test
    void theTokenRequestPageLeadsToADisplayedToken() {
        OAuthResponse request = server.handle(get("/oauth/token/request", HOST, Map.of()));
        assertEquals(302, request.status());
        String authorize = request.header("Location");
        assertTrue(authorize.contains("client_id=openshift-browser-client"), authorize);
        String session = server.handle(post("/login/kube%3Aadmin", HOST, Map.of(), "then=" + enc(authorize)
                + "&username=kubeadmin&password=" + PASSWORD)).header("Set-Cookie").split(";")[0];
        String display = server.handle(get(authorize, HOST, Map.of("Cookie", List.of(session)))).header("Location");
        assertTrue(display.startsWith(ISSUER + "/oauth/token/display?code="), display);
        String code = param(display, "code");
        OAuthResponse shown = server.handle(post("/oauth/token/display", HOST, Map.of(), "code=" + code));
        assertEquals(200, shown.status());
        Matcher token = Pattern.compile("sha256~[A-Za-z0-9_-]{43}").matcher(shown.bodyText());
        assertTrue(token.find(), shown.bodyText());
        assertTrue(shown.bodyText().contains("oc login --token=" + token.group() + " --server=" + ISSUER));
    }

    @Test
    void onlyTheOAuthPathsAreHandled() {
        assertTrue(server.handles("/.well-known/oauth-authorization-server"));
        assertTrue(server.handles("/oauth/authorize"));
        assertTrue(server.handles("/oauth/token"));
        assertTrue(server.handles("/login/kube:admin"));
        assertTrue(server.handles("/logout"));
        assertFalse(server.handles("/api/v1/nodes"));
        assertFalse(server.handles("/oauthx"));
        assertFalse(server.handles("/loginx"));
    }

    private String login() {
        String verifier = verifier();
        String code = code(challengeAuthorize("S256", challenge("S256", verifier), null));
        try {
            return MAPPER.readTree(server.handle(tokenRequest(code, CHALLENGE_REDIRECT, verifier)).body())
                    .path("access_token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String code(String authorizeUri) {
        OAuthResponse response = server.handle(get(authorizeUri, HOST, challengeHeaders()));
        assertEquals(302, response.status());
        return param(response.header("Location"), "code");
    }

    private static Map<String, List<String>> challengeHeaders() {
        return Map.of("X-CSRF-Token", List.of("1"), "Authorization", List.of(basic("kubeadmin", PASSWORD)));
    }

    private static String challengeAuthorize(String method, String challenge, String state) {
        return "/oauth/authorize?client_id=openshift-challenging-client&code_challenge=" + challenge
                + "&code_challenge_method=" + method + "&redirect_uri=" + enc(CHALLENGE_REDIRECT)
                + "&response_type=code" + (state == null ? "" : "&state=" + state);
    }

    private static OAuthRequest tokenRequest(String code, String redirect, String verifier) {
        return post("/oauth/token", HOST, Map.of("Authorization", List.of(basic("openshift-challenging-client", ""))),
                "grant_type=authorization_code&code=" + code + "&redirect_uri=" + enc(redirect) + "&code_verifier="
                        + verifier);
    }

    private static OAuthRequest get(String uri, String host, Map<String, List<String>> headers) {
        return OAuthRequest.of("GET", uri, host, headers, null);
    }

    private static OAuthRequest post(String uri, String host, Map<String, List<String>> headers, String form) {
        Map<String, List<String>> all = new LinkedHashMap<>(headers);
        all.put("Content-Type", List.of("application/x-www-form-urlencoded"));
        return OAuthRequest.of("POST", uri, host, all, form.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertError(OAuthResponse response, int status, String error) throws Exception {
        assertEquals(status, response.status(), response.bodyText());
        assertEquals(error, MAPPER.readTree(response.body()).path("error").asText(), response.bodyText());
    }

    private static String param(String uri, String name) {
        String query = URI.create(uri).getRawQuery();
        List<String> values = OAuthRequest.parseForm(query).get(name);
        return values == null ? null : values.getFirst();
    }

    private static String verifier() {
        byte[] bytes = new byte[96];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String method, String verifier) {
        if ("plain".equals(method)) {
            return verifier;
        }
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class FakeProvider implements IdentityProvider {
        boolean valid = true;

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
            return ("kubeadmin".equals(username) || "kube:admin".equals(username)) && PASSWORD.equals(password)
                    ? Optional.of(new OAuthUser("kube:admin", "uid-1", name(), List.of("system:cluster-admins")))
                    : Optional.empty();
        }

        @Override
        public Optional<OAuthUser> revalidate(OAuthToken token) {
            return valid ? Optional.of(new OAuthUser("kube:admin", token.userUid(), name(),
                    List.of("system:cluster-admins"))) : Optional.empty();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
