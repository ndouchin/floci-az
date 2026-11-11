package io.floci.az.services.aro;

import io.floci.az.services.aro.oauth.IdentityProvider;
import io.floci.az.services.aro.oauth.OAuthToken;
import io.floci.az.services.aro.oauth.OAuthUser;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code kube:admin} bootstrap user of an OpenShift cluster, checked against Secret {@code kube-system/kubeadmin}
 * as library-go's {@code bootstrapauthenticator} (vendored by {@code oauth-server}) and oauth-apiserver's
 * {@code tokenvalidation/bootstrapauthenticator.go} do it:
 *
 * <ul>
 *   <li>basic-auth name {@code kubeadmin} or {@code kube:admin}; the user is {@code kube:admin}, group
 *       {@code system:cluster-admins};</li>
 *   <li>the secret counts only when present, not being deleted, holding a bcrypt hash, and created at most one hour
 *       after namespace {@code kube-system};</li>
 *   <li>passwords shorter than 23 characters are refused;</li>
 *   <li>the user's UID is the base64url SHA-512 of the secret's UID, resource version and hash, so a token stops
 *       working once the secret changes or goes. The secret is re-read at most every 10 seconds for that check.</li>
 * </ul>
 */
public final class KubeadminAuthenticator implements IdentityProvider {

    private static final Logger LOG = Logger.getLogger(KubeadminAuthenticator.class);

    public static final String BOOTSTRAP_USER = "kube:admin";
    static final String BASIC_AUTH_NAME = "kubeadmin";
    static final int MIN_PASSWORD_LENGTH = 23;
    static final Duration RECHECK_INTERVAL = Duration.ofSeconds(10);
    static final List<String> GROUPS = List.of("system:cluster-admins");
    private static final Set<String> NAMES = Set.of(BOOTSTRAP_USER, BASIC_AUTH_NAME);

    /** What the authenticator reads from the cluster: the secret's hash and metadata, and when kube-system began. */
    public record KubeadminSecret(String passwordHash, String uid, String resourceVersion, Instant created,
                                  boolean deleting, Instant kubeSystemCreated) {
    }

    /** Reads Secret {@code kube-system/kubeadmin}; empty when it does not exist. May block; throws when it cannot. */
    @FunctionalInterface
    public interface SecretReader {
        Optional<KubeadminSecret> read();
    }

    /** The usable secret's hash and the UID it gives the user, or nothing when kubeadmin is disabled. */
    private record State(Optional<Usable> usable, Instant readAt) {
    }

    private record Usable(String passwordHash, String userUid) {
    }

    private final SecretReader reader;
    private final Clock clock;
    private volatile State cached;

    public KubeadminAuthenticator(SecretReader reader, Clock clock) {
        this.reader = reader;
        this.clock = clock;
    }

    @Override
    public String name() {
        return BOOTSTRAP_USER;
    }

    @Override
    public boolean challenge() {
        return true;
    }

    /** Listed on the login page only while the secret is usable, as {@code bootstrapselectprovider} does. */
    @Override
    public boolean login() {
        return state(false).usable().isPresent();
    }

    @Override
    public Optional<OAuthUser> authenticate(String username, String password) {
        if (!NAMES.contains(username)) {
            return Optional.empty();
        }
        Optional<Usable> usable = state(true).usable();
        if (usable.isEmpty() || password.length() < MIN_PASSWORD_LENGTH) {
            return Optional.empty();
        }
        boolean matches;
        try {
            matches = OpenBSDBCrypt.checkPassword(usable.get().passwordHash(), password.toCharArray());
        } catch (IllegalArgumentException e) {
            LOG.debugv("kubeadmin secret holds no usable bcrypt hash: {0}", e.getMessage());
            return Optional.empty();
        }
        return matches ? Optional.of(user(usable.get().userUid())) : Optional.empty();
    }

    @Override
    public Optional<OAuthUser> revalidate(OAuthToken token) {
        if (!BOOTSTRAP_USER.equals(token.userName())) {
            return Optional.empty();
        }
        return state(false).usable()
                .filter(usable -> usable.userUid().equals(token.userUid()))
                .map(usable -> user(usable.userUid()));
    }

    private static OAuthUser user(String uid) {
        return new OAuthUser(BOOTSTRAP_USER, uid, BOOTSTRAP_USER, GROUPS);
    }

    private State state(boolean fresh) {
        State current = cached;
        Instant now = clock.instant();
        if (!fresh && current != null && now.isBefore(current.readAt().plus(RECHECK_INTERVAL))) {
            return current;
        }
        State read = new State(read(), now);
        cached = read;
        return read;
    }

    private Optional<Usable> read() {
        Optional<KubeadminSecret> secret;
        try {
            secret = reader.read();
        } catch (RuntimeException e) {
            LOG.debugv("Cannot read the kubeadmin secret: {0}", e.getMessage());
            return Optional.empty();
        }
        if (secret.isEmpty() || secret.get().deleting() || secret.get().passwordHash() == null
                || secret.get().created().isAfter(secret.get().kubeSystemCreated().plus(Duration.ofHours(1)))) {
            return Optional.empty();
        }
        KubeadminSecret s = secret.get();
        byte[] both = (s.uid() + s.resourceVersion() + s.passwordHash()).getBytes(StandardCharsets.UTF_8);
        return Optional.of(new Usable(s.passwordHash(), Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha512(both))));
    }

    private static byte[] sha512(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-512").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is unavailable", e);
        }
    }
}
