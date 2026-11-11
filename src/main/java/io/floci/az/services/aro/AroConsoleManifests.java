package io.floci.az.services.aro;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The OpenShift web console of an ARO cluster, as the console-operator deploys it (openshift/console-operator
 * {@code release-4.19}: {@code manifests/} and {@code bindata/assets/}), with ARO's branding {@code azure}
 * (Azure/ARO-RP {@code pkg/cluster/consolebranding.go}). One deviation: the bridge runs with
 * {@code --k8s-mode=off-cluster} against floci-az's API proxy (a flag upstream labels "DEV ONLY"), because in-cluster
 * mode would send the user's {@code sha256~} token straight to MicroShift's kube-apiserver, which cannot validate it;
 * the pod reaches the proxy through a {@code hostAliases} entry for {@code api.<domain>}.
 *
 * <p>A cluster without the API proxy ({@code oauth.enabled=false}) gets the console without a login page instead:
 * {@code --user-auth=disabled} with the console ServiceAccount's token (also "DEV ONLY" upstream), bound to
 * {@code cluster-admin}, so every visitor acts as that ServiceAccount.</p>
 *
 * <p>Kept in step with floci-ocm's {@code ConsoleManifests}, which renders the same objects with ROSA's branding.</p>
 */
final class AroConsoleManifests {

    /** OKD 4.19.0-okd-scos.17's console ({@code oc adm release info ... --image-for=console}), amd64 only. */
    static final String DEFAULT_IMAGE =
            "quay.io/okd/scos-content@sha256:ba8cac1946833f0b26e657affe2c0eb46c2c0352bb293bd4b932f2f7e50dedab";
    static final String NAMESPACE = "openshift-console";
    static final String CONFIG_MANAGED = "openshift-config-managed";
    static final String USER_SETTINGS = "openshift-console-user-settings";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> RELEASE_ANNOTATIONS = Map.of(
            "include.release.openshift.io/hypershift", "true",
            "include.release.openshift.io/self-managed-high-availability", "true",
            "include.release.openshift.io/single-node-developer", "true",
            "capability.openshift.io/name", "Console");

    /**
     * @param fullDomain     the cluster's full domain ({@code apps.<fullDomain>} hosts its Routes)
     * @param consoleUrl     the bridge's base address, with the router's published HTTPS port
     * @param apiUrl         {@code https://api.<fullDomain>:<proxy port>}; {@code null} for a cluster without the
     *                       API proxy, which gets the console without login
     * @param proxyAddress   the IP at which pods reach the proxy, for {@code hostAliases}
     * @param releaseVersion the OKD release the cluster runs, shown in the console's About page
     * @param clientSecret   the OAuth client {@code console}'s secret
     * @param apiCa          PEM CA bundle that verifies the proxy's certificate for {@code api.<fullDomain>}
     */
    record Settings(String fullDomain, String consoleUrl, String apiUrl, String proxyAddress, String image,
                    String releaseVersion, String clientSecret, String apiCa) {

        boolean withLogin() {
            return apiUrl != null;
        }
    }

    private AroConsoleManifests() {
    }

    /**
     * Whether a cluster gets the console: as configured, else on with an amd64 Docker daemon, and on arm64 only with
     * an image other than the default, which is published for amd64 only (neither the OKD 4.19 payload nor
     * {@code quay.io/openshift/origin-console} has an arm64 build).
     */
    static boolean enabled(Optional<Boolean> configured, String architecture, String image) {
        String arch = architecture == null ? "" : architecture.toLowerCase(Locale.ROOT);
        return configured.orElseGet(() -> "amd64".equals(arch) || "x86_64".equals(arch)
                || !DEFAULT_IMAGE.equals(image));
    }

