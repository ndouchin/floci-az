package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aks.AksModels.AgentPoolProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.floci.az.services.aks.AksOperations.Kind;
import io.floci.az.services.aks.AksVersions.Version;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP handler for Azure Kubernetes Service (AKS) management-plane requests.
 *
 * <h2>Routing</h2>
 * <p>All requests use ARM paths:</p>
 * <pre>
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerService/managedClusters
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerService/locations/{loc}/kubernetesVersions
 *   GET    subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.ContainerService/managedClusters
 *   GET    .../managedClusters/{name}
 *   PUT    .../managedClusters/{name}
 *   PATCH  .../managedClusters/{name}                                (tags)
 *   DELETE .../managedClusters/{name}
 *   POST   .../managedClusters/{name}/start | stop
 *   POST   .../managedClusters/{name}/listClusterAdminCredential | listClusterUserCredential
 *   GET    .../managedClusters/{name}/upgradeProfiles/default
 *   GET    .../managedClusters/{name}/availableAgentPoolVersions
 *   GET    .../managedClusters/{name}/agentPools
 *   GET    .../managedClusters/{name}/agentPools/{pool}
 *   PUT    .../managedClusters/{name}/agentPools/{pool}
 *   DELETE .../managedClusters/{name}/agentPools/{pool}
 *   GET    .../managedClusters/{name}/agentPools/{pool}/upgradeProfiles/default
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerService/locations/{loc}/operations/{opId}        (LRO status)
 *   GET    subscriptions/{sub}/providers/Microsoft.ContainerService/locations/{loc}/operationresults/{opId}  (LRO result)
 * </pre>
 *
 * <h2>Long-running operations</h2>
 * <p>PUT, DELETE, start and stop return at once with an operation URL; the cluster's containers are brought to the
 * requested state by {@link AksReconciler}, and the operation reports the provisioning state that leaves.</p>
 *
 * <h2>Mocked mode</h2>
 * <p>When {@code floci-az.services.aks.mocked=true}, no k3s container is started. Clusters transition immediately to
 * "Succeeded" with a synthetic kubeconfig pointing at localhost.</p>
 */
@ApplicationScoped
public class AksHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor {

