package io.floci.az.services.managedidentity;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.services.entra.FederatedIdentities;
import io.floci.az.services.managedidentity.ManagedIdentityModels.FederatedIdentityCredential;
import io.floci.az.services.managedidentity.ManagedIdentityModels.UserAssignedIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * User-assigned managed identities as federated clients: a token request whose {@code client_id} is an identity's
 * client id exchanges an assertion against its federated identity credentials, and gets a token shaped like the ones
 * IMDS mints for it ({@code oid} = principal id, {@code xms_mirid} = the identity's ARM id).
 */
@ApplicationScoped
public class UserAssignedFederatedIdentities implements FederatedIdentities {

    private final ManagedIdentityStore store;

    @Inject
    public UserAssignedFederatedIdentities(ManagedIdentityStore store) {
        this.store = store;
    }

    @Override
    public Optional<FederatedIdentity> byClientId(String tenantId, String clientId) {
        return store.findByClientId(clientId).map(this::federated);
    }

    private FederatedIdentity federated(UserAssignedIdentity identity) {
        List<FederatedCredential> credentials = ArmResourceId.parse(identity.id())
                .map(store::credentials).orElse(List.of()).stream()
                .map(UserAssignedFederatedIdentities::credential).toList();
        return new FederatedIdentity(identity.clientId(), identity.principalId(), Map.of("xms_mirid", identity.id()),
                credentials);
    }

    private static FederatedCredential credential(FederatedIdentityCredential credential) {
        List<String> audiences = credential.audiences() == null ? List.of()
                : credential.audiences().stream().map(String::valueOf).toList();
        Object expression = credential.claimsMatchingExpression() == null ? null
                : credential.claimsMatchingExpression().get("value");
        return new FederatedCredential(credential.issuer(), credential.subject(), audiences,
                expression == null ? null : String.valueOf(expression));
    }
}
