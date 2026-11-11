package io.floci.az.services.aro.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The OpenShift OAuth server a cluster's API URL serves, as the {@code oauth-server} of OpenShift 4.19 behaves for
 * the clients {@code oc} and the console use: the authorization server metadata, the authorization code flow with
 * PKCE (basic-auth challenges for {@code openshift-challenging-client}, a login page and a session cookie for the
 * others), the token endpoint, the token display pages, logout, and the token lookup and revocation the API proxy
 * needs. Pure logic: requests and responses are plain values, and {@link IdentityProvider} calls may block.
 *
 * <p>Sources: {@code openshift/oauth-server} {@code release-4.19} ({@code vendor/github.com/openshift/osin}
 * {@code authorize.go}, {@code access.go}, {@code urivalidate.go}, {@code response.go}; {@code pkg/osinserver};
 * {@code pkg/oauth/handlers/grant.go}; {@code pkg/authenticator/challenger/passwordchallenger}),
 * {@code openshift/cluster-authentication-operator} ({@code metadata_controller.go},
 * {@code oauthclientscontroller.go}) and {@code openshift/oauth-apiserver} ({@code user/.../etcd.go}).</p>
 */
public final class OpenShiftOAuthServer {

    public static final String METADATA_PATH = "/.well-known/oauth-authorization-server";
    public static final String CHALLENGING_CLIENT = "openshift-challenging-client";
    public static final String BROWSER_CLIENT = "openshift-browser-client";
    public static final String CLI_CLIENT = "openshift-cli-client";
    public static final String CONSOLE_CLIENT = "console";
    public static final List<String> SCOPES = List.of("user:check-access", "user:full", "user:info",
            "user:list-projects", "user:list-scoped-projects");
    static final long CODE_LIFETIME_SECONDS = 300;
    static final long SESSION_LIFETIME_SECONDS = 300;

    private static final String SESSION_COOKIE = "ssn";
    private static final Pattern PKCE = Pattern.compile("^[a-zA-Z0-9~._-]{43,128}$");
    private static final List<String> ADMIN_GROUPS = List.of("system:masters", "system:cluster-admins");
    private static final String INVALID_REQUEST = "invalid_request";
    private static final String UNAUTHORIZED_CLIENT = "unauthorized_client";
    private static final String INVALID_GRANT = "invalid_grant";
    private static final Map<String, String> ERROR_DESCRIPTIONS = Map.of(
            INVALID_REQUEST, "The request is missing a required parameter, includes an invalid parameter value, "
                    + "includes a parameter more than once, or is otherwise malformed.",
            UNAUTHORIZED_CLIENT, "The client is not authorized to request a token using this method.",
            "unsupported_response_type", "The authorization server does not support obtaining a token using this "
                    + "method.",
            "invalid_scope", "The requested scope is invalid, unknown, or malformed.",
            "unsupported_grant_type", "The authorization grant type is not supported by the authorization server.",
            INVALID_GRANT, "The provided authorization grant (e.g., authorization code, resource owner credentials) "
                    + "or refresh token is invalid, expired, revoked, does not match the redirection URI used in the "
                    + "authorization request, or was issued to another client.");

    /**
     * Per-cluster settings: the access token lifetime, and the console's OAuth client (its secret and the console URL,
     * whose {@code /auth/callback} is the client's redirect URI); no console client when the URL is {@code null}.
     */
    public record Settings(long accessTokenMaxAgeSeconds, String consoleClientSecret, String consoleUrl) {
    }

    /** Who a minted token stands for: the user now, the token's scopes and its object name. */
    public record Authenticated(OAuthUser user, List<String> scopes, String objectName) {
    }

    private record Client(String id, String secret, List<String> redirectUris, boolean challenges) {
    }

    private record Code(String clientId, String redirectUri, List<String> scopes, String challenge, String method,
                        OAuthUser user, Instant expiresAt) {
    }

    private record Session(OAuthUser user, Instant expiresAt) {
    }

    private final List<IdentityProvider> providers;
    private final OAuthTokenStore tokens;
    private final Settings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String browserClientSecret;
    private final Map<String, Code> codes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public OpenShiftOAuthServer(List<IdentityProvider> providers, OAuthTokenStore tokens, Settings settings,
                                Clock clock) {
        this.providers = List.copyOf(providers);
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
        this.browserClientSecret = OAuthTokens.newToken(random);
    }