    private static final Logger LOG = Logger.getLogger(AksHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String TYPE = "Microsoft.ContainerService/managedClusters";
    private static final String AGENT_POOL_TYPE = "Microsoft.ContainerService/managedClusters/agentPools";
    private static final String PROVIDER_PREFIX = "/providers/Microsoft.ContainerService/";
    private static final String DEFAULT_VM_SIZE = "Standard_DS2_v2";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmulatorConfig config;
    private final AksClusterManager clusterManager;
    private final CloudDefaults defaults;
    private final AksVersions versions;
    private final AksOperations operations = new AksOperations();
    /**
     * Every cluster, keyed by its canonical id. Before the store, clusters were kept under raw-case
     * {@code sub/rg/name}; those entries are read as they are and move to their canonical key on their next write.
     */
    private final ArmResourceStore<ManagedCluster> store;
    private final AksReconciler reconciler;

    @Inject
    public AksHandler(EmulatorConfig config,
                      AksClusterManager clusterManager,
                      StorageFactory storageFactory,
                      CloudDefaults defaults) {
        this.config = config;
        this.defaults = defaults;
        this.clusterManager = clusterManager;
        this.versions = new AksVersions(config.services().aks().imageRepository(),
                config.services().aks().defaultImage());
        this.store = ArmResourceStore.create(storageFactory, "aks", ManagedCluster.class, ManagedCluster::armId,
                key -> true);
        this.reconciler = new AksReconciler(store, clusterManager, versions,
                new AksClusterRuntime(clusterManager, versions, config.services().aks().maxNodesPerCluster()),
                Duration.ofSeconds(Math.max(1, config.services().aks().readyTimeoutSeconds())));
    }

    @PostConstruct
    public void init() {
        dropShadowedClusters();
    }

    /**
     * In a persistent storage mode, clusters recorded before a restart are brought back: surviving containers are
     * adopted, a lost container is recreated on its data volume, and a lost volume gives a fresh cluster.
     */
    void onStart(@Observes StartupEvent event) {
        restorePersistedClusters();
    }

    void restorePersistedClusters() {
        if (config.services().aks().mocked()) {
            return;
        }
        for (ArmResourceStore.Stored<ManagedCluster> stored : store.all()) {
            LOG.infov("Restoring AKS cluster {0}", stored.value().getName());
            reconciler.submit(stored.id());
        }
    }

    /**
     * Old raw-case keys could hold one cluster under several casings, each with a k3s container of its own (named
     * from the record's {@code instanceId}). The store serves one of them; the others are removed here, with their
     * containers, before anything could drop them and leave the containers running.
     */
    void dropShadowedClusters() {
        for (ArmResourceStore.Stored<ManagedCluster> shadowed : store.removeShadowedLegacy()) {
            ManagedCluster cluster = shadowed.value();
            LOG.infov("AKS cluster {0}: dropping the duplicate stored under {1}/{2}", cluster.getName(),
                    cluster.getSubscriptionId(), cluster.getResourceGroup());
            if (!config.services().aks().mocked()) {
                try {
                    clusterManager.removeCluster(cluster);
                } catch (Exception e) {
                    LOG.warnv("Could not remove the k3s container of duplicate AKS cluster {0}: {1}. Remove container"
                            + " {2} (id {3}) and volume {2} by hand", cluster.getName(), e.getMessage(),
                            clusterManager.containerName(cluster), cluster.getContainerId());
                }
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        reconciler.shutdown();
        if (!config.services().aks().mocked()) {
            scanAll().forEach(cluster -> {
                try {
                    clusterManager.stopCluster(cluster);
                } catch (Exception e) {
                    LOG.warnv("Error stopping AKS cluster {0}: {1}", cluster.getName(), e.getMessage());
                }
            });
        }
    }

    @Override
    public String getServiceType() { return "aks"; }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().aks().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider("Microsoft.ContainerService")
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        String fullPath = req.resourcePath();
        String method = req.method();

        LOG.debugf("AksHandler: %s %s", method, fullPath);

        String tail = extractAksPath(fullPath);

        if ("GET".equals(method) && tail.matches("(?i)locations/[^/]+/operations/[^/]+")) {
            return operationStatusResponse(req, segment(tail, 1), segment(tail, 3));
        }
        if ("GET".equals(method) && tail.matches("(?i)locations/[^/]+/operationresults/[^/]+")) {
            return operationResultResponse(req, segment(tail, 1), segment(tail, 3));
        }
        if ("GET".equals(method) && tail.matches("(?i)locations/[^/]+/kubernetesVersions")) {
            return kubernetesVersionsResponse();
        }

        if (tail.equalsIgnoreCase("managedClusters") && !fullPath.contains("/resourceGroups/")) {
            return handleListSubscription(fullPath);
        }
        if (tail.equalsIgnoreCase("managedClusters") && "GET".equals(method)) {
            return handleListByResourceGroup(fullPath);
        }

        String sub = extractSubscriptionId(fullPath);
        String rg = extractResourceGroup(fullPath);

        if (tail.matches("managedClusters/[^/]+/listCluster(?:Admin|User)Credential") && "POST".equals(method)) {
            return handleListCredentials(sub, rg, segment(tail, 1), tail.endsWith("AdminCredential"));
        }
        if (tail.matches("(?i)managedClusters/[^/]+/(?:start|stop)") && "POST".equals(method)) {
            return handlePower(req, sub, rg, segment(tail, 1), segment(tail, 2).equalsIgnoreCase("start"));
        }
        if (tail.matches("(?i)managedClusters/[^/]+/upgradeProfiles/default") && "GET".equals(method)) {
            return handleClusterUpgradeProfile(sub, rg, segment(tail, 1));
        }
        if (tail.matches("(?i)managedClusters/[^/]+/availableAgentPoolVersions") && "GET".equals(method)) {
            return handleAvailableAgentPoolVersions(sub, rg, segment(tail, 1));
        }
        if (tail.matches("managedClusters/[^/]+/agentPools") && "GET".equals(method)) {
            return handleListAgentPools(sub, rg, segment(tail, 1));
        }
        if (tail.matches("(?i)managedClusters/[^/]+/agentPools/[^/]+/upgradeProfiles/default")
                && "GET".equals(method)) {
            return handleAgentPoolUpgradeProfile(sub, rg, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("managedClusters/[^/]+/agentPools/[^/]+")) {
            return handleAgentPool(req, method, sub, rg, segment(tail, 1), segment(tail, 3));
        }
        if (tail.matches("managedClusters/[^/]+")) {
            String clusterName = segment(tail, 1);
            return switch (method) {
                case "GET"    -> handleGet(sub, rg, clusterName);
                case "PUT"    -> handleCreateOrUpdate(req, sub, rg, clusterName);
                case "DELETE" -> handleDelete(req, sub, rg, clusterName);
                case "PATCH"  -> handleUpdateTags(sub, rg, clusterName, req);
                default       -> methodNotAllowed();
            };
        }

        return notFound("Unknown AKS path: " + tail);
    }

    // ── Cluster CreateOrUpdate ─────────────────────────────────────────────────

    /** A request the emulator refuses, carrying the AKS error it answers with. */
    static final class AksRequestException extends RuntimeException {
        private final int status;
        private final String code;

        AksRequestException(int status, String code, String message) {
            super(message, null, false, false);
            this.status = status;
            this.code = code;
        }

        String code() {
            return code;
        }

        Response response() {
            return ArmErrors.error(status, code, getMessage());
        }
    }

    private Response handleCreateOrUpdate(AzureRequest req, String sub, String rg, String clusterName) {
        JsonNode body = readBody(req.bodyStream());
        JsonNode props = body.path("properties");
        ArmResourceId id = clusterId(sub, rg, clusterName);
        boolean mocked = config.services().aks().mocked();
        AtomicReference<Boolean> created = new AtomicReference<>(false);
        ManagedCluster written;
        try {
            List<PoolRequest> poolRequests = parsePools(props.path("agentPoolProfiles"));
            written = store.createOrUpdate(id, existing -> {
                if (existing.isPresent() && "Deleting".equals(existing.get().getProvisioningState())) {
                    throw new AksRequestException(409, "OperationNotAllowed", "Managed cluster '" + clusterName
                            + "' is being deleted, the operation cannot be performed.");
                }
                ManagedCluster cluster = existing.orElseGet(() -> newCluster(sub, rg, clusterName));
                created.set(existing.isEmpty());
                applyClusterRequest(cluster, existing.isPresent(), body, props, poolRequests, req);
                cluster.setGeneration(cluster.getGeneration() + 1);
                if (mocked) {
                    settleMocked(cluster);
                }
                return cluster;
            }).stored().value();
        } catch (AksRequestException e) {
            return e.response();
        }
        boolean isNew = created.get();
        Kind kind = isNew ? Kind.CREATE : Kind.UPDATE;
        AksOperations.Operation op = mocked
                ? operations.completed(kind, id.canonicalKey(), written.getInstanceId())
                : operations.start(kind, id.canonicalKey(), written.getInstanceId());
        if (!mocked) {
            reconciler.submit(id);
        }
        return Response.status(isNew ? 201 : 200)
                .entity(toArmResponse(written))
                .type("application/json")
                .header("Azure-AsyncOperation", operationUrl(req, sub, written.getLocation(), op.id(), "operations"))
                .header("Retry-After", "1")
                .build();
    }

    private ManagedCluster newCluster(String sub, String rg, String clusterName) {
        ManagedCluster cluster = new ManagedCluster();
        cluster.setInstanceId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        cluster.setSubscriptionId(sub);
        cluster.setResourceGroup(rg);
        cluster.setName(clusterName);
        cluster.setCreatedAt(Instant.now());
        cluster.setPowerState("Running");
        cluster.setClusterToken(randomToken());
        return cluster;
    }

    private void applyClusterRequest(ManagedCluster cluster, boolean exists, JsonNode body, JsonNode props,
                                     List<PoolRequest> poolRequests, AzureRequest req) {
        String location = body.path("location").asText(
                cluster.getLocation() != null ? cluster.getLocation() : defaults.defaultLocation(req.scope()));
        String requestedVersion = textOrNull(props, "kubernetesVersion");
        Version current = exists ? currentVersion(cluster) : null;
        Version controlPlane = resolveControlPlaneVersion(cluster, current, requestedVersion);
        boolean upgrading = current != null && !controlPlane.equals(current);
        checkClusterPoolVersions(cluster, poolRequests, controlPlane, upgrading);

        cluster.setLocation(location);
        cluster.setKubernetesVersion(requestedVersion != null ? requestedVersion
                : cluster.getKubernetesVersion() != null ? cluster.getKubernetesVersion()
                : controlPlane.minorVersion());
        cluster.setCurrentKubernetesVersion(controlPlane.toString());
        String dnsPrefix = props.path("dnsPrefix").asText(
                cluster.getDnsPrefix() != null ? cluster.getDnsPrefix() : cluster.getName() + "-dns");
        cluster.setDnsPrefix(dnsPrefix);
        if (!exists) {
            cluster.setFqdn(dnsPrefix + ".hcp." + location + ".azmk8s.io");
            cluster.setNodeResourceGroup(props.path("nodeResourceGroup").asText(
                    "MC_" + cluster.getResourceGroup() + "_" + cluster.getName() + "_" + location));
        }
        if (body.has("tags")) {
            cluster.setTags(parseTags(body.path("tags")));
        } else if (!exists) {
            cluster.setTags(new LinkedHashMap<>());
        }
        if (!exists) {
            cluster.setNetworkProfile(AksNetwork.resolve(props.path("networkProfile")));
        }
        if (props.has("disableLocalAccounts")) {
            cluster.setDisableLocalAccounts(props.path("disableLocalAccounts").asBoolean());
        }
        cluster.setEchoedProperties(echoedProperties(cluster.getEchoedProperties(), props));

        if (!exists && poolRequests.isEmpty()) {
            poolRequests = List.of(PoolRequest.defaults("nodepool1"));
        }
        for (PoolRequest request : poolRequests) {
            Optional<AgentPoolProfile> existingPool = cluster.pool(request.name());
            AgentPoolProfile pool = existingPool.orElseGet(() -> {
                AgentPoolProfile created = new AgentPoolProfile();
                created.setName(request.name());
                cluster.pools().add(created);
                return created;
            });
            Version poolVersion = poolVersionFor(request, existingPool.orElse(null), controlPlane, upgrading);
            applyPoolRequest(pool, request, existingPool.isEmpty(), poolVersion, defaultMaxPods(cluster));
        }
        if (upgrading) {
            for (AgentPoolProfile pool : cluster.pools()) {
                if (poolRequests.stream().noneMatch(r -> r.name().equals(pool.getName()))) {
                    applyPoolRequest(pool, PoolRequest.defaults(pool.getName()), false, controlPlane,
                            defaultMaxPods(cluster));
                }
            }
        }
        cluster.setProvisioningState(!exists ? "Creating" : upgrading ? "Upgrading" : "Updating");
        cluster.setLastError(null);
        for (AgentPoolProfile pool : cluster.pools()) {
            if (pool.getPowerState() == null) {
                pool.setPowerState(cluster.getPowerState());
            }
        }
    }

    private Version resolveControlPlaneVersion(ManagedCluster cluster, Version current, String requested) {
        if (current != null && versions.sameAsCurrent(requested, current)) {
            return current;
        }
        Version target = versions.resolve(requested).orElseThrow(() -> versionNotSupported(
                "K8sVersionNotSupported", requested));
        if (current != null && !AksVersions.upgradeAllowed(current, target)) {
            throw new AksRequestException(400, "OperationNotAllowed", "Upgrading Kubernetes version " + current
                    + " to " + target + " is not allowed. Available upgrades: " + versions.upgradesFrom(current)
                    + ". For more information, see https://aka.ms/aks-supported-k8s-ver for version details.");
        }
        return target;
    }

    /**
     * The managed-cluster API either names no agent pool version or names each one, unchanged or equal to the
     * control plane's; a per-pool change goes through the agent pool API.
     */
    private static void checkClusterPoolVersions(ManagedCluster cluster, List<PoolRequest> requests,
                                                 Version controlPlane, boolean upgrading) {
        List<PoolRequest> existing = requests.stream().filter(r -> cluster.pool(r.name()).isPresent()).toList();
        long specified = existing.stream().filter(r -> r.orchestratorVersion() != null).count();
        boolean mixed = upgrading && specified > 0 && specified < existing.size();
        boolean changed = existing.stream()
                .filter(r -> r.orchestratorVersion() != null)
                .anyMatch(r -> {
                    AgentPoolProfile pool = cluster.pool(r.name()).orElseThrow();
                    String requested = r.orchestratorVersion();
                    return !sameVersion(requested, pool.getCurrentOrchestratorVersion())
                            && !sameVersion(requested, controlPlane.toString());
                });
        if (mixed || changed) {
            throw new AksRequestException(400, "NotAllAgentPoolOrchestratorVersionSpecifiedAndUnchanged",
                    "Using managed cluster api, all Agent pools' OrchestratorVersion must be all specified or all"
                            + " unspecified. If all specified, they must be stay unchanged or the same with control"
                            + " plane. For agent pool specific change, please use per agent pool operations:"
                            + " https://aka.ms/agent-pool-rest-api");
        }
    }

    private static boolean sameVersion(String requested, String full) {
        if (requested == null || full == null) {
            return false;
        }
        return requested.equals(full) || full.startsWith(requested + ".");
    }

    private Version poolVersionFor(PoolRequest request, AgentPoolProfile existing, Version controlPlane,
                                   boolean upgrading) {
        if (request.orchestratorVersion() == null) {
            if (existing == null || upgrading || existing.getCurrentOrchestratorVersion() == null) {
                return controlPlane;
            }
            return Version.parse(existing.getCurrentOrchestratorVersion()).orElse(controlPlane);
        }
        if (existing != null && sameVersion(request.orchestratorVersion(), existing.getCurrentOrchestratorVersion())) {
            return Version.parse(existing.getCurrentOrchestratorVersion()).orElse(controlPlane);
        }
        Version version = versions.resolve(request.orchestratorVersion()).orElseThrow(() -> versionNotSupported(
                "AgentPoolK8sVersionNotSupported", request.orchestratorVersion()));
        checkSkew(version, controlPlane);
        return version;
    }

    private static void checkSkew(Version pool, Version controlPlane) {
        AksVersions.poolSkew(pool, controlPlane).ifPresent(skew -> {
            String detail = skew == AksVersions.Skew.AHEAD
                    ? "Minor version of node pool version " + pool.minor() + " is bigger than control plane version "
                            + controlPlane.minor() + "."
                    : "Minor version of node pool cannot be more than 3 versions less than control plane's version."
                            + " Minor version of node pool is " + pool.minor() + " and control plane is "
                            + controlPlane.minor() + ".";
            throw new AksRequestException(400, "NodePoolMcVersionIncompatible", "Node pool version " + pool
                    + " and control plane version " + controlPlane + " are incompatible. " + detail
                    + " For more information, see https://aka.ms/aks/UpgradeVersionRules.");
        });
    }

    private AksRequestException versionNotSupported(String code, String requested) {
        return new AksRequestException(400, code, "Version " + requested + " is not supported in this region."
                + " Please use [az aks get-versions] command to get the supported version list in this region."
                + " For more information, see https://aka.ms/supported-version-list.");
    }

    private Version currentVersion(ManagedCluster cluster) {
        return Version.parse(cluster.getCurrentKubernetesVersion())
                .or(() -> versions.resolve(cluster.getKubernetesVersion()))
                .orElseGet(versions::defaultVersion);
    }

    private static void settleMocked(ManagedCluster cluster) {
        cluster.setProvisioningState("Succeeded");
        cluster.pools().removeIf(p -> "Deleting".equals(p.getProvisioningState()));
        for (AgentPoolProfile pool : cluster.pools()) {
            pool.setProvisioningState("Succeeded");
            pool.setPowerState(cluster.getPowerState());
        }
    }

    // ── Agent pool request parsing ─────────────────────────────────────────────

    /** The agent pool fields a request sets; null means the request leaves that field as it is. */
    record PoolRequest(String name, Integer count, String vmSize, String osType, String osSku, String mode,
                       String orchestratorVersion, Integer maxPods, Boolean enableAutoScaling, Integer minCount,
                       Integer maxCount, Map<String, String> nodeLabels, List<String> nodeTaints,
                       List<String> availabilityZones) {

        static PoolRequest defaults(String name) {
            return new PoolRequest(name, null, null, null, null, null, null, null, null, null, null, null, null,
                    null);
        }

        static PoolRequest parse(String name, JsonNode p) {
            Map<String, String> labels = p.has("nodeLabels") ? parseTags(p.path("nodeLabels")) : null;
            List<String> taints = p.has("nodeTaints") ? textList(p.path("nodeTaints")) : null;
            try {
                if (labels != null) {
                    AksNodeLayout.validateLabels(labels);
                }
                if (taints != null) {
                    AksNodeLayout.validateTaints(taints);
                }
            } catch (IllegalArgumentException e) {
                throw new AksRequestException(400, "InvalidParameter", e.getMessage());
            }
            return new PoolRequest(name,
                    p.hasNonNull("count") ? p.path("count").asInt() : null,
                    textOrNull(p, "vmSize"),
                    textOrNull(p, "osType"),
                    textOrNull(p, "osSKU"),
                    textOrNull(p, "mode"),
                    textOrNull(p, "orchestratorVersion"),
                    p.hasNonNull("maxPods") ? p.path("maxPods").asInt() : null,
                    p.hasNonNull("enableAutoScaling") ? p.path("enableAutoScaling").asBoolean() : null,
                    p.hasNonNull("minCount") ? p.path("minCount").asInt() : null,
                    p.hasNonNull("maxCount") ? p.path("maxCount").asInt() : null,
                    labels,
                    taints,
                    p.has("availabilityZones") ? textList(p.path("availabilityZones")) : null);
        }
    }

    private List<PoolRequest> parsePools(JsonNode poolsNode) {
        List<PoolRequest> pools = new ArrayList<>();
        if (!poolsNode.isArray()) {
            return pools;
        }
        for (JsonNode p : poolsNode) {
            String name = p.path("name").asText("nodepool1");
            // The gate checks an agentPools/{name} URL; pools embedded in the cluster body get the same rule here.
            if (config.strictNames() && !AksArmSurface.AGENT_POOL_NAME.accepts(name)) {
                throw new AksRequestException(400, AksArmSurface.AGENT_POOL_NAME.code(),
                        AksArmSurface.AGENT_POOL_NAME.message(name));
            }
            pools.add(PoolRequest.parse(name, p));
        }
        return pools;
    }

    private static int defaultMaxPods(ManagedCluster cluster) {
        return AksNetwork.defaultMaxPods(cluster.getNetworkProfile());
    }

    private static void applyPoolRequest(AgentPoolProfile pool, PoolRequest request, boolean isNew,
                                         Version version, int defaultMaxPods) {
        pool.setCount(request.count() != null ? request.count() : isNew ? 1 : pool.getCount());
        pool.setVmSize(firstNonNull(request.vmSize(), pool.getVmSize(), DEFAULT_VM_SIZE));
        pool.setOsType(firstNonNull(request.osType(), pool.getOsType(), "Linux"));
        pool.setOsSku(firstNonNull(request.osSku(), pool.getOsSku(), "Ubuntu"));
        pool.setMode(firstNonNull(request.mode(), pool.getMode(), "System"));
        pool.setMaxPods(firstNonNull(request.maxPods(), pool.getMaxPods(), defaultMaxPods));
        if (request.enableAutoScaling() != null) {
            pool.setEnableAutoScaling(request.enableAutoScaling());
        }
        if (request.minCount() != null) {
            pool.setMinCount(request.minCount());
        }
        if (request.maxCount() != null) {
            pool.setMaxCount(request.maxCount());
        }
        if (request.nodeLabels() != null) {
            pool.setNodeLabels(request.nodeLabels());
        }
        if (request.nodeTaints() != null) {
            pool.setNodeTaints(request.nodeTaints());
        }
        if (request.availabilityZones() != null) {
            pool.setAvailabilityZones(request.availabilityZones());
        }
        String previousVersion = pool.getCurrentOrchestratorVersion();
        boolean versionChanged = previousVersion != null && !previousVersion.equals(version.toString());
        pool.setOrchestratorVersion(request.orchestratorVersion() != null ? request.orchestratorVersion()
                : pool.getOrchestratorVersion() != null && !versionChanged
                ? pool.getOrchestratorVersion() : version.minorVersion());
        pool.setCurrentOrchestratorVersion(version.toString());
        pool.setProvisioningState(isNew ? "Creating" : versionChanged ? "Upgrading" : "Updating");
    }

    // ── Cluster read, delete, tags, power ──────────────────────────────────────

    private Response handleGet(String sub, String rg, String clusterName) {
        return getCluster(clusterId(sub, rg, clusterName))
                .map(c -> Response.ok(toArmResponse(c)).type("application/json").build())
                .orElseGet(() -> notFound("Managed cluster '" + clusterName + "' not found."));
    }

    /**
     * ManagedClusters_Delete is a long-running operation (final-state-via azure-async-operation) declaring 202,
     * with Azure-AsyncOperation, Location and Retry-After, and 204 when the cluster does not exist. The cluster
     * shows Deleting until its containers are gone.
     */
    private Response handleDelete(AzureRequest req, String sub, String rg, String clusterName) {
        ArmResourceId id = clusterId(sub, rg, clusterName);
        Optional<ManagedCluster> found = getCluster(id);
        if (found.isEmpty()) {
            return Response.noContent().build();
        }
        ManagedCluster cluster = found.get();
        if (config.services().aks().mocked()) {
            store.delete(id);
            AksOperations.Operation op = operations.completed(Kind.DELETE, id.canonicalKey(),
                    cluster.getInstanceId());
            return accepted(req, sub, cluster.getLocation(), op, true);
        }
        store.update(id, current -> {
            current.setProvisioningState("Deleting");
            current.setGeneration(current.getGeneration() + 1);
            return current;
        });
        AksOperations.Operation op = operations.start(Kind.DELETE, id.canonicalKey(), cluster.getInstanceId());
        reconciler.submit(id);
        return accepted(req, sub, cluster.getLocation(), op, true);
    }

    private Response handlePower(AzureRequest req, String sub, String rg, String clusterName, boolean start) {
        ArmResourceId id = clusterId(sub, rg, clusterName);
        Optional<ManagedCluster> found = getCluster(id);
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        String target = start ? "Running" : "Stopped";
        boolean mocked = config.services().aks().mocked();
        ManagedCluster cluster = store.update(id, current -> {
            current.setPowerState(target);
            current.setGeneration(current.getGeneration() + 1);
            current.setProvisioningState(mocked ? "Succeeded" : start ? "Starting" : "Stopping");
            current.setLastError(null);
            for (AgentPoolProfile pool : current.pools()) {
                pool.setPowerState(target);
                if (!mocked) {
                    pool.setProvisioningState(start ? "Starting" : "Stopping");
                } else {
                    pool.setProvisioningState("Succeeded");
                }
            }
            return current;
        }).orElseThrow().value();
        Kind kind = start ? Kind.START : Kind.STOP;
        AksOperations.Operation op = mocked
                ? operations.completed(kind, id.canonicalKey(), cluster.getInstanceId())
                : operations.start(kind, id.canonicalKey(), cluster.getInstanceId());
        if (!mocked) {
            reconciler.submit(id);
        }
        return accepted(req, sub, cluster.getLocation(), op, false);
    }

    /**
     * 202 for an accepted long-running operation. Delete and agent pool delete also carry Azure-AsyncOperation;
     * start and stop are final-state-via location and only carry Location.
     */
    private Response accepted(AzureRequest req, String sub, String location, AksOperations.Operation op,
                              boolean withAsyncOperation) {
        Response.ResponseBuilder response = Response.status(202)
                .header("Location", operationUrl(req, sub, location, op.id(), "operationresults"))
                .header("Retry-After", "1");
        if (withAsyncOperation) {
            response.header("Azure-AsyncOperation", operationUrl(req, sub, location, op.id(), "operations"));
        }
        return response.build();
    }

    private String operationUrl(AzureRequest req, String sub, String location, String operationId, String kind) {
        String loc = (location == null || location.isBlank()) ? defaults.defaultLocation(req.scope()) : location;
        // Built from the caller's own scheme and host, like the ACI monitors: a client that reached
        // us over TLS refuses to send credentials to a downgraded http:// poll URL.
        String apiVersion = req.queryParams() == null ? null : req.queryParams().get("api-version");
        String query = apiVersion == null || apiVersion.isBlank() ? "" : "?api-version=" + apiVersion;
        return RequestUrls.resolveBaseUrl(req, config) + "/subscriptions/" + sub + PROVIDER_PREFIX + "locations/"
                + loc + "/" + kind + "/" + operationId + query;
    }

    private Optional<AksOperations.Status> operationStatus(String operationId) {
        return operations.status(operationId, key -> findByKey(key).map(found -> new AksOperations.ResourceState(
                found.getInstanceId(), found.getProvisioningState(), found.getLastError())));
    }

    private Response operationStatusResponse(AzureRequest req, String location, String operationId) {
        Optional<AksOperations.Status> status = operationStatus(operationId);
        if (status.isEmpty()) {
            return notFound("The operation '" + operationId + "' was not found.");
        }
        return Response.ok(operationBody(req, location, status.get())).type("application/json").build();
    }

    /**
     * The Location monitor: 202 while the operation runs, 204 once it succeeded, and the error once it failed,
     * so a final-state-via-location poller sees each outcome.
     */
    private Response operationResultResponse(AzureRequest req, String location, String operationId) {
        Optional<AksOperations.Status> status = operationStatus(operationId);
        if (status.isEmpty()) {
            return notFound("The operation '" + operationId + "' was not found.");
        }
        return switch (status.get().status()) {
            case "InProgress" -> Response.status(202)
                    .header("Location", RequestUrls.resolveBaseUrl(req, config) + req.resourcePath()
                            + queryString(req))
                    .header("Retry-After", "1")
                    .build();
            case "Succeeded" -> Response.noContent().build();
            default -> ArmErrors.error(400, status.get().status().equals("Canceled") ? "OperationCanceled"
                    : "OperationFailed", status.get().errorMessage() == null
                    ? "The operation was " + status.get().status().toLowerCase() + "."
                    : status.get().errorMessage());
        };
    }

    private static String queryString(AzureRequest req) {
        String apiVersion = req.queryParams() == null ? null : req.queryParams().get("api-version");
        return apiVersion == null || apiVersion.isBlank() ? "" : "?api-version=" + apiVersion;
    }

    private static Map<String, Object> operationBody(AzureRequest req, String location,
                                                     AksOperations.Status status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "/subscriptions/" + ArmPaths.segmentAfter(req.resourcePath(), "subscriptions", "default")
                + PROVIDER_PREFIX + "locations/" + location + "/operations/" + status.id());
        body.put("name", status.id());
        body.put("status", status.status());
        body.put("startTime", status.startTime().toString());
        if (status.endTime() != null) {
            body.put("endTime", status.endTime().toString());
        }
        if ("Failed".equals(status.status())) {
            body.put("error", Map.of("code", "OperationFailed", "message", status.errorMessage()));
        }
        return body;
    }

    private Response handleUpdateTags(String sub, String rg, String clusterName, AzureRequest req) {
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        JsonNode body = readBody(req.bodyStream());
        return store.update(clusterId(sub, rg, clusterName), cluster -> {
            cluster.setTags(parseTags(body.path("tags")));
            return cluster;
        }).map(stored -> Response.ok(toArmResponse(stored.value())).type("application/json").build())
                .orElseGet(() -> notFound("Managed cluster '" + clusterName + "' not found."));
    }

    private Response handleListByResourceGroup(String fullPath) {
        return list(new ArmScope(extractSubscriptionId(fullPath), extractResourceGroup(fullPath)));
    }

    private Response handleListSubscription(String fullPath) {
        return list(new ArmScope(extractSubscriptionId(fullPath), null));
    }

    private Response list(ArmScope scope) {
        List<Map<String, Object>> items = new ArrayList<>();
        store.list(scope).forEach(stored -> items.add(toArmResponse(stored.value())));
        return Response.ok(Map.of("value", items)).type("application/json").build();
    }

    // ── Versions and upgrade profiles ──────────────────────────────────────────

    private Response kubernetesVersionsResponse() {
        List<Map<String, Object>> values = new ArrayList<>();
        for (Version version : versions.supported()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("version", version.minorVersion());
            entry.put("capabilities", Map.of("supportPlan", List.of("KubernetesOfficial")));
            if (version.equals(versions.defaultVersion())) {
                entry.put("isDefault", true);
            }
            entry.put("patchVersions", Map.of(version.toString(), Map.of("upgrades",
                    versions.upgradesFrom(version).stream().map(Version::toString).toList())));
            values.add(entry);
        }
        return Response.ok(Map.of("values", values)).type("application/json").build();
    }

    private Response handleClusterUpgradeProfile(String sub, String rg, String clusterName) {
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        ManagedCluster cluster = found.get();
        Version current = currentVersion(cluster);
        List<Map<String, Object>> upgrades = upgradeList(versions.upgradesFrom(current));
        Map<String, Object> controlPlane = new LinkedHashMap<>();
        controlPlane.put("name", "master");
        controlPlane.put("kubernetesVersion", current.toString());
        controlPlane.put("osType", "Linux");
        controlPlane.put("upgrades", upgrades);
        List<Map<String, Object>> pools = new ArrayList<>();
        for (AgentPoolProfile pool : cluster.pools()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", pool.getName());
            entry.put("kubernetesVersion", pool.getCurrentOrchestratorVersion());
            entry.put("osType", pool.getOsType());
            entry.put("upgrades", upgrades);
            pools.add(entry);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("controlPlaneProfile", controlPlane);
        properties.put("agentPoolProfiles", pools);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cluster.armId() + "/upgradeprofiles/default");
        out.put("name", "default");
        out.put("type", TYPE + "/upgradeprofiles");
        out.put("properties", properties);
        return Response.ok(out).type("application/json").build();
    }

    private Response handleAgentPoolUpgradeProfile(String sub, String rg, String clusterName, String poolName) {
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        ManagedCluster cluster = found.get();
        Optional<AgentPoolProfile> pool = cluster.pool(poolName);
        if (pool.isEmpty()) {
            return notFound("Agent pool '" + poolName + "' not found.");
        }
        Version controlPlane = currentVersion(cluster);
        Version poolVersion = Version.parse(pool.get().getCurrentOrchestratorVersion()).orElse(controlPlane);
        List<Version> upgrades = versions.supported().stream()
                .filter(v -> v.compareTo(poolVersion) > 0 && v.compareTo(controlPlane) <= 0)
                .toList();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kubernetesVersion", poolVersion.toString());
        properties.put("osType", pool.get().getOsType());
        properties.put("upgrades", upgradeList(upgrades));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cluster.armId() + "/agentPools/" + poolName + "/upgradeprofiles/default");
        out.put("name", "default");
        out.put("type", AGENT_POOL_TYPE + "/upgradeProfiles");
        out.put("properties", properties);
        return Response.ok(out).type("application/json").build();
    }

    private Response handleAvailableAgentPoolVersions(String sub, String rg, String clusterName) {
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        List<Map<String, Object>> poolVersions = new ArrayList<>();
        for (Version version : versions.supported()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            if (version.equals(versions.defaultVersion())) {
                entry.put("default", true);
            }
            entry.put("kubernetesVersion", version.toString());
            poolVersions.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", found.get().armId() + "/availableagentpoolversions");
        out.put("name", "default");
        out.put("type", TYPE + "/availableAgentpoolVersions");
        out.put("properties", Map.of("agentPoolVersions", poolVersions));
        return Response.ok(out).type("application/json").build();
    }

    private static List<Map<String, Object>> upgradeList(List<Version> upgrades) {
        return upgrades.stream().map(v -> Map.<String, Object>of("kubernetesVersion", v.toString())).toList();
    }

    // ── Credentials ────────────────────────────────────────────────────────────

    /**
     * The kubeconfig of the cluster's user or admin credential. Without Microsoft Entra integration both carry a
     * cluster-admin client certificate, as on AKS; {@code disableLocalAccounts} refuses the admin one with the error
     * AKS returns.
     */
    private Response handleListCredentials(String sub, String rg, String clusterName, boolean admin) {
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        ManagedCluster cluster = found.get();
        if (admin && cluster.localAccountsDisabled()) {
            return ArmErrors.error(400, "BadRequest", "Getting static credential is not allowed because this"
                    + " cluster is set to disable local accounts.");
        }
        AksKubeconfig.Role role = admin ? AksKubeconfig.Role.ADMIN : AksKubeconfig.Role.USER;
        String kubeconfig;
        if (cluster.getCaData() != null && cluster.getClientCertificateData() != null
                && cluster.getClientKeyData() != null && cluster.getEndpoint() != null) {
            kubeconfig = AksKubeconfig.of(cluster, role);
        } else if (config.services().aks().mocked()) {
            kubeconfig = AksKubeconfig.mock(cluster, role);
        } else {
            return credentialsNotAvailable(cluster);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kubeconfigs", List.of(Map.of(
                "name", role.credentialName(),
                "value", kubeconfig)));
        return Response.ok(result).type("application/json").build();
    }

