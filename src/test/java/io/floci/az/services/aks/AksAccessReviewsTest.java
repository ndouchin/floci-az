package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A SubjectAccessReview becomes the Azure data action and scope AKS's authorization webhook (kubeguard/guard,
 * {@code authz/providers/azure/rbac/checkaccessreqhelper.go} at v0.16.28) checks.
 */
class AksAccessReviewsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CLUSTER =
            "/subscriptions/s1/resourceGroups/rg/providers/Microsoft.ContainerService/managedClusters/aks1";
    private static final String MC = "Microsoft.ContainerService/managedClusters/";

    @ParameterizedTest(name = "{0} {1} {2}/{3} in [{4}] -> {5}")
    @CsvSource({
            "apps, deployments, , list, default, apps/deployments/read",
            "'', pods, exec, create, default, pods/exec/action",
            "'', pods, log, get, default, pods/read",
            "'', pods, , watch, default, pods/read",
            "'', secrets, , create, kube-system, secrets/write",
            "'', secrets, , patch, kube-system, secrets/write",
            "'', configmaps, , deletecollection, default, configmaps/delete",
            "rbac.authorization.k8s.io, clusterroles, , bind, '', rbac.authorization.k8s.io/clusterroles/bind/action",
            "rbac.authorization.k8s.io, roles, , escalate, default, rbac.authorization.k8s.io/roles/escalate/action",
            "'', users, , impersonate, '', users/impersonate/action",
            "'', namespaces, , delete, '', namespaces/delete",
            "apps, deployments, scale, update, default, apps/deployments/write",
    })
    void resourceRequestsMapToGuardsDataActions(String group, String resource, String subresource, String verb,
                                                String namespace, String action) throws Exception {
        JsonNode spec = MAPPER.readTree("""
                {"resourceAttributes": {"group": "%s", "resource": "%s", "subresource": "%s", "verb": "%s",
                                        "namespace": "%s"}, "user": "alice", "extra": {"oid": ["x"]}}"""
                .formatted(group, resource, subresource == null ? "" : subresource, verb, namespace));
        AksAccessReviews.AzureCheck check = AksAccessReviews.check(CLUSTER, spec);
        assertEquals(MC + action, check.dataAction());
        assertEquals(namespace.isEmpty() ? CLUSTER : CLUSTER + "/namespaces/" + namespace, check.scope());
    }

    @ParameterizedTest(name = "{1} {0} -> {2}")
    @CsvSource({
            "/healthz, get, healthz/read",
            "/apis, get, apis/read",
            "/metrics, get, metrics/read",
            "/version/, get, version/read",
    })
    void nonResourceRequestsMapToThePath(String path, String verb, String action) throws Exception {
        JsonNode spec = MAPPER.readTree("""
                {"nonResourceAttributes": {"path": "%s", "verb": "%s"}, "user": "alice"}""".formatted(path, verb));
        AksAccessReviews.AzureCheck check = AksAccessReviews.check(CLUSTER, spec);
        assertEquals(MC + action, check.dataAction());
        assertEquals(CLUSTER, check.scope());
    }
}
