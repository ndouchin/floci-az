package io.floci.az.services.aro.oauth;

import java.util.Optional;

/**
 * Where the OAuth server checks a user name and password: the {@code kube:admin} bootstrap user on ARO, htpasswd
 * identity providers on ROSA. Calls may block (they read the cluster), so the server is driven from a worker thread.
 */
public interface IdentityProvider {

    /** The provider's name, as the login page lists it ({@code kube:admin} for the bootstrap user). */
    String name();

    /** Whether {@code oc login -u} can use it: answers basic-auth challenges of the challenging client. */
    boolean challenge();

    /** Whether the browser login page lists it. */
    boolean login();

    /** The user {@code username} and {@code password} authenticate, or empty when they do not. */
    Optional<OAuthUser> authenticate(String username, String password);

    /**
     * The user a token this provider issued stands for now, with its current groups, or empty when the token must
     * stop working (the user or the secret it was issued against is gone).
     */
    Optional<OAuthUser> revalidate(OAuthToken token);
}
