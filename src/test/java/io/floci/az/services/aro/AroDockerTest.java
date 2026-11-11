package io.floci.az.services.aro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An ARO cluster created through ARM on a real MicroShift container, then checked through its admin kubeconfig from
 * outside the container: the node, SCC admission, a Route on the apps domain, a PVC on the default storage class, the
 * {@code config.openshift.io} objects, and the cleanup on delete.
 *
 * <p>Skipped without Docker. MicroShift needs a privileged container on a cgroup v2, rootful daemon; the API answers
 * after about a minute and every pod runs after two to three, so the cluster gets 15 minutes.
 */
@QuarkusTest
@TestProfile(AroDockerTest.RealModeProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AroHandler: real MicroShift cluster (Docker required)")
class AroDockerTest {

    /**
     * Published endpoints, so the kubeconfig names the daemon's host and a published port, which the test reaches
     * whether it runs on the Docker host or in a container next to a remote daemon.
     */
    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-az.services.aro.mocked", "false",
                    "floci-az.docker.endpoint-mode", "published");
        }
    }

    private static final String SUB = "00000000-0000-0000-0000-0000000000d1";
    private static final String RG = "aro-docker-rg";
    private static final String NAME = "aro-docker";
    private static final String DOMAIN = "arodock";
    private static final String FULL_DOMAIN = DOMAIN + ".eastus.aroapp.io";
    private static final String API = "?api-version=2025-07-25";
    private static final String CLUSTER = "/subscriptions/" + SUB + "/resourceGroups/" + RG
            + "/providers/Microsoft.RedHatOpenShift/openShiftClusters/" + NAME;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DockerClient dockerClient;

    private String operationPath;
    private KubeApi kube;
    private String containerName;

    @BeforeAll
    void checkDockerAvailable() {
        boolean dockerAvailable = Files.exists(Paths.get("/var/run/docker.sock")) || System.getenv("DOCKER_HOST") != null;
        assumeTrue(dockerAvailable, "Docker socket not available: skipping the MicroShift tests");
    }

    @Test
    @Order(1)
    void creatingAClusterThroughArmAnswers201Creating() {
        given().post("/_admin/reset").then().statusCode(204);
        Response created = given().contentType("application/json").body("""
            {
              "location": "eastus",
              "properties": {
                "clusterProfile": {"domain": "%s", "version": "4.19.2"},
                "masterProfile": {"vmSize": "Standard_D8s_v5"},
                "workerProfiles": [{"name": "worker", "vmSize": "Standard_D4s_v5", "diskSizeGB": 128, "count": 3}]
              }
            }
            """.formatted(DOMAIN)).when().put(CLUSTER + API);
        created.then().statusCode(201)
                .body("properties.provisioningState", equalTo("Creating"))
                .header("Azure-AsyncOperation", containsString("/operationsstatus/"));
        operationPath = URI.create(created.header("Azure-AsyncOperation")).getPath() + API;
    }

    @Test
    @Order(2)
    void theOperationSucceedsOnceTheClusterIsReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(15).toMillis();
        String status = "Creating";
        while (!List.of("Succeeded", "Failed").contains(status) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5_000);
            status = given().when().get(operationPath).path("status");
        }
        assertEquals("Succeeded", status, "operation: " + given().when().get(operationPath).asString());
        given().when().get(CLUSTER + API).then().statusCode(200)
                .body("properties.provisioningState", equalTo("Succeeded"))
                .body("properties.apiserverProfile.url", startsWith("https://"))
                .body("properties.consoleProfile.url",
                        equalTo("https://console-openshift-console.apps." + FULL_DOMAIN + "/"));
        containerName = clusterContainer().getNames()[0].substring(1);
    }

    @Test
    @Order(3)
    void theAdminKubeconfigReachesTheApiServer() throws Exception {
        String encoded = given().contentType("application/json").when().post(CLUSTER + "/listAdminCredentials" + API)
                .then().statusCode(200).extract().path("kubeconfig");
        kube = KubeApi.from(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
        JsonNode version = kube.get("/version");
        assertEquals("1", version.path("major").asText());
        given().contentType("application/json").when().post(CLUSTER + "/listCredentials" + API)
                .then().statusCode(200).body("kubeadminUsername", equalTo("kubeadmin"));
    }

    @Test
    @Order(4)
    void theNodeIsAReadyAroMasterAndWorker() throws Exception {
        JsonNode nodes = kube.get("/api/v1/nodes").path("items");
        assertEquals(1, nodes.size());
        JsonNode node = nodes.get(0);
        assertTrue(node.path("metadata").path("name").asText().matches("aro-docker-[a-z0-9]{5}-master-0"),
                node.path("metadata").path("name").asText());
        JsonNode labels = node.path("metadata").path("labels");
        assertEquals("eastus", labels.path("topology.kubernetes.io/region").asText());
        assertEquals("eastus-1", labels.path("topology.kubernetes.io/zone").asText());
        assertEquals("Standard_D8s_v5", labels.path("node.kubernetes.io/instance-type").asText());
        assertTrue(labels.has("node-role.kubernetes.io/master"));
        assertTrue(labels.has("node-role.kubernetes.io/worker"));
        assertTrue(node.path("spec").path("providerID").asText()
                .startsWith("azure:///subscriptions/" + SUB + "/resourceGroups/aro-" + DOMAIN));
        boolean ready = false;
        for (JsonNode condition : node.path("status").path("conditions")) {
            ready |= "Ready".equals(condition.path("type").asText()) && "True".equals(condition.path("status").asText());
        }
        assertTrue(ready, node.path("status").path("conditions").toString());
    }

    @Test
    @Order(5)
    void theStorageClassesAreTheOnesAroShips() throws Exception {
        JsonNode disk = kube.get("/apis/storage.k8s.io/v1/storageclasses/managed-csi");
        assertEquals("disk.csi.azure.com", disk.path("provisioner").asText());
        assertEquals("true", disk.path("metadata").path("annotations")
                .path("storageclass.kubernetes.io/is-default-class").asText());
        assertEquals("WaitForFirstConsumer", disk.path("volumeBindingMode").asText());
        assertEquals("Premium_LRS", disk.path("parameters").path("skuname").asText());
        JsonNode file = kube.get("/apis/storage.k8s.io/v1/storageclasses/azurefile-csi");
        assertEquals("file.csi.azure.com", file.path("provisioner").asText());
        assertEquals("Immediate", file.path("volumeBindingMode").asText());
    }

    @Test
    @Order(6)
    void aWorkloadRunsUnderRestrictedV2WithABoundVolumeAndARoute() throws Exception {
        kube.post("/api/v1/namespaces", "{\"apiVersion\":\"v1\",\"kind\":\"Namespace\",\"metadata\":{\"name\":\"demo\"}}");
        JsonNode namespace = kube.await("/api/v1/namespaces/demo",
                ns -> ns.path("metadata").path("annotations").has("openshift.io/sa.scc.uid-range"));
        String uidRange = namespace.path("metadata").path("annotations").path("openshift.io/sa.scc.uid-range").asText();
        long uidStart = Long.parseLong(uidRange.split("/")[0]);
        kube.await("/api/v1/namespaces/demo/serviceaccounts/default", sa -> true);

        kube.post("/api/v1/namespaces/demo/persistentvolumeclaims", """
            {"apiVersion":"v1","kind":"PersistentVolumeClaim","metadata":{"name":"data"},
             "spec":{"accessModes":["ReadWriteOnce"],"resources":{"requests":{"storage":"1Gi"}}}}
            """);
        kube.post("/apis/apps/v1/namespaces/demo/deployments", """
            {"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"web"},
             "spec":{"replicas":1,"selector":{"matchLabels":{"app":"web"}},
              "template":{"metadata":{"labels":{"app":"web"}},
               "spec":{"containers":[{"name":"web","image":"docker.io/library/busybox:1.37",
                 "command":["sh","-c","echo hello > /data/index.html && httpd -f -p 8080 -h /data"],
                 "ports":[{"containerPort":8080}],
                 "volumeMounts":[{"name":"data","mountPath":"/data"}]}],
                "volumes":[{"name":"data","persistentVolumeClaim":{"claimName":"data"}}]}}}}
            """);
        kube.post("/api/v1/namespaces/demo/services", """
            {"apiVersion":"v1","kind":"Service","metadata":{"name":"web"},
             "spec":{"selector":{"app":"web"},"ports":[{"port":8080,"targetPort":8080}]}}
            """);
        kube.post("/apis/route.openshift.io/v1/namespaces/demo/routes", """
            {"apiVersion":"route.openshift.io/v1","kind":"Route","metadata":{"name":"web"},
             "spec":{"to":{"kind":"Service","name":"web"}}}
            """);

        JsonNode pods = kube.await("/api/v1/namespaces/demo/pods?labelSelector=app%3Dweb", list ->
                list.path("items").size() == 1
                        && "Running".equals(list.path("items").get(0).path("status").path("phase").asText()));
        JsonNode pod = pods.path("items").get(0);
        assertEquals("restricted-v2", pod.path("metadata").path("annotations").path("openshift.io/scc").asText());
        long uid = pod.path("spec").path("containers").get(0).path("securityContext").path("runAsUser").asLong();
        assertTrue(uid >= uidStart && uid < uidStart + 10000, "uid " + uid + " outside " + uidRange);

        JsonNode claim = kube.get("/api/v1/namespaces/demo/persistentvolumeclaims/data");
        assertEquals("Bound", claim.path("status").path("phase").asText());
        assertEquals("managed-csi", claim.path("spec").path("storageClassName").asText());

        JsonNode route = kube.await("/apis/route.openshift.io/v1/namespaces/demo/routes/web",
                r -> r.path("status").path("ingress").size() > 0);
        assertEquals("web-demo.apps." + FULL_DOMAIN, route.path("spec").path("host").asText());
        JsonNode admitted = route.path("status").path("ingress").get(0).path("conditions").get(0);
        assertEquals("Admitted", admitted.path("type").asText());
        assertEquals("True", admitted.path("status").asText());

        String page = throughRouter("web-demo.apps." + FULL_DOMAIN);
        assertTrue(page.contains("hello"), page);
    }

    @Test
    @Order(7)
    void theClusterConfigObjectsDescribeAnAroCluster() throws Exception {
        JsonNode version = kube.get("/apis/config.openshift.io/v1/clusterversions/version");
        assertEquals("4.19.0-okd-scos.17", version.path("status").path("desired").path("version").asText());
        assertEquals("Completed", version.path("status").path("history").get(0).path("state").asText());
        assertFalse(version.path("spec").path("clusterID").asText().isEmpty());
        JsonNode infrastructure = kube.get("/apis/config.openshift.io/v1/infrastructures/cluster");
        assertEquals("Azure", infrastructure.path("status").path("platform").asText());
        assertEquals("https://api." + FULL_DOMAIN + ":6443", infrastructure.path("status").path("apiServerURL").asText());
        assertEquals("Azure", infrastructure.path("status").path("platformStatus").path("type").asText());
        JsonNode ingress = kube.get("/apis/config.openshift.io/v1/ingresses/cluster");
        assertEquals("apps." + FULL_DOMAIN, ingress.path("spec").path("domain").asText());
    }

    @Test
    @Order(8)
    void deletingRemovesTheContainerAndItsVolumes() throws InterruptedException {
        given().when().delete(CLUSTER + API).then().statusCode(202);
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        int status = 200;
        while (status != 404 && System.currentTimeMillis() < deadline) {
            Thread.sleep(2_000);
            status = given().when().get(CLUSTER + API).statusCode();
        }
        assertEquals(404, status);
        assertTrue(dockerClient.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("io.floci.resource-id", CLUSTER)).exec().isEmpty());
        List<String> volumes = dockerClient.listVolumesCmd().exec().getVolumes().stream()
                .map(InspectVolumeResponse::getName).toList();
        assertFalse(volumes.contains(containerName + "-data"), volumes.toString());
        assertFalse(volumes.contains(containerName + "-storage"), volumes.toString());
    }

    /** GETs {@code /} from the router's published HTTP port with {@code host}, retrying while the router syncs. */
    private String throughRouter(String host) throws Exception {
        Ports.Binding[] bindings = dockerClient.inspectContainerCmd(clusterContainer().getId()).exec()
                .getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(80));
        assertNotNull(bindings, "the router's port 80 is not published");
        int port = Integer.parseInt(bindings[0].getHostPortSpec());
        String apiHost = URI.create(kube.server).getHost();
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket(apiHost, port)) {
                socket.setSoTimeout(10_000);
                socket.getOutputStream().write(("GET / HTTP/1.0\r\nHost: " + host + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                last = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (last.startsWith("HTTP/1.0 200") || last.startsWith("HTTP/1.1 200")) {
                    return last;
                }
            } catch (IOException e) {
                last = e.toString();
            }
            Thread.sleep(2_000);
        }
        return last;
    }

    private Container clusterContainer() {
        List<Container> containers = dockerClient.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("io.floci.resource-id", CLUSTER)).exec();
        assertEquals(1, containers.size());
        return containers.getFirst();
    }

    /** Just enough of a Kubernetes client to talk to the cluster with the admin kubeconfig's client certificate. */
    static final class KubeApi {
        private final HttpClient http;
        final String server;

        private KubeApi(HttpClient http, String server) {
            this.http = http;
            this.server = server;
        }

        static KubeApi from(String kubeconfig) throws Exception {
            String server = field(kubeconfig, "server");
            CertificateFactory certificates = CertificateFactory.getInstance("X.509");
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int i = 0;
            for (Certificate ca : certificates.generateCertificates(new ByteArrayInputStream(
                    Base64.getDecoder().decode(field(kubeconfig, "certificate-authority-data"))))) {
                trust.setCertificateEntry("ca-" + i++, ca);
            }
            Certificate[] chain = certificates.generateCertificates(new ByteArrayInputStream(
                    Base64.getDecoder().decode(field(kubeconfig, "client-certificate-data")))).toArray(Certificate[]::new);
            PrivateKey key;
            try (PEMParser parser = new PEMParser(new StringReader(new String(
                    Base64.getDecoder().decode(field(kubeconfig, "client-key-data")), StandardCharsets.UTF_8)))) {
                Object parsed = parser.readObject();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
                key = parsed instanceof PEMKeyPair pair ? converter.getKeyPair(pair).getPrivate()
                        : converter.getPrivateKey((PrivateKeyInfo) parsed);
            }
            KeyStore identity = KeyStore.getInstance("PKCS12");
            identity.load(null, null);
            identity.setKeyEntry("client", key, new char[0], chain);
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(identity, new char[0]);
            TrustManagerFactory trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trusts.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), trusts.getTrustManagers(), null);
            return new KubeApi(HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(10))
                    .build(), server);
        }

        JsonNode get(String path) throws Exception {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(server + path)).GET()
                    .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "GET " + path + ": " + response.body());
            return MAPPER.readTree(response.body());
        }

        void post(String path, String json) throws Exception {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(server + path))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() == 201 || response.statusCode() == 200,
                    "POST " + path + " answered " + response.statusCode() + ": " + response.body());
        }

        /** Polls {@code path} for up to three minutes until {@code done} accepts it. */
        JsonNode await(String path, Predicate<JsonNode> done) throws Exception {
            long deadline = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
            String last = "";
            while (System.currentTimeMillis() < deadline) {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(server + path)).GET()
                        .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
                last = response.body();
                if (response.statusCode() == 200) {
                    JsonNode node = MAPPER.readTree(last);
                    if (done.test(node)) {
                        return node;
                    }
                }
                Thread.sleep(2_000);
            }
            fail("timed out waiting on " + path + ": " + last);
            return null;
        }

        private static String field(String kubeconfig, String name) {
            Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + ":\\s*(\\S+)\\s*$").matcher(kubeconfig);
            assertTrue(m.find(), name + " missing from the kubeconfig");
            return m.group(1);
        }
    }
}
