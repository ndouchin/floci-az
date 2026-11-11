package io.floci.az.services.aro.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Token and code formats of the OpenShift OAuth server ({@code oauth-server} {@code pkg/osinserver/tokengen.go},
 * {@code pkg/osinserver/registrystorage/storage.go}): {@code sha256~} and 256 random bits in unpadded base64url, never
 * starting with a dash; stored under {@code sha256~} and the base64url SHA-256 of the part after the prefix.
 */
public final class OAuthTokens {

    public static final String PREFIX = "sha256~";

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private OAuthTokens() {
    }

    public static String newToken(SecureRandom random) {
        byte[] bytes = new byte[32];
        while (true) {
            random.nextBytes(bytes);
            String value = B64URL.encodeToString(bytes);
            if (!value.startsWith("-")) {
                return PREFIX + value;
            }
        }
    }

    public static String objectName(String token) {
        String raw = token.startsWith(PREFIX) ? token.substring(PREFIX.length()) : token;
        return PREFIX + B64URL.encodeToString(sha256(raw.getBytes(StandardCharsets.UTF_8)));
    }

    static String s256(String verifier) {
        return B64URL.encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
