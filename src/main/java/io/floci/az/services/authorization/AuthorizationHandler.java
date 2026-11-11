package io.floci.az.services.authorization;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmJson;
import io.floci.az.core.auth.BearerClaims;
import io.floci.az.services.authorization.AuthorizationPath.Type;
import io.floci.az.services.authorization.RoleDefinition.Permission;
import io.floci.az.services.entra.DirectoryReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure RBAC's control plane under {@code Microsoft.Authorization}: role definitions (the built-in catalog plus custom
 * roles) and role assignments, at any scope ARM accepts them, including the {@code <clusterId>/namespaces/<name>}
 * scope AKS's Azure RBAC uses. Shapes follow the authorization spec {@code 2022-04-01}; any {@code api-version} is
 * accepted. {@link AccessEvaluator} evaluates the assignments; ARM requests themselves are not authorized.
 */
@ApplicationScoped
public class AuthorizationHandler implements AzureServiceHandler, Resettable {

    static final String SERVICE_TYPE = "authorization";

    private static final Pattern PRINCIPAL_EQ = Pattern.compile("principalId\\s+eq\\s+'([^']*)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGNED_TO = Pattern.compile("assignedTo\\('([^']*)'\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AT_SCOPE = Pattern.compile("atScope\\(\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROLE_NAME_EQ = Pattern.compile("roleName\\s+eq\\s+'([^']*)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_EQ = Pattern.compile("type\\s+eq\\s+'([^']*)'", Pattern.CASE_INSENSITIVE);

    private final EmulatorConfig config;
    private final AuthorizationStore store;
    private final RoleDefinitions roles;
    private final DirectoryReader directory;

    @Inject
    public AuthorizationHandler(EmulatorConfig config, AuthorizationStore store, RoleDefinitions roles,
                                DirectoryReader directory) {
        this.config = config;
        this.store = store;
        this.roles = roles;
        this.directory = directory;
    }

    @Override
    public String getServiceType() {
        return SERVICE_TYPE;
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().authorization().enabled();
    }

    /**
     * Guarded like Azure Policy's route: Microsoft.Authorization also owns policy resources, locks and deny
     * assignments. Only paths whose last {@code Microsoft.Authorization} segment names a role type are claimed.
     */
    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.Authorization", AuthorizationPath::isRolePath)
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        AuthorizationPath path = AuthorizationPath.parse(req.resourcePath());
        if (path == null || path.type() == null || !path.remainder().isEmpty()) {
            return ArmErrors.error(404, "ResourceNotFound", "Unsupported Microsoft.Authorization path: "
                    + req.resourcePath());
        }
        String method = req.method().toUpperCase(Locale.ROOT);
        String filter = req.queryParams() == null ? null : req.queryParams().get("$filter");
        try {
            return path.type() == Type.ROLE_ASSIGNMENT
                    ? assignments(req, path, method, filter)
                    : definitions(req, path, method, filter);
        } catch (ArmJson.InvalidBodyException e) {
            return ArmErrors.error(400, "InvalidRequestContent",
                    "The request content was invalid and could not be deserialized.");
        }
    }

    @Override
    public void clear() {
        store.clear();
        roles.clear();
    }

    // ── Role assignments ────────────────────────────────────────────────────────

    private Response assignments(AzureRequest req, AuthorizationPath path, String method, String filter) {
        if (path.name() == null) {
            return "GET".equals(method) ? listAssignments(req, path, filter) : methodNotAllowed(method);
        }
        return switch (method) {
            case "GET" -> store.assignment(path.resourceId())
                    .map(assignment -> ok(assignmentBody(assignment)))
                    .orElseGet(() -> assignmentNotFound(path.name()));
            case "PUT" -> putAssignment(req, path);
            case "DELETE" -> store.remove(path.resourceId())
                    .map(assignment -> ok(assignmentBody(assignment)))
                    .orElseGet(() -> Response.noContent().build());
            default -> methodNotAllowed(method);
        };
    }

    private Response putAssignment(AzureRequest req, AuthorizationPath path) {
        if (!RoleDefinitions.isGuid(path.name())) {
            return ArmErrors.error(400, "InvalidRoleAssignmentId", "The role assignment ID '" + path.name()
                    + "' is not valid. The role assignment ID must be a GUID.");
        }
        Map<String, Object> properties = ArmJson.cast(ArmJson.parseBodyStrict(req).get("properties"));
        String roleDefinitionId = ArmJson.string(properties, "roleDefinitionId", null);
        String principalId = ArmJson.string(properties, "principalId", null);
        for (String required : List.of("roleDefinitionId", "principalId")) {
            if (ArmJson.string(properties, required, "").isBlank()) {
                return ArmErrors.error(400, "InvalidRequestContent", "The request content was invalid and could not "
                        + "be deserialized: 'Required property '" + required + "' not found in JSON.'");
            }
        }
        if (roles.byId(roleDefinitionId).isEmpty()) {
            return roleDefinitionNotFound(400, RoleDefinitions.guidOf(roleDefinitionId));
        }
        Optional<RoleAssignment> existing = store.assignment(path.resourceId());
        if (existing.isPresent() && !samePrincipalRoleAndScope(existing.get(), principalId, roleDefinitionId,
                path.scope())) {
            return ArmErrors.error(400, "RoleAssignmentUpdateNotPermitted",
                    "Tenant ID, application ID, principal ID, and scope are not allowed to be updated.");
        }
        for (RoleAssignment other : store.assignments()) {
            if (!other.name().equalsIgnoreCase(path.name())
                    && samePrincipalRoleAndScope(other, principalId, roleDefinitionId, path.scope())) {
                return ArmErrors.error(409, "RoleAssignmentExists", "The role assignment already exists.");
            }
        }
        String now = Instant.now().toString();
        String caller = callerObjectId(req);
        RoleAssignment assignment = new RoleAssignment(path.resourceId(), path.name(), path.scope(),
                roleDefinitionId, principalId, ArmJson.string(properties, "principalType", null),
                ArmJson.string(properties, "description", null), ArmJson.string(properties, "condition", null),
                ArmJson.string(properties, "conditionVersion", null),
                ArmJson.string(properties, "delegatedManagedIdentityResourceId", null),
                existing.map(RoleAssignment::createdOn).orElse(now), now,
                existing.map(RoleAssignment::createdBy).orElse(caller), caller);
        store.put(assignment);
        return Response.status(201).entity(assignmentBody(assignment)).build();
    }

    private static boolean samePrincipalRoleAndScope(RoleAssignment assignment, String principalId,
                                                     String roleDefinitionId, String scope) {
        return assignment.principalId().equalsIgnoreCase(principalId)
                && assignment.roleDefinitionName().equalsIgnoreCase(RoleDefinitions.guidOf(roleDefinitionId))
                && AccessEvaluator.normalizeScope(assignment.scope()).equals(AccessEvaluator.normalizeScope(scope));
    }

    /**
     * {@code atScope()} keeps the assignments at or above the scope; without it the list also holds those below.
     * {@code principalId eq} keeps one principal, {@code assignedTo()} that principal and the groups it is a member of.
     */
    private Response listAssignments(AzureRequest req, AuthorizationPath path, String filter) {
        String target = AccessEvaluator.normalizeScope(path.scope());
        boolean atScope = filter != null && AT_SCOPE.matcher(filter).find();
        Set<String> principals = principalsOf(req, filter);
        List<Object> value = new ArrayList<>();
        for (RoleAssignment assignment : store.assignments()) {
            String scope = AccessEvaluator.normalizeScope(assignment.scope());
            boolean above = AccessEvaluator.covers(scope, target);
            boolean below = AccessEvaluator.covers(target, scope);
            if (!(above || (below && !atScope))) {
                continue;
            }
            if (principals != null && !principals.contains(assignment.principalId().toLowerCase(Locale.ROOT))) {
                continue;
            }
            value.add(assignmentBody(assignment));
        }
        return ok(Map.of("value", value));
    }

    private Set<String> principalsOf(AzureRequest req, String filter) {
        if (filter == null) {
            return null;
        }
        Matcher assignedTo = ASSIGNED_TO.matcher(filter);
        if (assignedTo.find()) {
            String principal = assignedTo.group(1);
            Set<String> principals = new HashSet<>();
            principals.add(principal.toLowerCase(Locale.ROOT));
            directory.memberGroupIds(tenantOf(req), principal)
                    .forEach(group -> principals.add(group.toLowerCase(Locale.ROOT)));
            return principals;
        }
        Matcher principalEq = PRINCIPAL_EQ.matcher(filter);
        return principalEq.find() ? Set.of(principalEq.group(1).toLowerCase(Locale.ROOT)) : null;
    }

    private Map<String, Object> assignmentBody(RoleAssignment assignment) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roleDefinitionId", assignment.roleDefinitionId());
        properties.put("principalId", assignment.principalId());
        properties.put("principalType", assignment.principalType());
        properties.put("scope", assignment.scope());
        properties.put("condition", assignment.condition());
        properties.put("conditionVersion", assignment.conditionVersion());
        properties.put("createdOn", assignment.createdOn());
        properties.put("updatedOn", assignment.updatedOn());
        properties.put("createdBy", assignment.createdBy());
        properties.put("updatedBy", assignment.updatedBy());
        properties.put("delegatedManagedIdentityResourceId", assignment.delegatedManagedIdentityResourceId());
        properties.put("description", assignment.description());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("properties", properties);
        body.put("id", assignment.id());
        body.put("type", AuthorizationStore.ASSIGNMENT_TYPE);
        body.put("name", assignment.name());
        return body;
    }

    // ── Role definitions ────────────────────────────────────────────────────────

    private Response definitions(AzureRequest req, AuthorizationPath path, String method, String filter) {
        if (path.name() == null) {
            return "GET".equals(method) ? listDefinitions(path, filter) : methodNotAllowed(method);
        }
        return switch (method) {
            case "GET" -> roles.byGuid(path.name())
                    .map(role -> ok(definitionBody(role, path)))
                    .orElseGet(() -> roleDefinitionNotFound(404, path.name()));
            case "PUT" -> putDefinition(req, path);
            case "DELETE" -> deleteDefinition(path);
            default -> methodNotAllowed(method);
        };
    }

    private Response listDefinitions(AuthorizationPath path, String filter) {
        String roleName = group(ROLE_NAME_EQ, filter);
        String type = group(TYPE_EQ, filter);
        String target = AccessEvaluator.normalizeScope(path.scope());
        List<Object> value = new ArrayList<>();
        for (RoleDefinition role : roles.all()) {
            if (roleName != null && !role.roleName().equalsIgnoreCase(roleName)) {
                continue;
            }
            if (type != null && !role.roleType().equalsIgnoreCase(type)) {
                continue;
            }
            if (!path.scope().isEmpty() && role.assignableScopes().stream()
                    .noneMatch(scope -> AccessEvaluator.covers(AccessEvaluator.normalizeScope(scope), target))) {
                continue;
            }
            value.add(definitionBody(role, path));
        }
        return ok(Map.of("value", value));
    }

    private Response putDefinition(AzureRequest req, AuthorizationPath path) {
        if (!RoleDefinitions.isGuid(path.name())) {
            return ArmErrors.error(400, "InvalidRoleDefinitionId", "The role definition ID '" + path.name()
                    + "' is not valid. The role definition ID must be a GUID.");
        }
        Optional<RoleDefinition> existing = roles.byGuid(path.name());
        if (existing.isPresent() && existing.get().builtIn()) {
            return ArmErrors.error(403, "AuthorizationFailed", "Built-in role definition '" + path.name()
                    + "' cannot be modified.");
        }
        Map<String, Object> properties = ArmJson.cast(ArmJson.parseBodyStrict(req).get("properties"));
        String roleName = ArmJson.string(properties, "roleName", null);
        if (roleName == null || roleName.isBlank()) {
            return ArmErrors.error(400, "InvalidRequestContent", "The request content was invalid and could not be "
                    + "deserialized: 'Required property 'roleName' not found in JSON.'");
        }
        if (roles.customNamed(roleName, path.name()).isPresent()) {
            return ArmErrors.error(409, "RoleDefinitionWithSameNameExists",
                    "A custom role with the same name already exists in this directory. Use a different name.");
        }
        List<String> assignableScopes = strings(properties.get("assignableScopes"));
        if (assignableScopes.isEmpty()) {
            assignableScopes = List.of(path.scope().isEmpty() ? "/" : path.scope());
        }
        String now = Instant.now().toString();
        String caller = callerObjectId(req);
        String scope = existing.map(RoleDefinition::scope).orElse(path.scope());
        RoleDefinition role = new RoleDefinition(path.name(), scope, roleName, RoleDefinition.CUSTOM,
                ArmJson.string(properties, "description", null), permissions(properties.get("permissions")),
                assignableScopes, existing.map(RoleDefinition::createdOn).orElse(now), now,
                existing.map(RoleDefinition::createdBy).orElse(caller), caller);
        roles.putCustom(role);
        return Response.status(201).entity(definitionBody(role, path)).build();
    }

    private Response deleteDefinition(AuthorizationPath path) {
        Optional<RoleDefinition> role = roles.byGuid(path.name());
        if (role.isEmpty()) {
            return Response.noContent().build();
        }
        if (role.get().builtIn()) {
            return ArmErrors.error(403, "AuthorizationFailed", "Built-in role definition '" + path.name()
                    + "' cannot be deleted.");
        }
        boolean assigned = store.assignments().stream()
                .anyMatch(assignment -> assignment.roleDefinitionName().equalsIgnoreCase(path.name()));
        if (assigned) {
            return ArmErrors.error(409, "RoleDefinitionHasAssignments",
                    "There are existing role assignments referencing role.");
        }
        return roles.removeCustom(path.name())
                .map(removed -> ok(definitionBody(removed, path)))
                .orElseGet(() -> Response.noContent().build());
    }

    /**
     * A custom role answers with the id it was created under. A built-in role answers under the subscription the
     * request names, or at the tenant root when it names none, as ARM does.
     */
    private Map<String, Object> definitionBody(RoleDefinition role, AuthorizationPath path) {
        String id = role.builtIn() ? RoleDefinitions.idAt(path.subscriptionScope(), role.name()) : role.id();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roleName", role.roleName());
        properties.put("type", role.roleType());
        properties.put("description", role.description());
        List<Object> permissions = new ArrayList<>();
        for (Permission permission : role.permissions()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("actions", permission.actions());
            block.put("notActions", permission.notActions());
            block.put("dataActions", permission.dataActions());
            block.put("notDataActions", permission.notDataActions());
            permissions.add(block);
        }
        properties.put("permissions", permissions);
        properties.put("assignableScopes", role.assignableScopes());
        properties.put("createdOn", role.createdOn());
        properties.put("updatedOn", role.updatedOn());
        properties.put("createdBy", role.createdBy());
        properties.put("updatedBy", role.updatedBy());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("name", role.name());
        body.put("type", RoleDefinitions.TYPE);
        body.put("properties", properties);
        return body;
    }

    private static List<Permission> permissions(Object value) {
        List<Permission> permissions = new ArrayList<>();
        if (value instanceof List<?> blocks) {
            for (Object block : blocks) {
                Map<String, Object> map = ArmJson.cast(block);
                permissions.add(new Permission(strings(map.get("actions")), strings(map.get("notActions")),
                        strings(map.get("dataActions")), strings(map.get("notDataActions"))));
            }
        }
        return permissions;
    }

    private static List<String> strings(Object value) {
        List<String> strings = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String text) {
                    strings.add(text);
                }
            }
        }
        return strings;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private String tenantOf(AzureRequest req) {
        return req.scope() != null && req.scope().tenantId() != null
                ? req.scope().tenantId() : config.services().entra().defaultTenantId();
    }

    private static String callerObjectId(AzureRequest req) {
        BearerClaims claims = BearerClaims.of(req);
        return claims.present() ? claims.objectId() : null;
    }

    private static String group(Pattern pattern, String filter) {
        if (filter == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(filter);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }

    private static Response assignmentNotFound(String name) {
        return ArmErrors.error(404, "RoleAssignmentNotFound", "The role assignment '" + name + "' is not found.");
    }

    private static Response roleDefinitionNotFound(int status, String guid) {
        return ArmErrors.error(status, "RoleDefinitionDoesNotExist",
                "The specified role definition with ID '" + guid + "' does not exist.");
    }

    private static Response methodNotAllowed(String method) {
        return ArmErrors.error(405, "MethodNotAllowed", "Method not allowed: " + method);
    }
}