    /** A {@code v1 List} of the console's objects, in the order {@code oc apply} needs them. */
    static ObjectNode render(Settings settings) {
        ObjectNode list = JSON.createObjectNode();
        list.put("apiVersion", "v1");
        list.put("kind", "List");
        ArrayNode items = list.putArray("items");
        items.add(namespace(NAMESPACE, Map.of("openshift.io/cluster-monitoring", "true",
                "network.openshift.io/policy-group", "console")));
        items.add(namespace(USER_SETTINGS, Map.of()));
        items.add(namespace(CONFIG_MANAGED, Map.of()));
        items.add(object("v1", "ServiceAccount", NAMESPACE, "console"));
        items.add(consoleClusterRole());
        items.add(clusterRoleBinding("console", "console"));
        items.add(clusterRoleBinding("console-auth-delegator", "system:auth-delegator"));
        items.add(userSettingsRole());
        items.add(roleBinding(USER_SETTINGS, "console-user-settings-admin",
                subject("ServiceAccount", "console", NAMESPACE)));
        items.add(consolePublicRole());
        items.add(roleBinding(CONFIG_MANAGED, "console-public", subject("Group", "system:authenticated", null)));
        if (settings.withLogin()) {
            ObjectNode secret = object("v1", "Secret", NAMESPACE, "console-oauth-config");
            secret.put("type", "Opaque");
            secret.putObject("stringData").put("clientSecret", settings.clientSecret());
            items.add(secret);
            ObjectNode ca = object("v1", "ConfigMap", NAMESPACE, "console-api-ca");
            ca.putObject("data").put("ca.crt", settings.apiCa());
            items.add(ca);
        } else {
            items.add(clusterRoleBinding("console-cluster-admin", "cluster-admin"));
            ObjectNode token = object("v1", "Secret", NAMESPACE, "console-token");
            token.put("type", "kubernetes.io/service-account-token");
            ((ObjectNode) token.get("metadata")).putObject("annotations")
                    .put("kubernetes.io/service-account.name", "console");
            items.add(token);
        }
        items.add(service());
        items.add(route(settings.fullDomain()));
        items.add(deployment(settings));
        ObjectNode consolePublic = object("v1", "ConfigMap", CONFIG_MANAGED, "console-public");
        consolePublic.putObject("data").put("consoleURL", settings.consoleUrl());
        items.add(consolePublic);
        return list;
    }

    static List<String> bridgeCommand(Settings settings) {
        List<String> command = new ArrayList<>(List.of(
                "/opt/bridge/bin/bridge",
                "--public-dir=/opt/bridge/static",
                "--listen=https://[::]:8443",
                "--tls-cert-file=/var/serving-cert/tls.crt",
                "--tls-key-file=/var/serving-cert/tls.key",
                "--base-address=" + settings.consoleUrl(),
                "--branding=azure"));
        if (settings.withLogin()) {
            command.addAll(List.of(
                    "--user-auth=openshift",
                    "--user-auth-oidc-client-id=console",
                    "--user-auth-oidc-client-secret-file=/var/oauth-config/clientSecret",
                    "--user-auth-oidc-ca-file=/var/api-ca/ca.crt",
                    "--ca-file=/var/api-ca/ca.crt",
                    "--k8s-mode=off-cluster",
                    "--k8s-mode-off-cluster-endpoint=" + settings.apiUrl(),
                    "--k8s-mode-off-cluster-service-account-bearer-token-file="
                            + "/var/run/secrets/kubernetes.io/serviceaccount/token",
                    "--k8s-public-endpoint=" + settings.apiUrl()));
        } else {
            command.addAll(List.of("--user-auth=disabled", "--k8s-mode=in-cluster"));
        }
        command.add("--release-version=" + settings.releaseVersion());
        return command;
    }

