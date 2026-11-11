package io.floci.az.services.aro.oauth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The parts of an HTTP request the OAuth server reads. {@code host} is the request's {@code Host}, from which the
 * issuer is derived; {@code path} has no query string.
 */
public record OAuthRequest(String method, String path, String rawQuery, String host,
                           Map<String, List<String>> headers, Map<String, List<String>> query,
                           Map<String, List<String>> form) {

    public OAuthRequest {
        method = method.toUpperCase(Locale.ROOT);
        Map<String, List<String>> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(headers);
        headers = caseInsensitive;
    }

    /** A request from its method, request URI ({@code /path?query}), {@code Host}, headers and body. */
    public static OAuthRequest of(String method, String uri, String host, Map<String, List<String>> headers,
                                  byte[] body) {
        int q = uri.indexOf('?');
        String path = q < 0 ? uri : uri.substring(0, q);
        String rawQuery = q < 0 ? "" : uri.substring(q + 1);
        OAuthRequest probe = new OAuthRequest(method, path, rawQuery, host, headers, Map.of(), Map.of());
        String contentType = probe.header("Content-Type");
        Map<String, List<String>> form = body != null && body.length > 0 && contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")
                ? parseForm(new String(body, StandardCharsets.UTF_8)) : Map.of();
        return new OAuthRequest(method, path, rawQuery, host, headers, parseForm(rawQuery), form);
    }

    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    public String queryParam(String name) {
        return first(query, name);
    }

    public String formParam(String name) {
        return first(form, name);
    }

    /** A form value first, then a query value, as Go's {@code Request.FormValue} reads them. */
    public String param(String name) {
        String value = first(form, name);
        return value != null ? value : first(query, name);
    }

    public boolean hasParam(String name) {
        return form.containsKey(name) || query.containsKey(name);
    }

    public String cookie(String name) {
        List<String> values = headers.get("Cookie");
        if (values == null) {
            return null;
        }
        for (String header : values) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).trim().equals(name)) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    /** The request URI relative to the server: path and query. */
    public String serverRelativeUri() {
        return rawQuery.isEmpty() ? path : path + "?" + rawQuery;
    }

    static Map<String, List<String>> parseForm(String encoded) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (key != null && value != null) {
                values.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            }
        }
        return values;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String first(Map<String, List<String>> values, String name) {
        List<String> list = values.get(name);
        return list == null || list.isEmpty() ? null : list.getFirst();
    }
}
