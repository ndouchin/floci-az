package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The {@code clusterUser} kubeconfig of an Entra cluster: an exec user running {@code kubelogin get-token} with the
 * AKS server and client applications and {@code --login devicecode}, the form the az CLI looks for before it converts
 * it ({@code acs/custom.py}, {@code uses_kubelogin_devicecode}), or the legacy {@code azure} auth-provider form for
 * {@code format=azure}, whose keys kubelogin's converter reads ({@code convert.go}).
 */
class AksEntraKubeconfigTest {

    private static final String TENANT = "00000000-0000-0000-0000-000000000002";

    private static ManagedCluster cluster() {
        ManagedCluster cluster = new ManagedCluster();
        cluster.setName("aks1");
        cluster.setResourceGroup("rg1");
        cluster.setEndpoint("https://localhost:6443");
        cluster.setCaData("Q0E=");
        return cluster;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> user(String encoded) {
        Map<String, Object> config = new Yaml().load(new String(Base64.getDecoder().decode(encoded),
                StandardCharsets.UTF_8));
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) config.get("clusters");
        assertEquals("https://localhost:6443", ((Map<String, Object>) clusters.getFirst().get("cluster")).get("server"));
        List<Map<String, Object>> users = (List<Map<String, Object>>) config.get("users");
        assertEquals("clusterUser_rg1_aks1", users.getFirst().get("name"));
        return (Map<String, Object>) users.getFirst().get("user");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theExecFormRunsKubeloginWithTheAksApplicationsAndDeviceCode() {
        Map<String, Object> exec = (Map<String, Object>) user(AksKubeconfig.entraExec(cluster(), TENANT,
                "AzurePublicCloud", null)).get("exec");
        assertEquals("kubelogin", exec.get("command"));
        assertEquals("client.authentication.k8s.io/v1beta1", exec.get("apiVersion"));
        assertEquals(List.of("get-token", "--environment", "AzurePublicCloud",
                "--server-id", "6dae42f8-4368-4678-94ff-3960e28e3630",
                "--client-id", "80faf920-1908-4b52-b5ef-a8e7bedfc67a",
                "--tenant-id", TENANT, "--login", "devicecode"), exec.get("args"));
        assertEquals(false, exec.get("provideClusterInfo"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theFlociAuthorityOptionAppendsTheAuthorityHost() {
        Map<String, Object> exec = (Map<String, Object>) user(AksKubeconfig.entraExec(cluster(), TENANT,
                "AzurePublicCloud", "https://localhost:4577/")).get("exec");
        List<String> args = (List<String>) exec.get("args");
        assertEquals(List.of("--authority-host", "https://localhost:4577/", "--disable-instance-discovery"),
                args.subList(args.size() - 3, args.size()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAzureFormIsTheLegacyAuthProvider() {
        Map<String, Object> user = user(AksKubeconfig.entraAuthProvider(cluster(), TENANT, "AzurePublicCloud"));
        assertFalse(user.containsKey("exec"));
        Map<String, Object> provider = (Map<String, Object>) user.get("auth-provider");
        assertEquals("azure", provider.get("name"));
        Map<String, Object> config = (Map<String, Object>) provider.get("config");
        assertEquals("6dae42f8-4368-4678-94ff-3960e28e3630", config.get("apiserver-id"));
        assertEquals("80faf920-1908-4b52-b5ef-a8e7bedfc67a", config.get("client-id"));
        assertEquals(TENANT, config.get("tenant-id"));
        assertEquals("AzurePublicCloud", config.get("environment"));
        assertEquals("1", config.get("config-mode"));
    }
}