    /**
     * Real mode only: the k3s kubeconfig has not been extracted, because the cluster is still
     * {@code Creating}, it {@code Failed}, or extraction failed. Answering with a synthetic
     * kubeconfig would hand the caller a server and token that do not work, so report the
     * cluster state instead, with the code AKS uses for operations its current state forbids.
     */
    private static Response credentialsNotAvailable(ManagedCluster cluster) {
        return ArmErrors.error(409, "OperationNotAllowed",
                "Managed cluster '" + cluster.getName() + "' is in Provisioning State("
                        + cluster.getProvisioningState() + "), listing credentials cannot be performed"
                        + " because the cluster has no kubeconfig yet.");
    }

    // ── Agent pools ────────────────────────────────────────────────────────────

    private Response handleListAgentPools(String sub, String rg, String clusterName) {
        Optional<ManagedCluster> found = getCluster(clusterId(sub, rg, clusterName));
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        List<Map<String, Object>> pools = new ArrayList<>();
        for (AgentPoolProfile p : found.get().pools()) {
            pools.add(toAgentPoolArmResponse(found.get(), p));
        }
        return Response.ok(Map.of("value", pools)).type("application/json").build();
    }

    private Response handleAgentPool(AzureRequest req, String method, String sub, String rg, String clusterName,
                                     String poolName) {
        ArmResourceId id = clusterId(sub, rg, clusterName);
        Optional<ManagedCluster> found = getCluster(id);
        if (found.isEmpty()) {
            return notFound("Managed cluster '" + clusterName + "' not found.");
        }
        return switch (method) {
            case "GET" -> found.get().pool(poolName)
                    .map(p -> Response.ok(toAgentPoolArmResponse(found.get(), p)).type("application/json").build())
                    .orElseGet(() -> notFound("Agent pool '" + poolName + "' not found."));
            case "PUT" -> putAgentPool(req, id, sub, poolName);
            case "DELETE" -> deleteAgentPool(req, id, sub, poolName);
            default -> methodNotAllowed();
        };
    }