    private static ObjectNode namespace(String name, Map<String, String> labels) {
        ObjectNode namespace = object("v1", "Namespace", null, name);
        ObjectNode metadata = (ObjectNode) namespace.get("metadata");
        ObjectNode annotations = metadata.putObject("annotations");
        RELEASE_ANNOTATIONS.forEach(annotations::put);
        annotations.put("openshift.io/node-selector", "");
        annotations.put("workload.openshift.io/allowed", "management");
        if (!labels.isEmpty()) {
            ObjectNode labelNode = metadata.putObject("labels");
            labels.forEach(labelNode::put);
        }
        return namespace;
    }

    private static ObjectNode consoleClusterRole() {
        ObjectNode role = object("rbac.authorization.k8s.io/v1", "ClusterRole", null, "console");
        ArrayNode rules = role.putArray("rules");
        rules.add(rule(List.of("apiextensions.k8s.io"), List.of("customresourcedefinitions"),
                List.of("get", "list", "watch"), null));
        rules.add(rule(List.of("admissionregistration.k8s.io"),
                List.of("mutatingwebhookconfigurations", "validatingwebhookconfigurations"), List.of("get"), null));
        rules.add(rule(List.of("operators.coreos.com"), List.of("subscriptions"), List.of("list"),
                List.of("web-terminal")));
        rules.add(rule(List.of("packages.operators.coreos.com"), List.of("packagemanifests"), List.of("get"), null));
        return role;
    }

    private static ObjectNode userSettingsRole() {
        ObjectNode role = object("rbac.authorization.k8s.io/v1", "Role", USER_SETTINGS,
                "console-user-settings-admin");
        List<String> all = List.of("get", "list", "watch", "create", "update", "delete", "patch");
        ArrayNode rules = role.putArray("rules");
        rules.add(rule(List.of("rbac.authorization.k8s.io"), List.of("roles", "rolebindings"), all, null));
        rules.add(rule(List.of(""), List.of("configmaps"), all, null));
        return role;
    }

    private static ObjectNode consolePublicRole() {
        ObjectNode role = object("rbac.authorization.k8s.io/v1", "Role", CONFIG_MANAGED, "console-public");
        role.putArray("rules").add(rule(List.of(""), List.of("configmaps"), List.of("get"),
                List.of("console-public")));
        return role;
    }

    private static ObjectNode clusterRoleBinding(String name, String clusterRole) {
        ObjectNode binding = object("rbac.authorization.k8s.io/v1", "ClusterRoleBinding", null, name);
        ObjectNode roleRef = binding.putObject("roleRef");
        roleRef.put("apiGroup", "rbac.authorization.k8s.io");
        roleRef.put("kind", "ClusterRole");
        roleRef.put("name", clusterRole);
        binding.putArray("subjects").add(subject("ServiceAccount", "console", NAMESPACE));
        return binding;
    }

    private static ObjectNode roleBinding(String namespace, String name, ObjectNode subject) {
        ObjectNode binding = object("rbac.authorization.k8s.io/v1", "RoleBinding", namespace, name);
        ObjectNode roleRef = binding.putObject("roleRef");
        roleRef.put("apiGroup", "rbac.authorization.k8s.io");
        roleRef.put("kind", "Role");
        roleRef.put("name", name);
        binding.putArray("subjects").add(subject);
        return binding;
    }

    private static ObjectNode subject(String kind, String name, String namespace) {
        ObjectNode subject = JSON.createObjectNode();
        subject.put("kind", kind);
        subject.put("name", name);
        if (namespace != null) {
            subject.put("namespace", namespace);
        } else {
            subject.put("apiGroup", "rbac.authorization.k8s.io");
        }
        return subject;
    }

    private static ObjectNode rule(List<String> groups, List<String> resources, List<String> verbs,
                                   List<String> names) {
        ObjectNode rule = JSON.createObjectNode();
        groups.forEach(rule.putArray("apiGroups")::add);
        resources.forEach(rule.putArray("resources")::add);
        if (names != null) {
            names.forEach(rule.putArray("resourceNames")::add);
        }
        verbs.forEach(rule.putArray("verbs")::add);
        return rule;
    }

