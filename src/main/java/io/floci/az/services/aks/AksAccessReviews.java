package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a Kubernetes SubjectAccessReview into the Azure RBAC check AKS's authorization webhook makes, as kubeguard/guard
 * does it ({@code authz/providers/azure/rbac/checkaccessreqhelper.go}, v0.16.28):
 *
 * <ul>
 *   <li>data action {@code Microsoft.ContainerService/managedClusters/[{group}/]{resource}/{action}}, where the verb
 *       maps to {@code read} (get, list, watch), {@code write} (create, update, patch), {@code delete} (delete,
 *       deletecollection) or {@code {verb}/action} (bind, escalate, use, impersonate); {@code pods/exec} is
 *       {@code pods/exec/action} and every other subresource takes its resource's action</li>
 *   <li>a non-resource request maps to {@code Microsoft.ContainerService/managedClusters/{path}/{action}}</li>
 *   <li>scope {@code <clusterId>/namespaces/<namespace>} for a namespaced request, else the cluster id</li>
 * </ul>
 *
 * <p>guard expands wildcard groups, resources and verbs from the operations list Azure publishes; this mapper keeps
 * the {@code *} in the data action instead, so only a role whose data actions match it by wildcard (such as
 * {@code Microsoft.ContainerService/managedClusters/*}) grants such a request.
 */
final class AksAccessReviews {

    static final String CLUSTER_TYPE = "Microsoft.ContainerService/managedClusters";

    /** The check: the data action and the scope it is evaluated at. */
    record AzureCheck(String dataAction, String scope) {
    }

    private AksAccessReviews() {
    }

    /** The check for the {@code spec} of a SubjectAccessReview against the cluster {@code clusterId}. */
    static AzureCheck check(String clusterId, JsonNode spec) {
        JsonNode resource = spec.path("resourceAttributes");
        if (resource.isObject()) {
            String group = resource.path("group").asText("");
            String action = resourceAndAction(resource.path("resource").asText(""),
                    resource.path("subresource").asText(""), resource.path("verb").asText(""));
            String dataAction = join(CLUSTER_TYPE, group, action);
            String namespace = resource.path("namespace").asText("");
            return new AzureCheck(dataAction, namespace.isEmpty() ? clusterId : clusterId + "/namespaces/" + namespace);
        }
        JsonNode nonResource = spec.path("nonResourceAttributes");
        return new AzureCheck(join(CLUSTER_TYPE, nonResource.path("path").asText(""),
                actionName(nonResource.path("verb").asText(""))), clusterId);
    }

    private static String resourceAndAction(String resource, String subresource, String verb) {
        if ("pods".equals(resource) && "exec".equals(subresource)) {
            return join(resource, subresource, "action");
        }
        return join(resource, actionName(verb));
    }

    static String actionName(String verb) {
        return switch (verb) {
            case "get", "list", "watch" -> "read";
            case "bind", "escalate", "use", "impersonate" -> verb + "/action";
            case "create", "patch", "update" -> "write";
            case "delete", "deletecollection" -> "delete";
            case "*" -> "*";
            default -> "";
        };
    }

    /** Joins path elements as Go's {@code path.Join} does: empty elements and repeated or trailing slashes drop out. */
    private static String join(String... elements) {
        List<String> parts = new ArrayList<>();
        for (String element : elements) {
            for (String part : element.split("/")) {
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
        }
        return String.join("/", parts);
    }
}
