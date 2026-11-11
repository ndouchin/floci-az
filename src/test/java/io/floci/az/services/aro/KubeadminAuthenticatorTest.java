package io.floci.az.services.aro;

import io.floci.az.services.aro.KubeadminAuthenticator.KubeadminSecret;
import io.floci.az.services.aro.oauth.OAuthToken;
import io.floci.az.services.aro.oauth.OAuthUser;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class KubeadminAuthenticatorTest {

    private static final String PASSWORD = "abcde-fghij-kmnop-qrstu";
    private static final Instant KUBE_SYSTEM = Instant.parse("2026-11-11T09:00:00Z");
    /** Version 2a, cost 10: the shape Go's {@code bcrypt.GenerateFromPassword} gives the installer. */
    private static final String HASH = OpenBSDBCrypt.generate("2a", PASSWORD.toCharArray(), new byte[16], 10);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:00:00Z"));
    private final AtomicInteger reads = new AtomicInteger();
    private Optional<KubeadminSecret> secret = Optional.of(secret(HASH, "rv1", KUBE_SYSTEM.plusSeconds(30)));
    private final KubeadminAuthenticator authenticator = new KubeadminAuthenticator(() -> {
        reads.incrementAndGet();
        return secret;
    }, clock);

    @Test
    void theRightPasswordAuthenticatesKubeAdminTiedToTheSecret() throws Exception {
        for (String name : List.of("kubeadmin", "kube:admin")) {
            OAuthUser user = authenticator.authenticate(name, PASSWORD).orElseThrow();
            assertEquals("kube:admin", user.name());
            assertEquals("kube:admin", user.provider());
            assertEquals(List.of("system:cluster-admins"), user.groups());
            byte[] uid = MessageDigest.getInstance("SHA-512").digest(("secret-uid" + "rv1" + HASH)
                    .getBytes(StandardCharsets.UTF_8));
            assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(uid), user.uid());
        }
    }

    @Test
    void aWrongPasswordAnotherUserOrAShortPasswordDoNotAuthenticate() {
        assertTrue(authenticator.authenticate("kubeadmin", "abcde-fghij-kmnop-qrstv").isEmpty());
        assertTrue(authenticator.authenticate("admin", PASSWORD).isEmpty());
        secret = Optional.of(secret(OpenBSDBCrypt.generate("2a", "short".toCharArray(), new byte[16], 4), "rv1",
                KUBE_SYSTEM));
        assertTrue(authenticator.authenticate("kubeadmin", "short").isEmpty());
    }

    @Test
    void anAbsentDeletingOrRecreatedSecretDisablesKubeadmin() {
        secret = Optional.empty();
        assertTrue(authenticator.authenticate("kubeadmin", PASSWORD).isEmpty());
        secret = Optional.of(new KubeadminSecret(HASH, "secret-uid", "rv1", KUBE_SYSTEM, true, KUBE_SYSTEM));
        assertTrue(authenticator.authenticate("kubeadmin", PASSWORD).isEmpty());
        secret = Optional.of(secret(HASH, "rv1", KUBE_SYSTEM.plus(Duration.ofHours(1)).plusSeconds(1)));
        assertTrue(authenticator.authenticate("kubeadmin", PASSWORD).isEmpty());
        assertFalse(authenticator.login());
        secret = Optional.of(secret(HASH, "rv1", KUBE_SYSTEM.plus(Duration.ofHours(1))));
        clock.advance(Duration.ofSeconds(11));
        assertTrue(authenticator.authenticate("kubeadmin", PASSWORD).isPresent());
        assertTrue(authenticator.login());
    }

    @Test
    void aMalformedHashOrAFailedReadDoNotAuthenticate() {
        secret = Optional.of(secret("not-bcrypt", "rv1", KUBE_SYSTEM));
        assertTrue(authenticator.authenticate("kubeadmin", PASSWORD).isEmpty());
        KubeadminAuthenticator failing = new KubeadminAuthenticator(() -> {
            throw new IllegalStateException("API down");
        }, clock);
        assertTrue(failing.authenticate("kubeadmin", PASSWORD).isEmpty());
    }

    @Test
    void tokensStopWorkingWithinTenSecondsOfTheSecretChangingOrGoing() {
        OAuthUser user = authenticator.authenticate("kubeadmin", PASSWORD).orElseThrow();
        OAuthToken token = new OAuthToken(user.name(), user.uid(), user.provider(), "openshift-challenging-client",
                List.of("user:full"), clock.instant(), 86400);
        assertTrue(authenticator.revalidate(token).isPresent());

        int before = reads.get();
        secret = Optional.empty();
        clock.advance(Duration.ofSeconds(5));
        assertTrue(authenticator.revalidate(token).isPresent(), "re-checked at most every 10 s");
        assertEquals(before, reads.get());
        clock.advance(Duration.ofSeconds(6));
        assertTrue(authenticator.revalidate(token).isEmpty());

        secret = Optional.of(secret(HASH, "rv2", KUBE_SYSTEM.plusSeconds(30)));
        clock.advance(Duration.ofSeconds(11));
        assertTrue(authenticator.revalidate(token).isEmpty(), "a token is tied to the secret it was issued against");
        OAuthUser again = authenticator.authenticate("kubeadmin", PASSWORD).orElseThrow();
        assertNotEquals(user.uid(), again.uid());
    }

    private static KubeadminSecret secret(String hash, String resourceVersion, Instant created) {
        return new KubeadminSecret(hash, "secret-uid", resourceVersion, created, false, KUBE_SYSTEM);
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
