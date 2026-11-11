package io.floci.az.services.aro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AroConsoleManifestsTest {

    private static final String DOMAIN = "demo.127.0.0.1.nip.io";
    private static final String CONSOLE_URL = "https://console-openshift-console.apps." + DOMAIN + ":7602";
    private static final String API_URL = "https://api." + DOMAIN + ":7700";
    private static final String IMAGE = AroConsoleManifests.DEFAULT_IMAGE;
    private static final String CA = "-----BEGIN CERTIFICATE-----\nCA\n-----END CERTIFICATE-----\n";

    private static ObjectNode render() {
        return AroConsoleManifests.render(new AroConsoleManifests.Settings(DOMAIN, CONSOLE_URL, API_URL, "10.1.2.3",
                IMAGE, "4.19.0-okd-scos.17", "s3cr3t", CA));
    }

    private static ObjectNode renderFallback() {
        return AroConsoleManifests.render(new AroConsoleManifests.Settings(DOMAIN, CONSOLE_URL, null, null, IMAGE,
                "4.19.0-okd-scos.17", null, null));
    }

    @Test
    void theObjectsMirrorTheConsoleOperator() {
        ObjectNode list = render();
        assertEquals("List", list.path("kind").asText());
        assertEquals("Namespace", list.path("items").path(0).path("kind").asText(), "namespaces come first");
        for (String namespace : List.of("openshift-console", "openshift-console-user-settings",
                "openshift-config-managed")) {
            item(list, "Namespace", "", namespace);
        }
        item(list, "ServiceAccount", "openshift-console", "console");
        item(list, "ClusterRole", "", "console");
        assertEquals("console", item(list, "ClusterRoleBinding", "", "console").path("roleRef").path("name").asText());
        assertEquals("system:auth-delegator", item(list, "ClusterRoleBinding", "", "console-auth-delegator")
                .path("roleRef").path("name").asText());
        item(list, "Role", "openshift-console-user-settings", "console-user-settings-admin");
        item(list, "RoleBinding", "openshift-console-user-settings", "console-user-settings-admin");

        JsonNode service = item(list, "Service", "openshift-console", "console");
        assertEquals("console-serving-cert", service.path("metadata").path("annotations")
                .path("service.beta.openshift.io/serving-cert-secret-name").asText());
        assertEquals(443, service.path("spec").path("ports").path(0).path("port").asInt());
        assertEquals(8443, service.path("spec").path("ports").path(0).path("targetPort").asInt());

        JsonNode route = item(list, "Route", "openshift-console", "console");
        assertEquals("console-openshift-console.apps." + DOMAIN, route.path("spec").path("host").asText());
        assertEquals("reencrypt", route.path("spec").path("tls").path("termination").asText());
        assertEquals("Redirect", route.path("spec").path("tls").path("insecureEdgeTerminationPolicy").asText());
        assertEquals("5m", route.path("metadata").path("annotations").path("haproxy.router.openshift.io/timeout")
                .asText());

        assertEquals("s3cr3t", item(list, "Secret", "openshift-console", "console-oauth-config")
                .path("stringData").path("clientSecret").asText());
        assertEquals(CA, item(list, "ConfigMap", "openshift-console", "console-api-ca").path("data").path("ca.crt")
                .asText());
        assertEquals(CONSOLE_URL, item(list, "ConfigMap", "openshift-config-managed", "console-public")
                .path("data").path("consoleURL").asText());
    }

    @Test
    void theBridgeRunsWithAzureBrandingOffClusterThroughTheProxy() {
        JsonNode pod = item(render(), "Deployment", "openshift-console", "console").path("spec").path("template")
                .path("spec");
        assertEquals("console", pod.path("serviceAccountName").asText());
        assertEquals("10.1.2.3", pod.path("hostAliases").path(0).path("ip").asText());
        assertEquals("api." + DOMAIN, pod.path("hostAliases").path(0).path("hostnames").path(0).asText());
        JsonNode container = pod.path("containers").path(0);
        assertEquals(IMAGE, container.path("image").asText());
        for (String probe : List.of("startupProbe", "livenessProbe", "readinessProbe")) {
            assertEquals("/health", container.path(probe).path("httpGet").path("path").asText(), probe);
            assertEquals("HTTPS", container.path(probe).path("httpGet").path("scheme").asText(), probe);
        }
        List<String> command = command(container);
        assertEquals("/opt/bridge/bin/bridge", command.getFirst());
        assertTrue(command.contains("--base-address=" + CONSOLE_URL), command.toString());
        assertTrue(command.contains("--branding=azure"), command.toString());
        assertTrue(command.stream().noneMatch(arg -> arg.startsWith("--documentation-base-url")), command.toString());
        assertTrue(command.contains("--user-auth=openshift"), command.toString());
        assertTrue(command.contains("--user-auth-oidc-client-id=console"), command.toString());
        assertTrue(command.contains("--k8s-mode=off-cluster"), command.toString());
        assertTrue(command.contains("--k8s-mode-off-cluster-endpoint=" + API_URL), command.toString());
        assertTrue(command.contains("--k8s-public-endpoint=" + API_URL), command.toString());
        assertTrue(command.contains("--release-version=4.19.0-okd-scos.17"), command.toString());
        assertEquals("SSL_CERT_FILE", container.path("env").path(0).path("name").asText());
    }

    @Test
    void withoutTheOAuthServerTheConsoleRunsWithoutLoginAsItsServiceAccount() {
        ObjectNode list = renderFallback();
        JsonNode pod = item(list, "Deployment", "openshift-console", "console").path("spec").path("template")
                .path("spec");
        assertTrue(pod.path("hostAliases").isMissingNode());
        JsonNode container = pod.path("containers").path(0);
        List<String> command = command(container);
        assertTrue(command.contains("--user-auth=disabled"), command.toString());
        assertTrue(command.contains("--k8s-mode=in-cluster"), command.toString());
        assertTrue(command.stream().noneMatch(arg -> arg.startsWith("--user-auth-oidc")), command.toString());
        assertTrue(command.contains("--branding=azure"), command.toString());
        JsonNode token = container.path("env").path(0);
        assertEquals("BRIDGE_K8S_AUTH_BEARER_TOKEN", token.path("name").asText());
        assertEquals("console-token", token.path("valueFrom").path("secretKeyRef").path("name").asText());
        JsonNode secret = item(list, "Secret", "openshift-console", "console-token");
        assertEquals("kubernetes.io/service-account-token", secret.path("type").asText());
        assertEquals("console", secret.path("metadata").path("annotations")
                .path("kubernetes.io/service-account.name").asText());
        assertEquals("cluster-admin", item(list, "ClusterRoleBinding", "", "console-cluster-admin")
                .path("roleRef").path("name").asText());
        assertEquals(CONSOLE_URL, item(list, "ConfigMap", "openshift-config-managed", "console-public")
                .path("data").path("consoleURL").asText());
        assertThrows(AssertionError.class, () -> item(list, "Secret", "openshift-console", "console-oauth-config"));
    }

    @Test
    void theConsoleIsOffOnArm64UnlessTheImageIsChanged() {
        assertTrue(AroConsoleManifests.enabled(Optional.empty(), "x86_64", IMAGE));
        assertTrue(AroConsoleManifests.enabled(Optional.empty(), "amd64", IMAGE));
        assertFalse(AroConsoleManifests.enabled(Optional.empty(), "aarch64", IMAGE));
        assertFalse(AroConsoleManifests.enabled(Optional.empty(), "arm64", IMAGE));
        assertTrue(AroConsoleManifests.enabled(Optional.empty(), "aarch64", "registry.example.com/console:arm64"));
        assertFalse(AroConsoleManifests.enabled(Optional.of(false), "x86_64", IMAGE));
        assertTrue(AroConsoleManifests.enabled(Optional.of(true), "aarch64", IMAGE));
    }

    private static List<String> command(JsonNode container) {
        List<String> command = new ArrayList<>();
        container.path("command").forEach(arg -> command.add(arg.asText()));
        return command;
    }

    private static JsonNode item(ObjectNode list, String kind, String namespace, String name) {
        for (JsonNode item : list.path("items")) {
            if (kind.equals(item.path("kind").asText()) && name.equals(item.path("metadata").path("name").asText())
                    && namespace.equals(item.path("metadata").path("namespace").asText(""))) {
                return item;
            }
        }
        throw new AssertionError("no " + kind + " " + namespace + "/" + name + " in " + list);
    }
}