    private static ObjectNode service() {
        ObjectNode service = object("v1", "Service", NAMESPACE, "console");
        ObjectNode metadata = (ObjectNode) service.get("metadata");
        metadata.putObject("labels").put("app", "console");
        metadata.putObject("annotations").put("service.beta.openshift.io/serving-cert-secret-name",
                "console-serving-cert");
        ObjectNode spec = service.putObject("spec");
        ObjectNode port = spec.putArray("ports").addObject();
        port.put("name", "https");
        port.put("protocol", "TCP");
        port.put("port", 443);
        port.put("targetPort", 8443);
        ObjectNode selector = spec.putObject("selector");
        selector.put("app", "console");
        selector.put("component", "ui");
        spec.put("type", "ClusterIP");
        spec.put("sessionAffinity", "None");
        return service;
    }

    private static ObjectNode route(String fullDomain) {
        ObjectNode route = object("route.openshift.io/v1", "Route", NAMESPACE, "console");
        ObjectNode metadata = (ObjectNode) route.get("metadata");
        metadata.putObject("labels").put("app", "console");
        metadata.putObject("annotations").put("haproxy.router.openshift.io/timeout", "5m");
        ObjectNode spec = route.putObject("spec");
        spec.put("host", "console-openshift-console.apps." + fullDomain);
        ObjectNode to = spec.putObject("to");
        to.put("kind", "Service");
        to.put("name", "console");
        to.put("weight", 100);
        spec.putObject("port").put("targetPort", "https");
        ObjectNode tls = spec.putObject("tls");
        tls.put("termination", "reencrypt");
        tls.put("insecureEdgeTerminationPolicy", "Redirect");
        spec.put("wildcardPolicy", "None");
        return route;
    }