    /** Whether {@code path} belongs to the OAuth server rather than to the API server behind the proxy. */
    public boolean handles(String path) {
        return METADATA_PATH.equals(path) || path.startsWith("/oauth/") || "/login".equals(path)
                || path.startsWith("/login/") || "/logout".equals(path);
    }

    public OAuthResponse handle(OAuthRequest request) {
        String issuer = "https://" + request.host();
        String path = request.path();
        if (METADATA_PATH.equals(path)) {
            return metadata(issuer);
        }
        return switch (path) {
            case "/oauth/authorize" -> authorize(request, issuer);
            case "/oauth/token" -> token(request, issuer);
            case "/oauth/token/request" -> OAuthResponse.redirect("/oauth/authorize?" + encode(sorted(Map.of(
                    "client_id", BROWSER_CLIENT, "redirect_uri", issuer + "/oauth/token/display",
                    "response_type", "code"))));
            case "/oauth/token/display" -> display(request, issuer);
            case "/oauth/token/implicit" -> OAuthResponse.html(200, page("Authenticated",
                    "<p>You have authenticated. You can close this window.</p>"));
            case "/logout" -> logout(request);
            default -> path.startsWith("/login/") ? login(request) : OAuthResponse.status(404).build();
        };
    }

    /**
     * The token a request presents, when it is one this server minted and still authenticates: not expired, and its
     * provider still vouches for the user. May block on the provider.
     */
    public Optional<Authenticated> authenticateToken(String token) {
        if (token == null || !token.startsWith(OAuthTokens.PREFIX)) {
            return Optional.empty();
        }
        String name = OAuthTokens.objectName(token);
        Optional<OAuthToken> stored = tokens.get(name);
        if (stored.isEmpty() || stored.get().expiredAt(clock.instant())) {
            return Optional.empty();
        }
        return provider(stored.get().provider())
                .flatMap(provider -> provider.revalidate(stored.get()))
                .map(user -> new Authenticated(user, stored.get().scopes(), name));
    }

    /**
     * {@code DELETE /apis/oauth.openshift.io/v1/oauthaccesstokens/<name>}: removes a token by object name, for its
     * owner or a cluster administrator.
     */
    public OAuthResponse revoke(String objectName, String requester, List<String> requesterGroups) {
        Optional<OAuthToken> stored = tokens.get(objectName);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", objectName);
        details.put("group", "oauth.openshift.io");
        details.put("kind", "oauthaccesstokens");
        if (stored.isEmpty()) {
            return kubeStatus(404, "Failure", "oauthaccesstokens.oauth.openshift.io \"" + objectName + "\" not found",
                    "NotFound", details);
        }
        boolean admin = requesterGroups.stream().anyMatch(ADMIN_GROUPS::contains);
        if (!admin && !stored.get().userName().equals(requester)) {
            return kubeStatus(403, "Failure", "oauthaccesstokens.oauth.openshift.io \"" + objectName + "\" is forbidden: "
                    + "User \"" + requester + "\" cannot delete resource \"oauthaccesstokens\" in API group "
                    + "\"oauth.openshift.io\" at the cluster scope", "Forbidden", details);
        }
        tokens.remove(objectName);
        return kubeStatus(200, "Success", null, null, details);
    }

