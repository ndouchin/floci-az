package io.floci.az.services.authorization;

import io.floci.az.services.authorization.RoleDefinition.Permission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Azure RBAC evaluation over the built-in AKS roles as the MicrosoftDocs built-in role pages define them
 * ({@code built-in-roles/containers.md}): wildcards, NotDataActions per role, the union across roles and scope
 * inheritance.
 */
class AccessEvaluatorTest {

    private static final Map<String, RoleDefinition> BUILT_INS = RoleDefinitions.builtInCatalog();
    private static final String SUB = "/subscriptions/00000000-0000-0000-0000-0000000000a1";
    private static final String RG = SUB + "/resourceGroups/rg";
    private static final String CLUSTER = RG + "/providers/Microsoft.ContainerService/managedClusters/aks1";
    private static final String NS1 = CLUSTER + "/namespaces/ns1";
    private static final String NS2 = CLUSTER + "/namespaces/ns2";
    private static final String MC = "Microsoft.ContainerService/managedClusters/";
    private static final String USER = "5d1c3a2b-1111-4222-8333-944455556666";
    private static final String CUSTOM = "cccccccc-0000-4000-8000-000000000001";

    private static final Map<String, String> ROLES = Map.of(
            "READER", "7f6c6a51-bcf8-42ba-9220-52d62157d7db",
            "WRITER", "a7ffa36f-339b-4b5c-8bdf-e2c188b2c0eb",
            "ADMIN", "3498e952-d568-435e-9b2c-8d77e338d7f7",
            "CLUSTER_ADMIN", "b1ff04bb-8a4e-4dc4-8eb5-8693973ce19b",
            "CONTRIBUTOR", "b24988ac-6180-42a0-ab88-20f7382dd24c",
            "NAMESPACE_WRITER", CUSTOM);

    private static final Map<String, String> SCOPES = Map.of("SUB", SUB, "RG", RG, "CLUSTER", CLUSTER,
            "NS1", NS1, "NS2", NS2);

    private static Optional<RoleDefinition> role(String roleDefinitionId) {
        String guid = RoleDefinitions.guidOf(roleDefinitionId);
        if (CUSTOM.equals(guid)) {
            return Optional.of(new RoleDefinition(CUSTOM, SUB, "Namespace writer", RoleDefinition.CUSTOM, null,
                    List.of(new Permission(List.of(), List.of(), List.of(MC + "namespaces/write"), List.of())),
                    List.of(SUB), null, null, null, null));
        }
        return Optional.ofNullable(BUILT_INS.get(guid));
    }

    private static RoleAssignment assignment(String scope, String role, String principal) {
        String name = UUID.randomUUID().toString();
        return new RoleAssignment(scope + "/providers/Microsoft.Authorization/roleAssignments/" + name, name, scope,
                SUB + "/providers/Microsoft.Authorization/roleDefinitions/" + role, principal, "User", null, null,
                null, null, null, null, null, null);
    }

    /** Each row: roles held as {@code ROLE@SCOPE} separated by {@code +}, the data action, the scope checked, the answer. */
    @ParameterizedTest(name = "{0}: {1} at {2} -> {3}")
    @CsvSource({
            "READER@NS1, pods/read, NS1, true",
            "READER@NS1, pods/read, NS2, false",
            "READER@NS1, secrets/read, NS1, false",
            "READER@NS1, apps/deployments/read, NS1, true",
            "READER@NS1, pods/exec/action, NS1, false",
            "WRITER@CLUSTER, apps/deployments/write, NS1, true",
            "WRITER@CLUSTER, secrets/read, NS2, true",
            "WRITER@CLUSTER, rbac.authorization.k8s.io/roles/read, NS1, false",
            "ADMIN@CLUSTER, namespaces/write, CLUSTER, false",
            "ADMIN@CLUSTER, rbac.authorization.k8s.io/roles/write, NS1, true",
            "ADMIN@CLUSTER+NAMESPACE_WRITER@SUB, namespaces/write, CLUSTER, true",
            "CLUSTER_ADMIN@RG, namespaces/delete, CLUSTER, true",
            "CLUSTER_ADMIN@RG, pods/exec/action, NS1, true",
            "CLUSTER_ADMIN@NS1, namespaces/delete, CLUSTER, false",
            "CONTRIBUTOR@SUB, pods/read, NS1, false",
    })
    void dataActionsFollowTheBuiltInRoles(String held, String action, String scope, boolean expected) {
        List<RoleAssignment> assignments = new ArrayList<>();
        for (String grant : held.split("\\+")) {
            String[] parts = grant.split("@");
            assignments.add(assignment(SCOPES.get(parts[1]), ROLES.get(parts[0]), USER));
        }
        AccessEvaluator.Decision decision = AccessEvaluator.evaluate(assignments, AccessEvaluatorTest::role,
                Set.of(USER), SCOPES.get(scope), MC + action, true);
        assertEquals(expected, decision.allowed());
    }

    @Test
    void onlyTheNamedPrincipalsAreConsidered() {
        List<RoleAssignment> assignments = List.of(assignment(CLUSTER, ROLES.get("CLUSTER_ADMIN"), USER));
        assertFalse(AccessEvaluator.evaluate(assignments, AccessEvaluatorTest::role,
                Set.of("aaaaaaaa-0000-4000-8000-000000000009"), NS1, MC + "pods/read", true).allowed());
        AccessEvaluator.Decision viaGroup = AccessEvaluator.evaluate(assignments, AccessEvaluatorTest::role,
                Set.of("aaaaaaaa-0000-4000-8000-000000000009", USER.toUpperCase()), NS1, MC + "pods/read", true);
        assertTrue(viaGroup.allowed());
        assertEquals(assignments.getFirst(), viaGroup.assignment());
    }

    @Test
    void controlPlaneActionsReadTheActionsLists() {
        List<RoleAssignment> assignments = List.of(assignment(RG, ROLES.get("READER"), USER));
        assertTrue(AccessEvaluator.evaluate(assignments, AccessEvaluatorTest::role, Set.of(USER), CLUSTER,
                "Microsoft.Authorization/roleAssignments/read", false).allowed());
        assertFalse(AccessEvaluator.evaluate(assignments, AccessEvaluatorTest::role, Set.of(USER), CLUSTER,
                MC + "listClusterUserCredential/action", false).allowed());
    }

    @Test
    void wildcardsMatchAnyCharactersCaseInsensitively() {
        assertTrue(AccessEvaluator.matches("Microsoft.ContainerService/managedClusters/*", MC + "apps/deployments/read"));
        assertTrue(AccessEvaluator.matches("*/read", "Microsoft.Storage/storageAccounts/read"));
        assertTrue(AccessEvaluator.matches("microsoft.containerservice/managedclusters/pods/*", MC + "pods/exec/action"));
        assertFalse(AccessEvaluator.matches(MC + "pods/read", MC + "pods/write"));
        assertFalse(AccessEvaluator.matches("*/read", MC + "pods/write"));
    }
}
