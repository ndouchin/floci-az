package io.floci.az.services.authorization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Every role definition the emulator knows: the built-in roles of {@code azure/built-in-roles.json} (copied verbatim
 * from the MicrosoftDocs built-in role pages by {@code tools/authorization/gen_builtin_roles.py}) and the custom roles
 * clients create. A role definition GUID is unique in the tenant, so lookups go by GUID whatever scope the id names.
 */
@ApplicationScoped
public class RoleDefinitions {

    static final String RESOURCE = "azure/built-in-roles.json";
    static final String TYPE = "Microsoft.Authorization/roleDefinitions";
    private static final String SEGMENT = "/providers/Microsoft.Authorization/roleDefinitions/";
    private static final Pattern GUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final Map<String, RoleDefinition> builtIns;
    private final ArmResourceStore<RoleDefinition> custom;

    @Inject
    public RoleDefinitions(StorageFactory storageFactory) {
        this(builtInCatalog(), ArmResourceStore.create(storageFactory, AuthorizationStore.STORE, TYPE,
                RoleDefinition.class, RoleDefinition::id, key -> false));
    }

    RoleDefinitions(Map<String, RoleDefinition> builtIns, ArmResourceStore<RoleDefinition> custom) {
        this.builtIns = builtIns;
        this.custom = custom;
    }

    /** The built-in catalog, keyed by lowercase GUID. */
    public static Map<String, RoleDefinition> builtInCatalog() {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream in = RoleDefinitions.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            }
            Map<String, RoleDefinition> catalog = new LinkedHashMap<>();
            for (JsonNode role : mapper.readTree(in).path("roleDefinitions")) {
                List<RoleDefinition.Permission> permissions = new ArrayList<>();
                for (JsonNode permission : role.path("permissions")) {
                    permissions.add(mapper.treeToValue(permission, RoleDefinition.Permission.class));
                }
                List<String> scopes = new ArrayList<>();
                role.path("assignableScopes").forEach(scope -> scopes.add(scope.asText()));
                RoleDefinition definition = new RoleDefinition(role.path("name").asText(), "",
                        role.path("roleName").asText(), role.path("roleType").asText(),
                        role.path("description").asText(null), permissions, scopes, null, null, null, null);
                catalog.put(definition.name().toLowerCase(Locale.ROOT), definition);
            }
            return Collections.unmodifiableMap(catalog);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }

    /** {@code {scope}/providers/Microsoft.Authorization/roleDefinitions/{guid}}; {@code scope} is "" at the tenant root. */
    static String idAt(String scope, String guid) {
        return (scope == null ? "" : scope) + SEGMENT + guid;
    }

    /** The GUID a role definition id ends with, or the value itself when it has no slash. */
    static String guidOf(String roleDefinitionId) {
        if (roleDefinitionId == null) {
            return null;
        }
        String trimmed = roleDefinitionId.endsWith("/")
                ? roleDefinitionId.substring(0, roleDefinitionId.length() - 1) : roleDefinitionId;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    static boolean isGuid(String value) {
        return value != null && GUID.matcher(value).matches();
    }

    /** The role definition with {@code guid}, built-in or custom. */
    public Optional<RoleDefinition> byGuid(String guid) {
        if (guid == null) {
            return Optional.empty();
        }
        RoleDefinition builtIn = builtIns.get(guid.toLowerCase(Locale.ROOT));
        if (builtIn != null) {
            return Optional.of(builtIn);
        }
        return custom.all().stream().map(ArmResourceStore.Stored::value)
                .filter(role -> role.name().equalsIgnoreCase(guid)).findFirst();
    }

    /** The role definition an assignment's {@code roleDefinitionId} names. */
    public Optional<RoleDefinition> byId(String roleDefinitionId) {
        return byGuid(guidOf(roleDefinitionId));
    }

    /** Built-in roles, then custom roles. */
    public List<RoleDefinition> all() {
        List<RoleDefinition> roles = new ArrayList<>(builtIns.values());
        custom.all().forEach(stored -> roles.add(stored.value()));
        return roles;
    }

    /** The custom role named {@code roleName}, case-insensitively, other than {@code exceptGuid}. */
    Optional<RoleDefinition> customNamed(String roleName, String exceptGuid) {
        return custom.all().stream().map(ArmResourceStore.Stored::value)
                .filter(role -> role.roleName().equalsIgnoreCase(roleName) && !role.name().equalsIgnoreCase(exceptGuid))
                .findFirst();
    }

    /** Writes a custom role; returns whether it was new. */
    boolean putCustom(RoleDefinition role) {
        ArmResourceId id = ArmResourceId.parse(role.id()).orElseThrow();
        return custom.createOrUpdate(id, existing -> role).created();
    }

    /** Removes the custom role with {@code guid}; empty when there is none. */
    Optional<RoleDefinition> removeCustom(String guid) {
        Optional<RoleDefinition> role = byGuid(guid).filter(found -> !found.builtIn());
        role.flatMap(found -> ArmResourceId.parse(found.id())).ifPresent(custom::delete);
        return role;
    }

    void clear() {
        custom.clear();
    }
}
