package io.floci.az.services.aro.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the OAuth server answers: a status, headers in order and a body. */
public record OAuthResponse(int status, Map<String, List<String>> headers, byte[] body) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String header(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    static Builder status(int status) {
        return new Builder(status);
    }

    static OAuthResponse json(int status, Object value) {
        try {
            return status(status).header("Content-Type", "application/json")
                    .body(MAPPER.writeValueAsBytes(value)).build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + value, e);
        }
    }

    static OAuthResponse redirect(String location) {
        return status(302).header("Location", location).build();
    }

    static OAuthResponse html(int status, String page) {
        return status(status).header("Content-Type", "text/html; charset=UTF-8")
                .body(page.getBytes(StandardCharsets.UTF_8)).build();
    }

    static final class Builder {
        private final int status;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private byte[] body = new byte[0];

        private Builder(int status) {
            this.status = status;
        }

        Builder header(String name, String value) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            return this;
        }

        Builder body(byte[] body) {
            this.body = body;
            return this;
        }

        OAuthResponse build() {
            return new OAuthResponse(status, headers, body);
        }
    }
}
