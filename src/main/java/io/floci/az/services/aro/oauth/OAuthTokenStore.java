package io.floci.az.services.aro.oauth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The tokens one cluster's OAuth server issued, keyed by object name. Every change hands a snapshot to
 * {@code onChange}, which persists it with the cluster record, so tokens survive an emulator restart.
 */
public final class OAuthTokenStore {

    private final Map<String, OAuthToken> tokens = new ConcurrentHashMap<>();
    private final Consumer<Map<String, OAuthToken>> onChange;

    public OAuthTokenStore(Map<String, OAuthToken> initial, Consumer<Map<String, OAuthToken>> onChange) {
        if (initial != null) {
            tokens.putAll(initial);
        }
        this.onChange = onChange;
    }

    public static OAuthTokenStore inMemory() {
        return new OAuthTokenStore(Map.of(), snapshot -> { });
    }

    public Optional<OAuthToken> get(String objectName) {
        return Optional.ofNullable(tokens.get(objectName));
    }

    /** Stores a token and drops the ones that expired before {@code now}. */
    public void put(String objectName, OAuthToken token, Instant now) {
        tokens.entrySet().removeIf(entry -> entry.getValue().expiredAt(now));
        tokens.put(objectName, token);
        onChange.accept(snapshot());
    }

    public boolean remove(String objectName) {
        boolean removed = tokens.remove(objectName) != null;
        if (removed) {
            onChange.accept(snapshot());
        }
        return removed;
    }

    public Map<String, OAuthToken> snapshot() {
        return new LinkedHashMap<>(tokens);
    }
}
