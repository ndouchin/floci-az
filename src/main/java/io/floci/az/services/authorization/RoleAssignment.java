package io.floci.az.services.authorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One Azure role assignment ({@code Microsoft.Authorization/roleAssignments}, 2022-04-01 shape).
 *
 * @param id                 the ARM id, {@code {scope}/providers/Microsoft.Authorization/roleAssignments/{name}}
 * @param name               the assignment GUID
 * @param scope              the scope it applies at and below, in the caller's casing
 * @param roleDefinitionId   the role definition id as the client sent it
 * @param principalId        the object id of the user, group or service principal
 * @param principalType      {@code User}, {@code Group}, {@code ServicePrincipal}, ... or null when not sent
 * @param description        the description, or null
 * @param condition          an ABAC condition, stored and not evaluated
 * @param conditionVersion   the condition version, or null
 * @param delegatedManagedIdentityResourceId the delegated identity, or null
 * @param createdOn          creation time (ISO 8601)
 * @param updatedOn          last update time (ISO 8601)
 * @param createdBy          object id of the creator, or null
 * @param updatedBy          object id of the last updater, or null
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoleAssignment(String id, String name, String scope, String roleDefinitionId, String principalId,
                             String principalType, String description, String condition, String conditionVersion,
                             String delegatedManagedIdentityResourceId, String createdOn, String updatedOn,
                             String createdBy, String updatedBy) {

    /** The GUID of the role definition: the last segment of {@link #roleDefinitionId}. */
    public String roleDefinitionName() {
        return RoleDefinitions.guidOf(roleDefinitionId);
    }
}
