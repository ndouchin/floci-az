package io.floci.az.services.aro.oauth;

import java.util.List;

/**
 * A user the OAuth server authenticated: the name the cluster sees, the UID that ties a token to the state it was
 * issued in (empty when the provider sets none), the provider that authenticated it, and its groups, without
 * {@code system:authenticated}, which kube-apiserver adds itself.
 */
public record OAuthUser(String name, String uid, String provider, List<String> groups) {

    public OAuthUser {
        uid = uid == null ? "" : uid;
        groups = List.copyOf(groups);
    }
}