    private Response putAgentPool(AzureRequest req, ArmResourceId id, String sub, String poolName) {
        JsonNode props = readBody(req.bodyStream()).path("properties");
        boolean mocked = config.services().aks().mocked();
        AtomicReference<Boolean> created = new AtomicReference<>(false);
        ManagedCluster cluster;
        try {
            PoolRequest request = PoolRequest.parse(poolName, props);
            cluster = store.update(id, current -> {
                Optional<AgentPoolProfile> existing = current.pool(poolName);
                created.set(existing.isEmpty());
                Version controlPlane = currentVersion(current);
                Version version = poolVersionFor(request, existing.orElse(null), controlPlane, false);
                AgentPoolProfile pool = existing.orElseGet(() -> {
                    AgentPoolProfile np = new AgentPoolProfile();
                    np.setName(poolName);
                    current.pools().add(np);
                    return np;
                });
                int previousCount = pool.getCount();
                applyPoolRequest(pool, request, existing.isEmpty(), version, defaultMaxPods(current));
                if ("Updating".equals(pool.getProvisioningState()) && pool.getCount() != previousCount) {
                    pool.setProvisioningState("Scaling");
                }
                pool.setPowerState(current.getPowerState());
                current.setGeneration(current.getGeneration() + 1);
                if (mocked) {
                    settleMocked(current);
                } else if ("Succeeded".equals(current.getProvisioningState())
                        || "Failed".equals(current.getProvisioningState())) {
                    current.setProvisioningState("Updating");
                }
                return current;
            }).orElseThrow().value();
        } catch (AksRequestException e) {
            return e.response();
        }
        Kind kind = created.get() ? Kind.CREATE : Kind.UPDATE;
        AksOperations.Operation op = mocked
                ? operations.completed(kind, id.canonicalKey(), cluster.getInstanceId())
                : operations.start(kind, id.canonicalKey(), cluster.getInstanceId());
        if (!mocked) {
            reconciler.submit(id);
        }
        return Response.status(created.get() ? 201 : 200)
                .entity(toAgentPoolArmResponse(cluster, cluster.pool(poolName).orElseThrow()))
                .type("application/json")
                .header("Azure-AsyncOperation", operationUrl(req, sub, cluster.getLocation(), op.id(), "operations"))
                .header("Retry-After", "1")
                .build();
    }

