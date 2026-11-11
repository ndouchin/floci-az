package io.floci.az.services.authorization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * One Azure role definition: a built-in role from the catalog or a custom role a client created.
 *
 * @param name             the role definition GUID
 * @param scope            where a custom role was created ({@code /subscriptions/{sub}}); {@code ""} for a built-in role
 * @param roleName         the display name clients look roles up by ({@code roleName eq '...'})
 * @param roleType         {@code BuiltInRole} or {@code CustomRole}
 * @param description      the description, or null
 * @param permissions      the permission blocks
 * @param assignableScopes where the role may be assigned
 * @param createdOn        creation time (ISO 8601), or null for a built-in role
 * @param updatedOn        last update time (ISO 8601), or null for a built-in role
 * @param createdBy        object id of the creator, or null
 * @param updatedBy        object id of the last updater, or null
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoleDefinition(String name, String scope, String roleName, String roleType, String description,
                             List<Permission> permissions, List<String> assignableScopes, String createdOn,
                             String updatedOn, String createdBy, String updatedBy) {

    public static final String BUILT_IN = "BuiltInRole";
    public static final String CUSTOM = "CustomRole";

    public RoleDefinition {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        assignableScopes = assignableScopes == null ? List.of() : List.copyOf(assignableScopes);
        scope = scope == null ? "" : scope;
    }

    /**
     * One permission block. Wildcards ({@code *}) match any characters, and the not-lists are exclusions of this block,
     * not deny rules ({@code role-definitions.md}).
     */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Permission(List<String> actions, List<String> notActions, List<String> dataActions,
                             List<String> notDataActions) {

        public Permission {
            actions = actions == null ? List.of() : List.copyOf(actions);
            notActions = notActions == null ? List.of() : List.copyOf(notActions);
            dataActions = dataActions == null ? List.of() : List.copyOf(dataActions);
            notDataActions = notDataActions == null ? List.of() : List.copyOf(notDataActions);
        }
    }

    public boolean builtIn() {
        return BUILT_IN.equals(roleType);
    }

    /** The id the role is stored under: {@code {scope}/providers/Microsoft.Authorization/roleDefinitions/{name}}. */
    public String id() {
        return RoleDefinitions.idAt(scope, name);
    }
}