    /**
     * {@code GET /apis/user.openshift.io/v1/users/~}: the virtual User oauth-apiserver builds from the request's user,
     * with its groups and {@code system:authenticated}, sorted and deduplicated.
     */
    public static OAuthResponse virtualUser(String name, String uid, List<String> groups) {
        TreeSet<String> sortedGroups = new TreeSet<>(groups);
        sortedGroups.add("system:authenticated");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        if (uid != null && !uid.isEmpty()) {
            metadata.put("uid", uid);
        }
        metadata.put("creationTimestamp", null);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("kind", "User");
        user.put("apiVersion", "user.openshift.io/v1");
        user.put("metadata", metadata);
        user.put("identities", null);
        user.put("groups", new ArrayList<>(sortedGroups));
        return OAuthResponse.json(200, user);
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    private static OAuthResponse metadata(String issuer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("authorization_endpoint", issuer + "/oauth/authorize");
        body.put("token_endpoint", issuer + "/oauth/token");
        body.put("scopes_supported", SCOPES);
        body.put("response_types_supported", List.of("code", "token"));
        body.put("grant_types_supported", List.of("authorization_code", "implicit"));
        body.put("code_challenge_methods_supported", List.of("plain", "S256"));
        return OAuthResponse.json(200, body);
    }

    // ── Authorize ────────────────────────────────────────────────────────────

    private OAuthResponse authorize(OAuthRequest request, String issuer) {
        String state = nonNull(request.param("state"));
        String redirectUri;
        try {
            redirectUri = URLDecoder.decode(nonNull(request.param("redirect_uri")), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return jsonError(INVALID_REQUEST, null, "");
        }
        Client client = clients(issuer).get(nonNull(request.param("client_id")));
        if (client == null) {
            return jsonError(UNAUTHORIZED_CLIENT, null, state);
        }
        if (redirectUri.isEmpty() && client.redirectUris().size() == 1) {
            redirectUri = client.redirectUris().getFirst();
        }
        Optional<String> validated = validateRedirect(client, redirectUri);
        if (validated.isEmpty()) {
            return jsonError(INVALID_REQUEST, null, state);
        }
        redirectUri = validated.get();

        String responseType = nonNull(request.param("response_type"));
        if (!"code".equals(responseType) && !"token".equals(responseType)) {
            return redirectError(redirectUri, "unsupported_response_type", null, state);
        }
        String challenge = "";
        String method = "";
        if ("code".equals(responseType)) {
            challenge = nonNull(request.param("code_challenge"));
            if (!challenge.isEmpty()) {
                method = nonNull(request.param("code_challenge_method"));
                if (method.isEmpty()) {
                    method = "plain";
                }
                if (!"plain".equals(method) && !"S256".equals(method)) {
                    return redirectError(redirectUri, INVALID_REQUEST,
                            "code_challenge_method transform algorithm not supported (rfc7636)", state);
                }
                if (!PKCE.matcher(challenge).matches()) {
                    return redirectError(redirectUri, INVALID_REQUEST, "code_challenge invalid (rfc7636)", state);
                }
            }
        }

        OAuthUser user;
        if (client.challenges()) {
            if (nonNull(request.header("X-CSRF-Token")).isEmpty()) {
                return OAuthResponse.status(401).header("Warning", "199 Origin \"A non-empty X-CSRF-Token header is "
                        + "required to receive basic-auth challenges\"").build();
            }
            user = basicAuth(request).orElse(null);
            if (user == null) {
                return OAuthResponse.status(401).header("WWW-Authenticate", "Basic realm=\"openshift\"").build();
            }
        } else {
            user = session(request).orElse(null);
            if (user == null) {
                return loginRedirect(request);
            }
        }

        List<String> scopes = splitScopes(request.param("scope"));
        for (String scope : scopes) {
            if (!SCOPES.contains(scope)) {
                return redirectError(redirectUri, "invalid_scope", "scope " + scope + " is not a valid scope",
                        state);
            }
        }
        if ("token".equals(responseType)) {
            String token = mint(user, client.id(), scopes);
            Map<String, String> fragment = new TreeMap<>();
            fragment.put("access_token", token);
            fragment.put("expires_in", String.valueOf(settings.accessTokenMaxAgeSeconds()));
            fragment.put("scope", String.join(" ", scopes));
            fragment.put("token_type", "Bearer");
            if (!state.isEmpty()) {
                fragment.put("state", state);
            }
            return OAuthResponse.redirect(withoutQueryAndFragment(redirectUri) + "#" + encode(fragment));
        }
        String code = OAuthTokens.newToken(random);
        codes.put(code, new Code(client.id(), redirectUri, scopes, challenge, method, user,
                clock.instant().plusSeconds(CODE_LIFETIME_SECONDS)));
        Map<String, String> params = new TreeMap<>();
        params.put("code", code);
        params.put("state", state);
        return OAuthResponse.redirect(withParams(redirectUri, params));
    }

    private Optional<OAuthUser> basicAuth(OAuthRequest request) {
        String header = nonNull(request.header("Authorization"));
        if (header.length() < 6 || !header.substring(0, 6).equalsIgnoreCase("basic ")) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        String username = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        for (IdentityProvider provider : providers) {
            if (provider.challenge()) {
                Optional<OAuthUser> user = provider.authenticate(username, password);
                if (user.isPresent()) {
                    return user;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<OAuthUser> session(OAuthRequest request) {
        String id = request.cookie(SESSION_COOKIE);
        Session session = id == null ? null : sessions.get(id);
        if (session == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(session.expiresAt())) {
            sessions.remove(id);
            return Optional.empty();
        }
        return Optional.of(session.user());
    }

    private OAuthResponse loginRedirect(OAuthRequest request) {
        List<IdentityProvider> login = providers.stream().filter(IdentityProvider::login).toList();
        String then = request.serverRelativeUri();
        if (login.isEmpty()) {
            return OAuthResponse.html(403, page("Login unavailable", "<p>No identity provider allows login.</p>"));
        }
        if (login.size() == 1) {
            return OAuthResponse.redirect(loginPath(login.getFirst().name()) + "?then=" + queryEscape(then));
        }
        StringBuilder list = new StringBuilder("<h1>Log in with&hellip;</h1><ul>");
        for (IdentityProvider provider : login) {
            list.append("<li><a href=\"").append(escapeHtml(loginPath(provider.name()) + "?then=" + queryEscape(then)))
                    .append("\">").append(escapeHtml(provider.name())).append("</a></li>");
        }
        return OAuthResponse.html(200, page("Log in", list.append("</ul>").toString()));
    }

    // ── Login, logout ────────────────────────────────────────────────────────

    private OAuthResponse login(OAuthRequest request) {
        String name = URLDecoder.decode(request.path().substring("/login/".length()), StandardCharsets.UTF_8);
        Optional<IdentityProvider> provider = provider(name).filter(IdentityProvider::login);
        if (provider.isEmpty()) {
            return OAuthResponse.status(404).build();
        }
        if ("POST".equals(request.method())) {
            String then = nonNull(request.formParam("then"));
            if (!then.startsWith("/oauth/authorize")) {
                then = "/oauth/token/request";
            }
            Optional<OAuthUser> user = provider.get().authenticate(nonNull(request.formParam("username")),
                    nonNull(request.formParam("password")));
            if (user.isEmpty()) {
                return OAuthResponse.redirect(loginPath(name) + "?then=" + queryEscape(then)
                        + "&reason=access_denied");
            }
            String id = OAuthTokens.newToken(random).substring(OAuthTokens.PREFIX.length());
            sessions.entrySet().removeIf(entry -> !clock.instant().isBefore(entry.getValue().expiresAt()));
            sessions.put(id, new Session(user.get(), clock.instant().plusSeconds(SESSION_LIFETIME_SECONDS)));
            return OAuthResponse.status(302).header("Location", then)
                    .header("Set-Cookie", SESSION_COOKIE + "=" + id + "; Path=/; Max-Age=" + SESSION_LIFETIME_SECONDS
                            + "; HttpOnly; Secure; SameSite=Lax").build();
        }
        String then = nonNull(request.queryParam("then"));
        String error = "access_denied".equals(request.queryParam("reason"))
                ? "<p class=\"error\">Invalid login or password. Please try again.</p>" : "";
        return OAuthResponse.html(200, page("Log in", "<h1>Log in to your account</h1>" + error
                + "<form method=\"post\" action=\"" + escapeHtml(loginPath(name)) + "\">"
                + "<input type=\"hidden\" name=\"then\" value=\"" + escapeHtml(then) + "\">"
                + "<label>Username <input type=\"text\" name=\"username\" autofocus></label>"
                + "<label>Password <input type=\"password\" name=\"password\"></label>"
                + "<button type=\"submit\">Log in</button></form>"));
    }

    private OAuthResponse logout(OAuthRequest request) {
        String id = request.cookie(SESSION_COOKIE);
        if (id != null) {
            sessions.remove(id);
        }
        String clear = SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax";
        String then = nonNull(request.param("then"));
        boolean relative = then.startsWith("/") && !then.startsWith("//");
        boolean console = settings.consoleUrl() != null && !then.isEmpty() && then.startsWith(settings.consoleUrl());
        if (relative || console) {
            return OAuthResponse.status(302).header("Location", then).header("Set-Cookie", clear).build();
        }
        OAuthResponse page = OAuthResponse.html(200, page("Logged out", "<p>You have logged out.</p>"));
        page.headers().put("Set-Cookie", List.of(clear));
        return page;
    }

    // ── Token ────────────────────────────────────────────────────────────────

    private OAuthResponse token(OAuthRequest request, String issuer) {
        if (!"POST".equals(request.method()) && !"GET".equals(request.method())) {
            return jsonError(INVALID_REQUEST, null, "");
        }
        if (!"authorization_code".equals(request.param("grant_type"))) {
            return jsonError("unsupported_grant_type", null, "");
        }
        String clientId;
        String secret;
        if (request.hasParam("client_secret") && !nonNull(request.param("client_id")).isEmpty()) {
            clientId = request.param("client_id");
            secret = nonNull(request.param("client_secret"));
        } else {
            String header = request.header("Authorization");
            if (header == null || header.isEmpty()) {
                return jsonError(INVALID_REQUEST, null, "");
            }
            String[] credentials = clientCredentials(header);
            if (credentials == null) {
                return jsonError(INVALID_REQUEST, null, "");
            }
            clientId = credentials[0];
            secret = credentials[1];
        }
        Exchange exchange = exchange(issuer, clientId, secret, nonNull(request.param("code")),
                nonNull(request.param("redirect_uri")), nonNull(request.param("code_verifier")));
        if (exchange.error() != null) {
            return jsonError(exchange.error(), exchange.description(), "");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", exchange.token());
        body.put("expires_in", settings.accessTokenMaxAgeSeconds());
        body.put("scope", String.join(" ", exchange.scopes()));
        body.put("token_type", "Bearer");
        return noStore(OAuthResponse.json(200, body));
    }

    private record Exchange(String token, List<String> scopes, String error, String description) {
        static Exchange failed(String error, String description) {
            return new Exchange(null, List.of(), error, description);
        }
    }

    /** osin's {@code handleAuthorizationCodeRequest} and {@code FinishAccessRequest}, in its order of checks. */
    private Exchange exchange(String issuer, String clientId, String secret, String code, String redirectUri,
                              String verifier) {
        if (code.isEmpty()) {
            return Exchange.failed(INVALID_GRANT, null);
        }
        Client client = clients(issuer).get(clientId);
        if (client == null || !constantTimeEquals(client.secret(), secret)) {
            return Exchange.failed(UNAUTHORIZED_CLIENT, null);
        }
        Code grant = codes.get(code);
        if (grant == null || !clock.instant().isBefore(grant.expiresAt())
                || !grant.clientId().equals(client.id())) {
            return Exchange.failed(INVALID_GRANT, null);
        }
        if (redirectUri.isEmpty()) {
            redirectUri = client.redirectUris().getFirst();
        }
        Optional<String> validated = validateRedirect(client, redirectUri);
        if (validated.isEmpty() || !validated.get().equals(grant.redirectUri())) {
            return Exchange.failed(INVALID_REQUEST, null);
        }
        if (!grant.challenge().isEmpty()) {
            if (!PKCE.matcher(verifier).matches()) {
                return Exchange.failed(INVALID_REQUEST, null);
            }
            String expected = "S256".equals(grant.method()) ? OAuthTokens.s256(verifier) : verifier;
            if (!expected.equals(grant.challenge())) {
                return Exchange.failed(INVALID_GRANT, null);
            }
        }
        if (codes.remove(code) == null) {
            return Exchange.failed(INVALID_GRANT, null);
        }
        return new Exchange(mint(grant.user(), client.id(), grant.scopes()), grant.scopes(), null, null);
    }

    private String mint(OAuthUser user, String clientId, List<String> scopes) {
        String token = OAuthTokens.newToken(random);
        Instant now = clock.instant();
        codes.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        tokens.put(OAuthTokens.objectName(token), new OAuthToken(user.name(), user.uid(), user.provider(), clientId,
                scopes, now, settings.accessTokenMaxAgeSeconds()), now);
        return token;
    }

    private OAuthResponse display(OAuthRequest request, String issuer) {
        String code = nonNull(request.param("code"));
        if (code.isEmpty()) {
            return OAuthResponse.html(200, page("Display Token",
                    "<p><a href=\"/oauth/token/request\">Request another token</a></p>"));
        }
        if (!"POST".equals(request.method())) {
            return OAuthResponse.html(200, page("Display Token", "<form method=\"post\" action=\"/oauth/token/display\">"
                    + "<input type=\"hidden\" name=\"code\" value=\"" + escapeHtml(code) + "\">"
                    + "<button type=\"submit\">Display Token</button></form>"));
        }
        Exchange exchange = exchange(issuer, BROWSER_CLIENT, browserClientSecret, code,
                issuer + "/oauth/token/display", "");
        if (exchange.error() != null) {
            return OAuthResponse.html(400, page("Error", "<p>" + escapeHtml(exchange.error()) + "</p>"
                    + "<p><a href=\"/oauth/token/request\">Request another token</a></p>"));
        }
        String token = escapeHtml(exchange.token());
        return noStore(OAuthResponse.html(200, page("Display Token", "<h2>Your API token is</h2><code>" + token
                + "</code><h2>Log in with this token</h2><pre>oc login --token=" + token + " --server="
                + escapeHtml(issuer) + "</pre><h3>Use this token directly against the API</h3><pre>curl -H "
                + "\"Authorization: Bearer " + token + "\" \"" + escapeHtml(issuer) + "/apis/user.openshift.io/v1/"
                + "users/~\"</pre><p><a href=\"/oauth/token/request\">Request another token</a></p>")));
    }

    // ── Clients and redirects ────────────────────────────────────────────────

    /**
     * The OAuth clients a cluster has: the three the authentication operator creates, and the console's when the
     * console runs. Their redirect URIs derive from the issuer, which is the API URL the client used.
     */
    private Map<String, Client> clients(String issuer) {
        Map<String, Client> clients = new LinkedHashMap<>();
        clients.put(CHALLENGING_CLIENT, new Client(CHALLENGING_CLIENT, "", List.of(issuer + "/oauth/token/implicit"),
                true));
        clients.put(BROWSER_CLIENT, new Client(BROWSER_CLIENT, browserClientSecret,
                List.of(issuer + "/oauth/token/display"), false));
        clients.put(CLI_CLIENT, new Client(CLI_CLIENT, "", List.of("http://127.0.0.1/callback",
                "http://[::1]/callback"), false));
        if (settings.consoleUrl() != null && settings.consoleClientSecret() != null) {
            clients.put(CONSOLE_CLIENT, new Client(CONSOLE_CLIENT, settings.consoleClientSecret(),
                    List.of(stripSlash(settings.consoleUrl()) + "/auth/callback"), false));
        }
        return clients;
    }

    /** osin's {@code ValidateUriList}: same scheme and host (any port for a loopback IP base), path under the base. */
    private static Optional<String> validateRedirect(Client client, String redirectUri) {
        if (redirectUri.isEmpty()) {
            return Optional.empty();
        }
        for (String base : client.redirectUris()) {
            Optional<String> valid = validateUri(base, redirectUri);
            if (valid.isPresent()) {
                return valid;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> validateUri(String baseUri, String redirectUri) {
        URI base;
        URI redirect;
        try {
            base = new URI(baseUri);
            redirect = new URI(redirectUri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (base.getRawFragment() != null || redirect.getRawFragment() != null
                || redirect.getScheme() == null || !redirect.getScheme().equals(base.getScheme())) {
            return Optional.empty();
        }
        String host;
        if (base.getRawAuthority().equals(redirect.getRawAuthority())) {
            host = base.getRawAuthority();
        } else if (isLoopbackIp(base.getRawAuthority()) && "http".equals(base.getScheme())
                && redirect.getHost() != null && isLoopbackIp(redirect.getHost())) {
            host = redirect.getRawAuthority();
        } else {
            return Optional.empty();
        }
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        String redirectPath = redirect.getRawPath() == null ? "" : redirect.getRawPath();
        if (redirectPath.contains("/../") || redirectPath.endsWith("/..")) {
            return Optional.empty();
        }
        String requiredPrefix = basePath.replaceAll("/+$", "") + "/";
        if (!basePath.equals(redirectPath) && !redirectPath.startsWith(requiredPrefix)) {
            return Optional.empty();
        }
        String query = redirect.getRawQuery() == null ? "" : "?" + redirect.getRawQuery();
        return Optional.of(base.getScheme() + "://" + host + redirectPath + query);
    }

    private static boolean isLoopbackIp(String host) {
        return host.matches("127(\\.\\d{1,3}){3}") || "::1".equals(host);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Optional<IdentityProvider> provider(String name) {
        return providers.stream().filter(provider -> provider.name().equals(name)).findFirst();
    }

    private static String[] clientCredentials(String header) {
        String[] parts = header.split(" ", 2);
        if (parts.length != 2 || !"Basic".equals(parts[0])) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(parts[1].trim()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return null;
            }
            return new String[]{URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
                    URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> splitScopes(String scope) {
        List<String> scopes = new ArrayList<>();
        for (String value : nonNull(scope).split(" ")) {
            if (!value.isEmpty() && !scopes.contains(value)) {
                scopes.add(value);
            }
        }
        if (scopes.isEmpty()) {
            scopes.add("user:full");
        }
        return scopes;
    }

    private static OAuthResponse jsonError(String error, String description, String state) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description != null ? description : ERROR_DESCRIPTIONS.getOrDefault(error, ""));
        if (state != null && !state.isEmpty()) {
            body.put("state", state);
        }
        return noStore(OAuthResponse.json(400, body));
    }

    private static OAuthResponse redirectError(String redirectUri, String error, String description, String state) {
        Map<String, String> params = new TreeMap<>();
        params.put("error", error);
        params.put("error_description", description != null ? description : ERROR_DESCRIPTIONS.getOrDefault(error, ""));
        if (!state.isEmpty()) {
            params.put("state", state);
        }
        return OAuthResponse.redirect(withParams(redirectUri, params));
    }

    private static OAuthResponse noStore(OAuthResponse response) {
        response.headers().put("Cache-Control", List.of("no-cache, no-store, max-age=0, must-revalidate"));
        response.headers().put("Pragma", List.of("no-cache"));
        response.headers().put("Expires", List.of("Fri, 01 Jan 1990 00:00:00 GMT"));
        return response;
    }

    private static OAuthResponse kubeStatus(int code, String status, String message, String reason,
                                        Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "Status");
        body.put("apiVersion", "v1");
        body.put("metadata", Map.of());
        body.put("status", status);
        if (message != null) {
            body.put("message", message);
        }
        if (reason != null) {
            body.put("reason", reason);
        }
        body.put("details", details);
        if (code != 200) {
            body.put("code", code);
        }
        return OAuthResponse.json(code, body);
    }

    /** The redirect URI with {@code params} merged into its query, every key sorted as Go's {@code url.Values}. */
    private static String withParams(String uri, Map<String, String> params) {
        int q = uri.indexOf('?');
        Map<String, String> merged = new TreeMap<>();
        if (q >= 0) {
            OAuthRequest.parseForm(uri.substring(q + 1)).forEach((key, values) -> merged.put(key, values.getFirst()));
        }
        merged.putAll(params);
        return (q < 0 ? uri : uri.substring(0, q)) + "?" + encode(merged);
    }

    private static String withoutQueryAndFragment(String uri) {
        int cut = uri.length();
        for (char c : new char[]{'?', '#'}) {
            int at = uri.indexOf(c);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return uri.substring(0, cut);
    }

    private static Map<String, String> sorted(Map<String, String> params) {
        return new TreeMap<>(params);
    }

    private static String encode(Map<String, String> params) {
        StringBuilder query = new StringBuilder();
        params.forEach((key, value) -> {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(queryEscape(key)).append('=').append(queryEscape(value));
        });
        return query.toString();
    }

    /** Go's {@code url.QueryEscape}: everything but letters, digits, {@code -_.~} escaped, spaces as {@code +}. */
    static String queryEscape(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("%7E", "~").replace("*", "%2A");
    }

    private static String loginPath(String provider) {
        return "/login/" + provider.replace("%", "%25").replace("/", "%2F").replace("?", "%3F").replace(";", "%3B")
                .replace(",", "%2C").replace(" ", "%20");
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String page(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>" + escapeHtml(title) + "</title></head>"
                + "<body>" + body + "</body></html>";
    }
}