    private Response deleteAgentPool(AzureRequest req, ArmResourceId id, String sub, String poolName) {
        boolean mocked = config.services().aks().mocked();
        AtomicReference<Boolean> present = new AtomicReference<>(false);
        ManagedCluster cluster = store.update(id, current -> {
            current.pool(poolName).ifPresent(pool -> {
                present.set(true);
                pool.setProvisioningState("Deleting");
                current.setGeneration(current.getGeneration() + 1);
                if (mocked) {
                    current.pools().remove(pool);
                } else if ("Succeeded".equals(current.getProvisioningState())
                        || "Failed".equals(current.getProvisioningState())) {
                    current.setProvisioningState("Updating");
                }
            });
            return current;
        }).orElseThrow().value();
        if (!present.get()) {
            return Response.noContent().build();
        }
        AksOperations.Operation op = mocked
                ? operations.completed(Kind.DELETE, id.canonicalKey(), cluster.getInstanceId())
                : operations.start(Kind.UPDATE, id.canonicalKey(), cluster.getInstanceId());
        if (!mocked) {
            reconciler.submit(id);
        }
        return accepted(req, sub, cluster.getLocation(), op, true);
    }

    // ── Storage helpers ────────────────────────────────────────────────────────

    private Optional<ManagedCluster> getCluster(ArmResourceId id) {
        return store.get(id).map(ArmResourceStore.Stored::value);
    }

