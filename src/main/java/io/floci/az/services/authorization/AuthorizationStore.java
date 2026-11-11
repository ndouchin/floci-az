package io.floci.az.services.authorization;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Role assignments, kept in the {@code authorization} storage backend under their canonical ARM id, so they follow
 * the global storage mode and compare case-insensitively the way ARM ids do. Custom role definitions share the
 * backend ({@link RoleDefinitions}).
 */
@ApplicationScoped
public class AuthorizationStore {

    static final String STORE = "authorization";
    static final String ASSIGNMENT_TYPE = "Microsoft.Authorization/roleAssignments";

    private final ArmResourceStore<RoleAssignment> assignments;

    @Inject
    public AuthorizationStore(StorageFactory storageFactory) {
        this(ArmResourceStore.create(storageFactory, STORE, ASSIGNMENT_TYPE, RoleAssignment.class,
                RoleAssignment::id, key -> false));
    }

    AuthorizationStore(ArmResourceStore<RoleAssignment> assignments) {
        this.assignments = assignments;
    }

    public Optional<RoleAssignment> assignment(String id) {
        return ArmResourceId.parse(id).flatMap(assignments::get).map(ArmResourceStore.Stored::value);
    }

    /** Every role assignment, at every scope. */
    public List<RoleAssignment> assignments() {
        return assignments.all().stream().map(ArmResourceStore.Stored::value).toList();
    }

    /** Writes {@code assignment}; returns whether it was new. */
    boolean put(RoleAssignment assignment) {
        ArmResourceId id = ArmResourceId.parse(assignment.id()).orElseThrow();
        return assignments.createOrUpdate(id, existing -> assignment).created();
    }

    Optional<RoleAssignment> remove(String id) {
        return ArmResourceId.parse(id).flatMap(assignments::delete).map(ArmResourceStore.Stored::value);
    }

    void clear() {
        assignments.clear();
    }
}
