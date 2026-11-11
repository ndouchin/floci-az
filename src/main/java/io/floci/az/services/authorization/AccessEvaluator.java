package io.floci.az.services.authorization;

import io.floci.az.services.authorization.RoleDefinition.Permission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides whether a set of principals holds an action at a scope, the way Azure RBAC evaluates role assignments
 * ({@code role-definitions.md}): an assignment applies at its scope and every scope below it, a wildcard matches any
 * characters, a permission block grants what its actions match minus what its not-actions match, and the result is
 * the union over every applicable assignment, so one role's not-actions never take away what another role grants.
 *
 * <p>Not evaluated: ABAC conditions (stored on the assignment, ignored), deny assignments, and management-group
 * scopes above a subscription.
 */
@ApplicationScoped
public class AccessEvaluator {

    /** The answer: whether access is granted and, when it is, the assignment and role that grant it. */
    public record Decision(boolean allowed, RoleAssignment assignment, RoleDefinition role) {

        static final Decision DENIED = new Decision(false, null, null);
    }

    private final AuthorizationStore store;
    private final RoleDefinitions roles;

    @Inject
    public AccessEvaluator(AuthorizationStore store, RoleDefinitions roles) {
        this.store = store;
        this.roles = roles;
    }

    /** Whether {@code principalIds} hold the data action {@code dataAction} at {@code scope}. */
    public Decision checkDataAction(Collection<String> principalIds, String scope, String dataAction) {
        return evaluate(store.assignments(), roles::byId, principalIds, scope, dataAction, true);
    }

    /** Whether {@code principalIds} hold the control-plane action {@code action} at {@code scope}. */
    public Decision checkAction(Collection<String> principalIds, String scope, String action) {
        return evaluate(store.assignments(), roles::byId, principalIds, scope, action, false);
    }

    static Decision evaluate(Collection<RoleAssignment> assignments,
                             Function<String, Optional<RoleDefinition>> roleOf, Collection<String> principalIds,
                             String scope, String action, boolean dataAction) {
        Set<String> principals = principalIds.stream().filter(id -> id != null && !id.isBlank())
                .map(id -> id.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        String target = normalizeScope(scope);
        for (RoleAssignment assignment : assignments) {
            if (assignment.principalId() == null
                    || !principals.contains(assignment.principalId().toLowerCase(Locale.ROOT))
                    || !covers(normalizeScope(assignment.scope()), target)) {
                continue;
            }
            Optional<RoleDefinition> role = roleOf.apply(assignment.roleDefinitionId());
            if (role.isPresent() && grants(role.get(), action, dataAction)) {
                return new Decision(true, assignment, role.get());
            }
        }
        return Decision.DENIED;
    }

    /** Whether one role grants {@code action}: some permission block matches it and does not exclude it. */
    static boolean grants(RoleDefinition role, String action, boolean dataAction) {
        for (Permission permission : role.permissions()) {
            List<String> allowed = dataAction ? permission.dataActions() : permission.actions();
            List<String> excluded = dataAction ? permission.notDataActions() : permission.notActions();
            if (allowed.stream().anyMatch(pattern -> matches(pattern, action))
                    && excluded.stream().noneMatch(pattern -> matches(pattern, action))) {
                return true;
            }
        }
        return false;
    }

    /** Case-insensitive glob match where {@code *} matches any run of characters, slashes included. */
    static boolean matches(String pattern, String action) {
        String p = pattern.toLowerCase(Locale.ROOT);
        String a = action.toLowerCase(Locale.ROOT);
        int pi = 0;
        int ai = 0;
        int star = -1;
        int mark = 0;
        while (ai < a.length()) {
            if (pi < p.length() && p.charAt(pi) == '*') {
                star = pi++;
                mark = ai;
            } else if (pi < p.length() && p.charAt(pi) == a.charAt(ai)) {
                pi++;
                ai++;
            } else if (star >= 0) {
                pi = star + 1;
                ai = ++mark;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') {
            pi++;
        }
        return pi == p.length();
    }

    /** Whether an assignment at {@code ancestor} applies at {@code scope}: the same scope or one below it. */
    static boolean covers(String ancestor, String scope) {
        return ancestor.equals("/") || ancestor.equals(scope) || scope.startsWith(ancestor + "/");
    }

    /** Lowercase, one leading slash, no trailing slash; the root is {@code /}. */
    static String normalizeScope(String scope) {
        String s = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            return "/";
        }
        return s.startsWith("/") ? s : "/" + s;
    }
}