    private Optional<ManagedCluster> findByKey(String canonicalKey) {
        return store.all().stream()
                .filter(stored -> stored.id().canonicalKey().equals(canonicalKey))
                .map(ArmResourceStore.Stored::value)
                .findFirst();
    }

    private List<ManagedCluster> scanAll() {
        return store.all().stream().map(ArmResourceStore.Stored::value).toList();
    }

    private static ArmResourceId clusterId(String sub, String rg, String clusterName) {
        return ArmResourceId.parse(String.format(
                "/subscriptions/%s/resourceGroups/%s/providers/Microsoft.ContainerService/managedClusters/%s",
                sub, rg, clusterName)).orElseThrow();
    }

    // ── ARM response builders ──────────────────────────────────────────────────

    private Map<String, Object> toArmResponse(ManagedCluster cluster) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("provisioningState", cluster.getProvisioningState());
        props.put("powerState", Map.of("code", cluster.getPowerState() == null ? "Running" : cluster.getPowerState()));
        props.put("kubernetesVersion", cluster.getKubernetesVersion());
        props.put("currentKubernetesVersion", cluster.getCurrentKubernetesVersion() != null
                ? cluster.getCurrentKubernetesVersion() : cluster.getKubernetesVersion());
        props.put("dnsPrefix", cluster.getDnsPrefix());
        props.put("fqdn", cluster.getFqdn());
        props.put("enableRBAC", true);
        props.put("supportPlan", "KubernetesOfficial");
        props.put("maxAgentPools", 100);
        props.put("nodeResourceGroup", cluster.getNodeResourceGroup() != null ? cluster.getNodeResourceGroup()
                : "MC_" + cluster.getResourceGroup() + "_" + cluster.getName() + "_" + cluster.getLocation());
        if (cluster.getDisableLocalAccounts() != null) {
            props.put("disableLocalAccounts", cluster.getDisableLocalAccounts());
        }
        if (cluster.getNetworkProfile() != null) {
            props.put("networkProfile", networkProfileProperties(cluster.getNetworkProfile()));
        }
        if (cluster.getEchoedProperties() != null) {
            cluster.getEchoedProperties().forEach(props::putIfAbsent);
        }
        List<Map<String, Object>> poolsOut = new ArrayList<>();
        for (AgentPoolProfile p : cluster.pools()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getName());
            pm.putAll(agentPoolProperties(p));
            poolsOut.add(pm);
        }
        props.put("agentPoolProfiles", poolsOut);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cluster.armId());
        out.put("name", cluster.getName());
        out.put("type", TYPE);
        out.put("location", cluster.getLocation());
        if (cluster.getTags() != null && !cluster.getTags().isEmpty()) {
            out.put("tags", cluster.getTags());
        }
        out.put("properties", props);
        return out;
    }

    private static Map<String, Object> networkProfileProperties(AksModels.NetworkProfile profile) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("networkPlugin", profile.getNetworkPlugin());
        if (profile.getNetworkPluginMode() != null) {
            out.put("networkPluginMode", profile.getNetworkPluginMode());
        }
        if (profile.getNetworkPolicy() != null) {
            out.put("networkPolicy", profile.getNetworkPolicy());
        }
        if (profile.getNetworkDataplane() != null) {
            out.put("networkDataplane", profile.getNetworkDataplane());
        }
        if (profile.getPodCidr() != null) {
            out.put("podCidr", profile.getPodCidr());
            out.put("podCidrs", List.of(profile.getPodCidr()));
        }
        out.put("serviceCidr", profile.getServiceCidr());
        out.put("serviceCidrs", List.of(profile.getServiceCidr()));
        out.put("dnsServiceIP", profile.getDnsServiceIP());
        out.put("outboundType", profile.getOutboundType());
        out.put("loadBalancerSku", profile.getLoadBalancerSku());
        out.put("ipFamilies", List.of("IPv4"));
        return out;
    }

    private static Map<String, Object> agentPoolProperties(AgentPoolProfile pool) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("count", pool.getCount());
        props.put("vmSize", pool.getVmSize());
        props.put("maxPods", pool.getMaxPods() != null ? pool.getMaxPods() : AksNetwork.LEGACY_MAX_PODS);
        props.put("type", "VirtualMachineScaleSets");
        props.put("osType", pool.getOsType());
        props.put("osSKU", pool.getOsSku() != null ? pool.getOsSku() : "Ubuntu");
        props.put("mode", pool.getMode());
        if (pool.getOrchestratorVersion() != null) {
            props.put("orchestratorVersion", pool.getOrchestratorVersion());
        }
        if (pool.getCurrentOrchestratorVersion() != null) {
            props.put("currentOrchestratorVersion", pool.getCurrentOrchestratorVersion());
        }
        if (pool.getEnableAutoScaling() != null) {
            props.put("enableAutoScaling", pool.getEnableAutoScaling());
        }
        if (pool.getMinCount() != null) {
            props.put("minCount", pool.getMinCount());
        }
        if (pool.getMaxCount() != null) {
            props.put("maxCount", pool.getMaxCount());
        }
        if (pool.getNodeLabels() != null) {
            props.put("nodeLabels", pool.getNodeLabels());
        }
        if (pool.getNodeTaints() != null) {
            props.put("nodeTaints", pool.getNodeTaints());
        }
        if (pool.getAvailabilityZones() != null) {
            props.put("availabilityZones", pool.getAvailabilityZones());
        }
        props.put("provisioningState", pool.getProvisioningState());
        props.put("powerState", Map.of("code", pool.getPowerState() == null ? "Running" : pool.getPowerState()));
        return props;
    }

    private Map<String, Object> toAgentPoolArmResponse(ManagedCluster cluster, AgentPoolProfile pool) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cluster.armId() + "/agentPools/" + pool.getName());
        out.put("name", pool.getName());
        out.put("type", AGENT_POOL_TYPE);
        out.put("properties", agentPoolProperties(pool));
        return out;
    }

    /**
     * Request objects AKS accepts that have no runtime meaning here (Entra, OIDC issuer, security, addons...),
     * stored and echoed so clients that read them back see what they sent.
     */
    private static Map<String, Object> echoedProperties(Map<String, Object> previous, JsonNode props) {
        Map<String, Object> echoed = previous == null ? new LinkedHashMap<>() : new LinkedHashMap<>(previous);
        for (String field : List.of("aadProfile", "oidcIssuerProfile", "securityProfile", "apiServerAccessProfile",
                "addonProfiles", "autoUpgradeProfile", "autoScalerProfile", "linuxProfile", "windowsProfile",
                "workloadAutoScalerProfile", "storageProfile", "ingressProfile", "azureMonitorProfile",
                "identityProfile", "httpProxyConfig")) {
            if (props.has(field)) {
                echoed.put(field, MAPPER.convertValue(props.get(field), Object.class));
            }
        }
        if (props.has("servicePrincipalProfile")) {
            Map<String, Object> principal = new LinkedHashMap<>();
            principal.put("clientId", props.path("servicePrincipalProfile").path("clientId").asText());
            echoed.put("servicePrincipalProfile", principal);
        }
        return echoed;
    }

    // ── Path parsing helpers ───────────────────────────────────────────────────

    private static String extractAksPath(String fullPath) {
        if (fullPath == null) { return ""; }
        return ArmPaths.afterProvider(fullPath, "Microsoft.ContainerService", fullPath);
    }

    private static String extractSubscriptionId(String fullPath) {
        return ArmPaths.segmentAfter(fullPath, "subscriptions", "default");
    }

    private static String extractResourceGroup(String fullPath) {
        return ArmPaths.resourceGroup(fullPath, "default");
    }

    private static String segment(String path, int index) {
        String[] parts = path.split("/");
        return index < parts.length ? parts[index] : "";
    }

    private static Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    private static List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private JsonNode readBody(InputStream stream) {
        try {
            if (stream == null || stream.available() == 0) { return MAPPER.createObjectNode(); }
            return MAPPER.readTree(stream);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    // ── Standard error responses ───────────────────────────────────────────────

    private static Response notFound(String message) {
        return ArmErrors.notFound(message);
    }

    private static Response methodNotAllowed() {
        return Response.status(405).entity(Map.of("error", "Method not allowed"))
                .type("application/json").build();
    }

    /**
     * Removes every cluster's containers, volumes and network, then wipes all AKS data. Used by
     * {@code POST /_admin/reset}: a record dropped without its containers leaves them running, and nothing
     * removes them later. {@code keepRunningOnShutdown} does not apply, since no record is left to reach it.
     */
    public void clear() {
        List<ManagedCluster> clusters = scanAll();
        store.clear();
        operations.clear();
        if (!config.services().aks().mocked()) {
            clusters.forEach(cluster -> {
                try {
                    clusterManager.removeCluster(cluster);
                } catch (Exception e) {
                    LOG.warnv("Error removing the containers of AKS cluster {0} during reset: {1}",
                            cluster.getName(), e.getMessage());
                }
            });
        }
    }

    // ── ResourceIndexContributor ────────────────────────────────────────────────

    @Override
    public boolean indexEnabled() {
        return config.services().aks().enabled();
    }

    @Override
    public List<Map<String, Object>> listRgResources(String sub, String rg) {
        return indexEntries(new ArmScope(sub, rg));
    }

    @Override
    public List<Map<String, Object>> listSubscriptionResources(String sub) {
        return indexEntries(new ArmScope(sub, null));
    }

    private List<Map<String, Object>> indexEntries(ArmScope scope) {
        return store.list(scope).stream()
                .map(ArmResourceStore.Stored::value)
                .map(cluster -> ArmResources.indexEntry(cluster.armId(), cluster.getName(), TYPE,
                        cluster.getLocation(), cluster.getTags()))
                .toList();
    }
}