    private static ObjectNode deployment(Settings settings) {
        ObjectNode deployment = object("apps/v1", "Deployment", NAMESPACE, "console");
        ObjectNode labels = JSON.createObjectNode();
        labels.put("app", "console");
        labels.put("component", "ui");
        ((ObjectNode) deployment.get("metadata")).set("labels", labels.deepCopy());
        ObjectNode spec = deployment.putObject("spec");
        spec.put("replicas", 1);
        spec.putObject("selector").set("matchLabels", labels.deepCopy());
        spec.putObject("strategy").put("type", "RollingUpdate");
        ObjectNode template = spec.putObject("template");
        ObjectNode templateMetadata = template.putObject("metadata");
        templateMetadata.put("name", "console");
        templateMetadata.set("labels", labels.deepCopy());
        ObjectNode annotations = templateMetadata.putObject("annotations");
        annotations.put("target.workload.openshift.io/management", "{\"effect\": \"PreferredDuringScheduling\"}");
        annotations.put("openshift.io/required-scc", "restricted-v2");
        ObjectNode pod = template.putObject("spec");
        pod.putObject("nodeSelector").put("node-role.kubernetes.io/master", "");
        pod.put("restartPolicy", "Always");
        pod.put("serviceAccountName", "console");
        ObjectNode podSecurity = pod.putObject("securityContext");
        podSecurity.put("runAsNonRoot", true);
        podSecurity.putObject("seccompProfile").put("type", "RuntimeDefault");
        pod.put("terminationGracePeriodSeconds", 40);
        pod.put("priorityClassName", "system-cluster-critical");
        if (settings.withLogin()) {
            ObjectNode alias = pod.putArray("hostAliases").addObject();
            alias.put("ip", settings.proxyAddress());
            alias.putArray("hostnames").add("api." + settings.fullDomain());
        }
        ObjectNode container = pod.putArray("containers").addObject();
        container.put("name", "console");
        container.put("image", settings.image());
        container.put("imagePullPolicy", "IfNotPresent");
        ArrayNode command = container.putArray("command");
        bridgeCommand(settings).forEach(command::add);
        ObjectNode env = container.putArray("env").addObject();
        if (settings.withLogin()) {
            env.put("name", "SSL_CERT_FILE");
            env.put("value", "/var/api-ca/ca.crt");
        } else {
            env.put("name", "BRIDGE_K8S_AUTH_BEARER_TOKEN");
            ObjectNode secretKey = env.putObject("valueFrom").putObject("secretKeyRef");
            secretKey.put("name", "console-token");
            secretKey.put("key", "token");
        }
        ObjectNode requests = container.putObject("resources").putObject("requests");
        requests.put("cpu", "10m");
        requests.put("memory", "100Mi");
        ObjectNode containerSecurity = container.putObject("securityContext");
        containerSecurity.put("readOnlyRootFilesystem", false);
        containerSecurity.put("allowPrivilegeEscalation", false);
        containerSecurity.putObject("capabilities").putArray("drop").add("ALL");
        container.putObject("lifecycle").putObject("preStop").putObject("exec").putArray("command").add("sleep")
                .add("25");
        container.set("startupProbe", probe(30, 10));
        container.set("livenessProbe", probe(1, 10));
        container.set("readinessProbe", probe(3, 1));
        ObjectNode port = container.putArray("ports").addObject();
        port.put("name", "https");
        port.put("containerPort", 8443);
        port.put("protocol", "TCP");
        container.put("terminationMessagePolicy", "FallbackToLogsOnError");
        ArrayNode mounts = container.putArray("volumeMounts");
        ArrayNode volumes = pod.putArray("volumes");
        mount(mounts, volumes, "serving-cert", "/var/serving-cert", "secret", "console-serving-cert");
        if (settings.withLogin()) {
            mount(mounts, volumes, "oauth-config", "/var/oauth-config", "secret", "console-oauth-config");
            mount(mounts, volumes, "api-ca", "/var/api-ca", "configMap", "console-api-ca");
        }
        ArrayNode tolerations = pod.putArray("tolerations");
        ObjectNode master = tolerations.addObject();
        master.put("key", "node-role.kubernetes.io/master");
        master.put("operator", "Exists");
        master.put("effect", "NoSchedule");
        ObjectNode unreachable = tolerations.addObject();
        unreachable.put("key", "node.kubernetes.io/unreachable");
        unreachable.put("operator", "Exists");
        unreachable.put("effect", "NoExecute");
        unreachable.put("tolerationSeconds", 120);
        return deployment;
    }

    private static ObjectNode probe(int failureThreshold, int timeoutSeconds) {
        ObjectNode probe = JSON.createObjectNode();
        ObjectNode get = probe.putObject("httpGet");
        get.put("path", "/health");
        get.put("port", 8443);
        get.put("scheme", "HTTPS");
        probe.put("failureThreshold", failureThreshold);
        probe.put("initialDelaySeconds", 0);
        probe.put("periodSeconds", 10);
        probe.put("successThreshold", 1);
        probe.put("timeoutSeconds", timeoutSeconds);
        return probe;
    }

    private static void mount(ArrayNode mounts, ArrayNode volumes, String name, String path, String type,
                              String source) {
        ObjectNode mount = mounts.addObject();
        mount.put("name", name);
        mount.put("mountPath", path);
        mount.put("readOnly", true);
        ObjectNode volume = volumes.addObject();
        volume.put("name", name);
        if ("secret".equals(type)) {
            volume.putObject("secret").put("secretName", source);
        } else {
            volume.putObject("configMap").put("name", source);
        }
    }

    private static ObjectNode object(String apiVersion, String kind, String namespace, String name) {
        ObjectNode object = JSON.createObjectNode();
        object.put("apiVersion", apiVersion);
        object.put("kind", kind);
        ObjectNode metadata = object.putObject("metadata");
        metadata.put("name", name);
        if (namespace != null) {
            metadata.put("namespace", namespace);
        }
        return object;
    }
}
