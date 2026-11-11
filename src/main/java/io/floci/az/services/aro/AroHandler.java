package io.floci.az.services.aro;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.RequestUrls;
import io.floci.az.core.Resettable;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmPaths;
import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.core.arm.ArmResources;
import io.floci.az.core.arm.ArmScope;
import io.floci.az.core.arm.ResourceIndexContributor;
import io.floci.az.core.cloud.CloudDefaults;
import io.floci.az.core.storage.StorageFactory;
import io.floci.az.services.aro.AroModels.OpenShiftCluster;
import io.floci.az.services.aro.AroProperties.InvalidProperty;
import io.floci.az.services.aro.oauth.OAuthToken;
import io.floci.az.services.aro.oauth.OAuthTokens;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Azure Red Hat OpenShift ({@code Microsoft.RedHatOpenShift}) management-plane requests, after the 2025-07-25 spec
 * and the ARO resource provider's frontend (Azure/ARO-RP {@code pkg/frontend}).
 *
 * <pre>
 *   GET    /subscriptions/{sub}/providers/Microsoft.RedHatOpenShift/openShiftClusters
 *   GET    /subscriptions/{sub}/resourceGroups/{rg}/providers/Microsoft.RedHatOpenShift/openShiftClusters
 *   GET    .../openShiftClusters/{name}
 *   PUT    .../openShiftClusters/{name}                         201 or 200 + Azure-AsyncOperation
 *   PATCH  .../openShiftClusters/{name}                         200 + Azure-AsyncOperation
 *   DELETE .../openShiftClusters/{name}                         202 + Location + Azure-AsyncOperation, 204 if absent
 *   POST   .../openShiftClusters/{name}/listCredentials
 *   POST   .../openShiftClusters/{name}/listAdminCredentials
 *   GET    /subscriptions/{sub}/providers/Microsoft.RedHatOpenShift/locations/{loc}/openShiftVersions[/{version}]
 *   GET    /subscriptions/{sub}/providers/Microsoft.RedHatOpenShift/locations/{loc}/operationsstatus/{id}
 *   GET    /subscriptions/{sub}/providers/Microsoft.RedHatOpenShift/locations/{loc}/operationresults/{id}
 * </pre>
 *
 * <p>An operation's status is the state of the cluster it changes: {@code Creating}, {@code Updating} or
 * {@code Deleting} while it runs, then {@code Succeeded} or {@code Failed}, as ARO reports it.</p>
 */
@ApplicationScoped
public class AroHandler implements AzureServiceHandler, Resettable, ResourceIndexContributor {

    private static final Logger LOG = Logger.getLogger(AroHandler.class);

    static final String TYPE = AroArmSurface.NAMESPACE + "/openShiftClusters";
    static final String VERSION_TYPE = AroArmSurface.NAMESPACE + "/OpenShiftVersion";
    private static final String CREATING = "Creating";
    private static final String UPDATING = "Updating";
    private static final String DELETING = "Deleting";
    private static final String SUCCEEDED = "Succeeded";
    private static final String FAILED = "Failed";
    private static final Set<String> TERMINAL = Set.of(SUCCEEDED, FAILED, "Canceled");
    private static final int MAX_OPERATIONS = 1000;
    private static final String INFRA_ID_ALPHABET = "bcdfghjklmnpqrstvwxz2456789";
    private static final String KUBEADMIN_ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHIJKLMNPQRSTUVWXYZ23456789";

    private static final Pattern OPERATION = Pattern.compile("(?i)locations/([^/]+)/(operationsstatus|operationresults)/([^/]+)");
    private static final Pattern VERSIONS = Pattern.compile("(?i)locations/([^/]+)/openShiftVersions(?:/([^/]+))?");
    private static final Pattern CLUSTERS = Pattern.compile("(?i)openShiftClusters");
    private static final Pattern CLUSTER = Pattern.compile("(?i)openShiftClusters/([^/]+)(?:/([^/]+))?");

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmulatorConfig config;
    private final AroClusterManager clusterManager;
    private final AroProxyManager proxyManager;
    private final CloudDefaults defaults;
    private final ArmResourceStore<OpenShiftCluster> store;
    private final Map<String, AroOperation> operations = new LinkedHashMap<>();
    /** Why each cluster that is up is not finalized yet, by instance id, for the message of a timeout. */
    private final Map<String, String> finalizeErrors = new ConcurrentHashMap<>();
    /** Clusters whose console objects were applied since floci-az started, by instance id. */
    private final Set<String> consolesApplied = ConcurrentHashMap.newKeySet();
    /** Clusters whose console is not available yet, by instance id, with when to log that it is late. */
    private final Map<String, Instant> consoleDeadlines = new ConcurrentHashMap<>();
    private final Map<String, Instant> consoleChecks = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "aro-readiness-poller");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "aro-worker");
        thread.setDaemon(true);
        return thread;
    });

    /** One asynchronous operation: a create, an update or a delete of one cluster. */
    private static final class AroOperation {
        final String id;
        final String subscriptionId;
        final String location;
        final String clusterKey;
        final String initialStatus;
        final Instant startTime = Instant.now();
        String status;
        Instant endTime;
        String errorCode;
        String errorMessage;

        AroOperation(String id, String subscriptionId, String location, String clusterKey, String initialStatus) {
            this.id = id;
            this.subscriptionId = subscriptionId;
            this.location = location;
            this.clusterKey = clusterKey;
            this.initialStatus = initialStatus;
            this.status = initialStatus;
        }

        boolean done() {
            return TERMINAL.contains(status);
        }
    }

    @Inject
    public AroHandler(EmulatorConfig config, AroClusterManager clusterManager, AroProxyManager proxyManager,
                      StorageFactory storageFactory, CloudDefaults defaults) {
        this.config = config;
        this.clusterManager = clusterManager;
        this.proxyManager = proxyManager;
        this.defaults = defaults;
        this.store = ArmResourceStore.create(storageFactory, "aro", OpenShiftCluster.class, OpenShiftCluster::armId,
                key -> false);
    }

    void onStart(@Observes StartupEvent event) {
        if (mocked() || !config.services().aro().enabled()) {
            return;
        }
        poller.scheduleWithFixedDelay(this::pollClusters, 2, 3, TimeUnit.SECONDS);
        for (OpenShiftCluster cluster : scanAll()) {
            workers.submit(() -> restore(cluster));
        }
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
        workers.shutdownNow();
        if (mocked()) {
            return;
        }
        proxyManager.stopAll();
        for (OpenShiftCluster cluster : scanAll()) {
            try {
                clusterManager.stopCluster(cluster);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping ARO cluster {0}: {1}", cluster.getName(), e.getMessage());
            }
        }
    }

    @Override
    public String getServiceType() {
        return "aro";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().aro().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider(AroArmSurface.NAMESPACE)
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        String path = req.resourcePath();
        String method = req.method().toUpperCase(Locale.ROOT);
        String tail = ArmPaths.afterProvider(path, AroArmSurface.NAMESPACE, "");
        String sub = req.scope() != null && req.scope().subscriptionId() != null
                ? req.scope().subscriptionId() : "default";
        Optional<String> resourceGroup = req.resourceGroup();
        LOG.debugv("AroHandler: {0} {1}", method, path);

        Matcher operation = OPERATION.matcher(tail);
        if (operation.matches() && "GET".equals(method)) {
            return operation.group(2).equalsIgnoreCase("operationsstatus")
                    ? operationStatus(sub, operation.group(3))
                    : operationResult(req, sub, operation.group(3));
        }
        Matcher versions = VERSIONS.matcher(tail);
        if (versions.matches() && "GET".equals(method)) {
            return versions.group(2) == null ? listVersions() : getVersion(req, versions.group(2));
        }
        if (CLUSTERS.matcher(tail).matches() && "GET".equals(method)) {
            return list(new ArmScope(sub, resourceGroup.orElse(null)));
        }
        Matcher cluster = CLUSTER.matcher(tail);
        if (cluster.matches() && resourceGroup.isPresent()) {
            String rg = resourceGroup.get();
            String name = cluster.group(1);
            String action = cluster.group(2);
            if (action != null) {
                if (!"POST".equals(method)) {
                    return methodNotAllowed();
                }
                return switch (action.toLowerCase(Locale.ROOT)) {
                    case "listcredentials" -> listCredentials(sub, rg, name);
                    case "listadmincredentials" -> listAdminCredentials(sub, rg, name);
                    default -> notFound(sub, rg, name);
                };
            }
            return switch (method) {
                case "GET" -> get(sub, rg, name);
                case "PUT" -> createOrUpdate(req, sub, rg, name, false);
                case "PATCH" -> createOrUpdate(req, sub, rg, name, true);
                case "DELETE" -> delete(req, sub, rg, name);
                default -> methodNotAllowed();
            };
        }
        return cloudError(404, "NotFound", null, "The requested path could not be found.");
    }

    // ── Clusters ─────────────────────────────────────────────────────────────

    private Response get(String sub, String rg, String name) {
        return find(sub, rg, name)
                .map(cluster -> json(200, toArm(cluster, true)))
                .orElseGet(() -> notFound(sub, rg, name));
    }

    private Response list(ArmScope scope) {
        List<Map<String, Object>> value = new ArrayList<>();
        store.list(scope).forEach(stored -> value.add(toArm(stored.value(), true)));
        return json(200, Map.of("value", value));
    }

    private Response createOrUpdate(AzureRequest req, String sub, String rg, String name, boolean patch) {
        Map<String, Object> body;
        try {
            body = readBody(req.bodyStream());
        } catch (IOException e) {
            return cloudError(400, "InvalidRequestContent", null,
                    "The request content was invalid and could not be deserialized: \"" + e.getMessage() + "\".");
        }
        AroVersions versions;
        try {
            versions = versions();
        } catch (IllegalArgumentException e) {
            return cloudError(500, "InternalServerError", null, e.getMessage());
        }
        ArmResourceId id = clusterId(sub, rg, name);
        synchronized (lock) {
            Optional<OpenShiftCluster> existing = store.get(id).map(ArmResourceStore.Stored::value);
            if (existing.isEmpty() && patch) {
                return notFound(sub, rg, name);
            }
            try {
                return existing.isPresent()
                        ? update(req, existing.get(), body, patch, versions)
                        : create(req, sub, rg, name, body, versions);
            } catch (InvalidProperty e) {
                return cloudError(400, e.code(), e.target(), e.getMessage());
            }
        }
    }

    private Response create(AzureRequest req, String sub, String rg, String name, Map<String, Object> body,
                            AroVersions versions) {
        String location = body.get("location") instanceof String l && !l.isBlank()
                ? l : defaults.defaultLocation(req.scope());
        Map<String, Object> properties = AroProperties.forCreate(AroProperties.cast(body.get("properties")),
                new AroProperties.Context(sub, rg, location, versions));
        String version = String.valueOf(AroProperties.cast(properties.get("clusterProfile")).get("version"));

        OpenShiftCluster cluster = new OpenShiftCluster();
        cluster.setInstanceId(UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        cluster.setSubscriptionId(sub);
        cluster.setResourceGroup(rg);
        cluster.setName(name);
        cluster.setLocation(location);
        cluster.setTags(tags(body.get("tags")));
        cluster.setIdentity(body.get("identity") instanceof Map<?, ?> ? AroProperties.cast(body.get("identity")) : null);
        cluster.setProperties(properties);
        cluster.setInfraId(infraId(name));
        cluster.setClusterUuid(AroProperties.newClusterUuid());
        cluster.setKubeadminPassword(kubeadminPassword());
        cluster.setOauthEnabled(!mocked() && config.services().aro().oauth().enabled());
        cluster.setConsoleEnabled(!mocked() && consoleEnabled());
        if (cluster.isConsoleEnabled() && cluster.isOauthEnabled()) {
            cluster.setConsoleClientSecret(OAuthTokens.newToken(RANDOM).substring(OAuthTokens.PREFIX.length()));
        }
        cluster.setImageTag(versions.resolve(version).orElseThrow().tag());
        cluster.setCreatedAt(Instant.now());

        AroOperation operation = newOperation(cluster, CREATING);
        cluster.setAsyncOperationId(operation.id);
        if (mocked()) {
            cluster.setPublicIp("127.0.0.1");
            cluster.setProvisioningState(SUCCEEDED);
            complete(operation, SUCCEEDED, null, null);
        } else {
            cluster.setProvisioningState(CREATING);
            cluster.setReadyDeadline(Instant.now().plusSeconds(config.services().aro().readyTimeoutSeconds()));
        }
        store.put(cluster);
        if (!mocked()) {
            OpenShiftCluster snapshot = copy(cluster);
            workers.submit(() -> start(snapshot));
        }
        return accepted(req, 201, cluster, operation);
    }

    private Response update(AzureRequest req, OpenShiftCluster cluster, Map<String, Object> body, boolean patch,
                            AroVersions versions) {
        Optional<Response> refused = refuseUnlessTerminal(cluster);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (FAILED.equals(cluster.getProvisioningState())) {
            if (CREATING.equals(cluster.getFailedProvisioningState())) {
                return cloudError(400, "RequestNotAllowed", null,
                        "Request is not allowed on cluster whose creation failed. Delete the cluster.");
            }
            if (DELETING.equals(cluster.getFailedProvisioningState())) {
                return cloudError(400, "RequestNotAllowed", null,
                        "Request is not allowed on cluster whose deletion failed. Delete the cluster.");
            }
        }
        if (body.get("location") instanceof String location && !sameLocation(location, cluster.getLocation())) {
            return cloudError(400, "InvalidParameter", "location",
                    "The provided location '" + location + "' is invalid.");
        }
        Map<String, Object> requested = AroProperties.cast(body.get("properties"));
        cluster.setProperties(AroProperties.forUpdate(cluster.getProperties(), requested, patch,
                new AroProperties.Context(cluster.getSubscriptionId(), cluster.getResourceGroup(),
                        cluster.getLocation(), versions)));
        if (!patch || body.containsKey("tags")) {
            cluster.setTags(tags(body.get("tags")));
        }
        if (body.get("identity") instanceof Map<?, ?>) {
            cluster.setIdentity(AroProperties.cast(body.get("identity")));
        }
        AroOperation operation = newOperation(cluster, UPDATING);
        cluster.setAsyncOperationId(operation.id);
        cluster.setFailedProvisioningState(null);
        if (mocked()) {
            cluster.setProvisioningState(SUCCEEDED);
            complete(operation, SUCCEEDED, null, null);
        } else {
            cluster.setProvisioningState(UPDATING);
        }
        store.put(cluster);
        return accepted(req, 200, cluster, operation);
    }

    private Response delete(AzureRequest req, String sub, String rg, String name) {
        ArmResourceId id = clusterId(sub, rg, name);
        AroOperation operation;
        OpenShiftCluster snapshot;
        synchronized (lock) {
            Optional<OpenShiftCluster> found = store.get(id).map(ArmResourceStore.Stored::value);
            if (found.isEmpty()) {
                return Response.noContent().build();
            }
            OpenShiftCluster cluster = found.get();
            Optional<Response> refused = refuseUnlessTerminal(cluster);
            if (refused.isPresent()) {
                return refused.get();
            }
            operation = newOperation(cluster, DELETING);
            cluster.setAsyncOperationId(operation.id);
            cluster.setProvisioningState(DELETING);
            if (mocked()) {
                store.delete(id);
                complete(operation, SUCCEEDED, null, null);
                snapshot = null;
            } else {
                store.put(cluster);
                snapshot = copy(cluster);
            }
        }
        if (snapshot != null) {
            workers.submit(() -> remove(snapshot));
        }
        String base = operationBase(req, sub, operation.location);
        String query = apiVersionQuery(req);
        return Response.status(202)
                .header("Location", base + "/operationresults/" + operation.id + query)
                .header("Azure-AsyncOperation", base + "/operationsstatus/" + operation.id + query)
                .header("Retry-After", "1")
                .build();
    }

    private Response listCredentials(String sub, String rg, String name) {
        Optional<OpenShiftCluster> found = find(sub, rg, name);
        if (found.isEmpty()) {
            return notFound(sub, rg, name);
        }
        Optional<Response> refused = refuseCredentials(found.get());
        if (refused.isPresent()) {
            return refused.get();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kubeadminUsername", "kubeadmin");
        body.put("kubeadminPassword", found.get().getKubeadminPassword());
        return json(200, body);
    }

    private Response listAdminCredentials(String sub, String rg, String name) {
        Optional<OpenShiftCluster> found = find(sub, rg, name);
        if (found.isEmpty()) {
            return notFound(sub, rg, name);
        }
        OpenShiftCluster cluster = found.get();
        Optional<Response> refused = refuseCredentials(cluster);
        if (refused.isPresent()) {
            return refused.get();
        }
        String kubeconfig = cluster.getKubeconfig();
        if (kubeconfig == null || kubeconfig.isBlank()) {
            if (!mocked()) {
                return cloudError(400, "RequestNotAllowed", null, "Request is not allowed in provisioningState '"
                        + cluster.getProvisioningState() + "'.");
            }
            kubeconfig = mockKubeconfig(cluster);
        }
        return json(200, Map.of("kubeconfig", kubeconfig));
    }

    /** ARO refuses credentials of a cluster being created or deleted, or whose creation or deletion failed. */
    private static Optional<Response> refuseCredentials(OpenShiftCluster cluster) {
        String state = cluster.getProvisioningState();
        String failedIn = cluster.getFailedProvisioningState();
        if (CREATING.equals(state) || DELETING.equals(state)
                || FAILED.equals(state) && (CREATING.equals(failedIn) || DELETING.equals(failedIn))) {
            return Optional.of(cloudError(400, "RequestNotAllowed", null,
                    "Request is not allowed in provisioningState '" + state + "'."));
        }
        return Optional.empty();
    }

    private static Optional<Response> refuseUnlessTerminal(OpenShiftCluster cluster) {
        if (TERMINAL.contains(cluster.getProvisioningState())) {
            return Optional.empty();
        }
        return Optional.of(cloudError(400, "RequestNotAllowed", null,
                "Request is not allowed in provisioningState '" + cluster.getProvisioningState() + "'."));
    }

    // ── Versions ─────────────────────────────────────────────────────────────

    private Response listVersions() {
        List<Map<String, Object>> value = new ArrayList<>();
        versions().advertised().forEach(version -> value.add(versionBody(version)));
        return json(200, Map.of("value", value));
    }

    private Response getVersion(AzureRequest req, String version) {
        if (!versions().advertised().contains(version)) {
            return cloudError(400, "ResourceNotFound", null, "The Resource openShiftVersion with version '" + version
                    + "' was not found in the namespace '" + AroArmSurface.NAMESPACE.toLowerCase(Locale.ROOT)
                    + "' for api version '" + apiVersion(req) + "'.");
        }
        return json(200, versionBody(version));
    }

    private static Map<String, Object> versionBody(String version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", version);
        body.put("type", VERSION_TYPE);
        body.put("properties", Map.of("version", version));
        return body;
    }

    private AroVersions versions() {
        return AroVersions.parse(config.services().aro().versions());
    }

    // ── Operations ───────────────────────────────────────────────────────────

    private Response operationStatus(String sub, String operationId) {
        AroOperation operation = operation(sub, operationId);
        if (operation == null) {
            return cloudError(404, "NotFound", null, "The entity was not found.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        synchronized (lock) {
            body.put("id", "/subscriptions/" + operation.subscriptionId + "/providers/" + AroArmSurface.NAMESPACE
                    + "/locations/" + operation.location + "/operationsstatus/" + operation.id);
            body.put("name", operation.id);
            body.put("status", operation.status);
            body.put("startTime", operation.startTime.toString());
            if (operation.endTime != null) {
                body.put("endTime", operation.endTime.toString());
            }
            if (operation.errorCode != null) {
                body.put("error", Map.of("code", operation.errorCode, "message", operation.errorMessage));
            }
        }
        return json(200, body);
    }

    private Response operationResult(AzureRequest req, String sub, String operationId) {
        AroOperation operation = operation(sub, operationId);
        if (operation == null) {
            return cloudError(404, "NotFound", null, "The entity was not found.");
        }
        boolean done;
        synchronized (lock) {
            done = operation.done();
        }
        if (!done) {
            return Response.status(202)
                    .header("Location", operationBase(req, sub, operation.location) + "/operationresults/"
                            + operation.id + apiVersionQuery(req))
                    .build();
        }
        if (DELETING.equals(operation.initialStatus)) {
            return Response.noContent().build();
        }
        return ArmResourceId.parse(operation.clusterKey)
                .flatMap(store::get)
                .map(stored -> json(200, toArm(stored.value(), false)))
                .orElseGet(() -> Response.noContent().build());
    }

    private AroOperation newOperation(OpenShiftCluster cluster, String initialStatus) {
        String location = cluster.getLocation().toLowerCase(Locale.ROOT).replace(" ", "");
        AroOperation operation = new AroOperation(UUID.randomUUID().toString(), cluster.getSubscriptionId(),
                location, cluster.armId(), initialStatus);
        synchronized (lock) {
            operations.put(operation.id, operation);
            while (operations.size() > MAX_OPERATIONS) {
                operations.remove(operations.keySet().iterator().next());
            }
        }
        return operation;
    }

    private AroOperation operation(String sub, String operationId) {
        synchronized (lock) {
            AroOperation operation = operations.get(operationId);
            return operation == null || !operation.subscriptionId.equalsIgnoreCase(sub) ? null : operation;
        }
    }

    private void complete(String operationId, String status, String errorCode, String errorMessage) {
        synchronized (lock) {
            AroOperation operation = operationId == null ? null : operations.get(operationId);
            if (operation != null) {
                complete(operation, status, errorCode, errorMessage);
            }
        }
    }

    private void complete(AroOperation operation, String status, String errorCode, String errorMessage) {
        synchronized (lock) {
            operation.status = status;
            operation.endTime = Instant.now();
            operation.errorCode = errorCode;
            operation.errorMessage = errorMessage;
        }
    }

    // ── Runtime ──────────────────────────────────────────────────────────────

    private void start(OpenShiftCluster cluster) {
        try {
            clusterManager.startCluster(cluster);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to start the MicroShift container of ARO cluster %s", cluster.getName());
            fail(cluster, CREATING, "Could not start the cluster's MicroShift container: " + e.getMessage());
            return;
        }
        boolean kept = mutate(cluster, stored -> copyRuntime(cluster, stored));
        if (!kept) {
            LOG.infov("ARO cluster {0} was removed while its container started; removing the container",
                    cluster.getName());
            clusterManager.removeCluster(cluster);
        }
    }

    private void remove(OpenShiftCluster cluster) {
        forgetConsole(cluster);
        try {
            proxyManager.stop(cluster);
            clusterManager.removeCluster(cluster);
        } catch (RuntimeException e) {
            LOG.warnv("Error removing the MicroShift container of ARO cluster {0}: {1}", cluster.getName(),
                    e.getMessage());
            fail(cluster, DELETING, "Could not remove the cluster's MicroShift container: " + e.getMessage());
            return;
        }
        synchronized (lock) {
            ArmResourceId id = clusterId(cluster);
            store.get(id).map(ArmResourceStore.Stored::value)
                    .filter(stored -> cluster.getInstanceId().equals(stored.getInstanceId()))
                    .ifPresent(stored -> store.delete(id));
            complete(cluster.getAsyncOperationId(), SUCCEEDED, null, null);
        }
    }

    private void restore(OpenShiftCluster cluster) {
        String state = cluster.getProvisioningState();
        if (DELETING.equals(state)) {
            remove(cluster);
            return;
        }
        if (!SUCCEEDED.equals(state) && !CREATING.equals(state) && !UPDATING.equals(state)) {
            return;
        }
        if (!cluster.isOauthEnabled() && config.services().aro().oauth().enabled()) {
            LOG.infov("ARO cluster {0} was stored without an API proxy: it keeps its direct API URL and has no OAuth "
                    + "server, so oc login needs a cluster created from now on", cluster.getName());
        }
        try {
            AroClusterManager.Restore outcome = clusterManager.restore(cluster);
            mutate(cluster, stored -> {
                copyRuntime(cluster, stored);
                if (outcome == AroClusterManager.Restore.RECREATED) {
                    stored.setKubeadminSecretCreated(false);
                    stored.setOauthTokens(null);
                }
                if (outcome != AroClusterManager.Restore.ADOPTED) {
                    stored.setKubeconfig(null);
                    stored.setProvisioningState(CREATING);
                    stored.setReadyDeadline(Instant.now()
                            .plusSeconds(config.services().aro().readyTimeoutSeconds()));
                } else if (UPDATING.equals(stored.getProvisioningState())) {
                    stored.setProvisioningState(SUCCEEDED);
                }
            });
            LOG.infov("Restored ARO cluster {0}: {1}", cluster.getName(), outcome);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Could not restore ARO cluster %s", cluster.getName());
            fail(cluster, CREATING, "Could not restore the cluster's MicroShift container: " + e.getMessage());
        }
    }

    private void pollClusters() {
        try {
            for (OpenShiftCluster cluster : scanAll()) {
                if (UPDATING.equals(cluster.getProvisioningState())) {
                    succeed(cluster);
                } else if (CREATING.equals(cluster.getProvisioningState())) {
                    pollCreating(cluster);
                } else if (SUCCEEDED.equals(cluster.getProvisioningState()) && cluster.getContainerId() != null) {
                    if (cluster.getProxyPort() > 0 && !proxyManager.running(cluster)) {
                        startProxy(cluster);
                    }
                    ensureConsole(cluster);
                    watchConsole(cluster);
                }
            }
        } catch (RuntimeException e) {
            LOG.error("Error in the ARO readiness poller", e);
        }
    }

    private void pollCreating(OpenShiftCluster cluster) {
        if (cluster.getReadyDeadline() != null && Instant.now().isAfter(cluster.getReadyDeadline())) {
            String lastError = finalizeErrors.remove(cluster.getInstanceId());
            fail(cluster, CREATING, "The cluster did not become ready within "
                    + config.services().aro().readyTimeoutSeconds() + " seconds."
                    + (lastError == null ? "" : " Last error: " + lastError));
            return;
        }
        if (cluster.getContainerId() == null || !clusterManager.isReady(cluster)) {
            return;
        }
        try {
            clusterManager.finalizeCluster(cluster);
            if (cluster.getProxyPort() > 0) {
                proxyManager.ensureStarted(cluster, tokens -> persistTokens(cluster, tokens));
                if (!proxyManager.metadataAnswers(cluster)) {
                    throw new IllegalStateException("the API proxy does not serve the OAuth metadata yet");
                }
            }
        } catch (RuntimeException e) {
            String previous = finalizeErrors.put(cluster.getInstanceId(), String.valueOf(e.getMessage()));
            if (!String.valueOf(e.getMessage()).equals(previous)) {
                LOG.infov("ARO cluster {0} is up but not finalized yet: {1}", cluster.getName(), e.getMessage());
            }
            return;
        }
        finalizeErrors.remove(cluster.getInstanceId());
        LOG.infov("ARO cluster {0} is ready after {1} s", cluster.getName(),
                Duration.between(cluster.getCreatedAt(), Instant.now()).toSeconds());
        mutate(cluster, stored -> {
            stored.setKubeconfig(cluster.getKubeconfig());
            stored.setKubeadminSecretCreated(cluster.isKubeadminSecretCreated());
        });
        succeed(cluster);
        ensureConsole(cluster);
    }

    /** Starts the API proxy of a ready cluster whose proxy is not running (adopted at startup, or a failed start). */
    private void startProxy(OpenShiftCluster cluster) {
        try {
            proxyManager.ensureStarted(cluster, tokens -> persistTokens(cluster, tokens));
            finalizeErrors.remove(cluster.getInstanceId());
        } catch (RuntimeException e) {
            String previous = finalizeErrors.put(cluster.getInstanceId(), String.valueOf(e.getMessage()));
            if (!String.valueOf(e.getMessage()).equals(previous)) {
                LOG.warnv("ARO cluster {0}: {1}", cluster.getName(), e.getMessage());
            }
        }
    }

    /**
     * Applies the console's objects once per cluster since floci-az started, after the cluster is ready; the console
     * never holds readiness back. A failure is logged once and retried on the next poll.
     */
    private void ensureConsole(OpenShiftCluster cluster) {
        String id = cluster.getInstanceId();
        if (!cluster.isConsoleEnabled() || consolesApplied.contains(id)) {
            return;
        }
        try {
            clusterManager.applyConsole(cluster);
            consolesApplied.add(id);
            consoleDeadlines.put(id, Instant.now().plusSeconds(config.services().aro().console().readyTimeoutSeconds()));
            finalizeErrors.remove(id);
            LOG.infov("ARO cluster {0}: console objects applied for {1}", cluster.getName(),
                    AroClusterManager.consoleUrl(cluster));
        } catch (RuntimeException e) {
            String previous = finalizeErrors.put(id, String.valueOf(e.getMessage()));
            if (!String.valueOf(e.getMessage()).equals(previous)) {
                LOG.warnv("ARO cluster {0}: could not apply the console: {1}", cluster.getName(), e.getMessage());
            }
        }
    }

    /** Logs when the console becomes available, or once when it is still not after {@code console.ready-timeout}. */
    private void watchConsole(OpenShiftCluster cluster) {
        String id = cluster.getInstanceId();
        Instant deadline = consoleDeadlines.get(id);
        Instant now = Instant.now();
        if (deadline == null || now.isBefore(consoleChecks.getOrDefault(id, Instant.MIN))) {
            return;
        }
        consoleChecks.put(id, now.plusSeconds(10));
        boolean available;
        try {
            available = clusterManager.consoleAvailable(cluster);
        } catch (RuntimeException e) {
            LOG.debugv("ARO cluster {0}: console state unknown: {1}", cluster.getName(), e.getMessage());
            available = false;
        }
        if (available) {
            consoleDeadlines.remove(id);
            LOG.infov("ARO cluster {0}: console available at {1}", cluster.getName(),
                    AroClusterManager.consoleUrl(cluster));
        } else if (now.isAfter(deadline)) {
            consoleDeadlines.remove(id);
            LOG.warnv("ARO cluster {0}: the console is not available {1} s after the cluster became ready; see "
                    + "oc get pods -n openshift-console", cluster.getName(),
                    String.valueOf(config.services().aro().console().readyTimeoutSeconds()));
        }
    }

    private void forgetConsole(OpenShiftCluster cluster) {
        consolesApplied.remove(cluster.getInstanceId());
        consoleDeadlines.remove(cluster.getInstanceId());
        consoleChecks.remove(cluster.getInstanceId());
    }

    private boolean consoleEnabled() {
        try {
            return clusterManager.consoleEnabled();
        } catch (RuntimeException e) {
            LOG.debugv("Cannot tell the Docker daemon's architecture; no console: {0}", e.getMessage());
            return false;
        }
    }

    private void persistTokens(OpenShiftCluster cluster, Map<String, OAuthToken> tokens) {
        mutate(cluster, stored -> stored.setOauthTokens(tokens));
    }

    private void succeed(OpenShiftCluster cluster) {
        mutate(cluster, stored -> {
            stored.setProvisioningState(SUCCEEDED);
            stored.setFailedProvisioningState(null);
            stored.setReadyDeadline(null);
        });
        complete(cluster.getAsyncOperationId(), SUCCEEDED, null, null);
    }

    private void fail(OpenShiftCluster cluster, String failedIn, String message) {
        mutate(cluster, stored -> {
            stored.setProvisioningState(FAILED);
            stored.setFailedProvisioningState(failedIn);
            stored.setReadyDeadline(null);
        });
        complete(cluster.getAsyncOperationId(), FAILED, "InternalServerError", message);
    }

    /** Applies {@code change} to the stored cluster when it is still the same instance; false when it is gone. */
    private boolean mutate(OpenShiftCluster cluster, Consumer<OpenShiftCluster> change) {
        synchronized (lock) {
            ArmResourceId id = clusterId(cluster);
            Optional<OpenShiftCluster> stored = store.get(id).map(ArmResourceStore.Stored::value)
                    .filter(current -> cluster.getInstanceId().equals(current.getInstanceId()));
            if (stored.isEmpty()) {
                return false;
            }
            change.accept(stored.get());
            store.put(stored.get());
            return true;
        }
    }

    private static void copyRuntime(OpenShiftCluster from, OpenShiftCluster to) {
        to.setContainerId(from.getContainerId());
        to.setApiHostPort(from.getApiHostPort());
        to.setHttpHostPort(from.getHttpHostPort());
        to.setHttpsHostPort(from.getHttpsHostPort());
        to.setProxyPort(from.getProxyPort());
        to.setEndpoint(from.getEndpoint());
        to.setInternalEndpoint(from.getInternalEndpoint());
        to.setPublicIp(from.getPublicIp());
    }

    // ── Responses ────────────────────────────────────────────────────────────

    private Response accepted(AzureRequest req, int status, OpenShiftCluster cluster, AroOperation operation) {
        return Response.status(status)
                .entity(toArm(cluster, false))
                .type(MediaType.APPLICATION_JSON)
                .header("Azure-AsyncOperation", operationBase(req, cluster.getSubscriptionId(), operation.location)
                        + "/operationsstatus/" + operation.id + apiVersionQuery(req))
                .header("Retry-After", "1")
                .build();
    }

    /**
     * The cluster as ARO returns it: the stored properties with the provisioning state and the read-only URLs and
     * addresses filled in, never the pull secret or the client secret. {@code workerProfilesStatus} is enriched on
     * reads only, as ARO's frontend does.
     */
    Map<String, Object> toArm(OpenShiftCluster cluster, boolean read) {
        Map<String, Object> props = AroProperties.deepCopy(cluster.getProperties());
        props.put("provisioningState", cluster.getProvisioningState());
        String fullDomain = AroProperties.fullDomain(cluster.clusterProfileValue("domain"), cluster.getLocation());
        AroProperties.cast(props.computeIfAbsent("consoleProfile", k -> new LinkedHashMap<>()))
                .put("url", (mocked() ? "https://console-openshift-console.apps." + fullDomain
                        : AroClusterManager.consoleUrl(cluster)) + "/");
        Map<String, Object> apiserver = AroProperties.cast(props.get("apiserverProfile"));
        apiserver.put("url", cluster.getEndpoint() != null
                ? cluster.getEndpoint() + "/" : "https://api." + fullDomain + ":6443/");
        if (cluster.getPublicIp() != null) {
            apiserver.put("ip", cluster.getPublicIp());
            for (Map<String, Object> ingress : AroProperties.listOfMaps(props.get("ingressProfiles"))) {
                ingress.put("ip", cluster.getPublicIp());
            }
        }
        props.put("apiserverProfile", apiserver);
        if (read && SUCCEEDED.equals(cluster.getProvisioningState())) {
            props.put("workerProfilesStatus", workerProfilesStatus(cluster));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", cluster.armId());
        out.put("name", cluster.getName());
        out.put("type", TYPE);
        out.put("location", cluster.getLocation());
        if (cluster.getTags() != null && !cluster.getTags().isEmpty()) {
            out.put("tags", cluster.getTags());
        }
        out.put("properties", AroProperties.order(props));
        if (cluster.getIdentity() != null) {
            out.put("identity", cluster.getIdentity());
        }
        return out;
    }

    /**
     * What ARO's frontend reads from the cluster's MachineSets: one per zone, named
     * {@code <infraID>-<profile>-<region><zone>}, the profile's count spread across zones 1 to 3. The emulated
     * cluster has no worker nodes of its own; its one node is master and worker.
     */
    private static List<Map<String, Object>> workerProfilesStatus(OpenShiftCluster cluster) {
        List<Map<String, Object>> status = new ArrayList<>();
        String region = cluster.getLocation().toLowerCase(Locale.ROOT).replace(" ", "");
        for (Map<String, Object> profile : AroProperties.listOfMaps(cluster.getProperties().get("workerProfiles"))) {
            int count = profile.get("count") instanceof Number n ? n.intValue() : 0;
            for (int zone = 1; zone <= 3; zone++) {
                Map<String, Object> machineSet = new LinkedHashMap<>();
                machineSet.put("name", cluster.getInfraId() + "-" + profile.get("name") + "-" + region + zone);
                machineSet.put("vmSize", profile.get("vmSize"));
                machineSet.put("diskSizeGB", profile.get("diskSizeGB"));
                if (profile.get("subnetId") != null) {
                    machineSet.put("subnetId", profile.get("subnetId"));
                }
                machineSet.put("count", count / 3 + (zone <= count % 3 ? 1 : 0));
                machineSet.put("encryptionAtHost", profile.get("encryptionAtHost"));
                if (profile.get("diskEncryptionSetId") != null) {
                    machineSet.put("diskEncryptionSetId", profile.get("diskEncryptionSetId"));
                }
                status.add(machineSet);
            }
        }
        return status;
    }

    /** An installer-style admin kubeconfig for mocked mode, pointing at the cluster's ARO API URL. */
    private static String mockKubeconfig(OpenShiftCluster cluster) {
        String fullDomain = AroProperties.fullDomain(cluster.clusterProfileValue("domain"), cluster.getLocation());
        String yaml = "apiVersion: v1\n"
                + "clusters:\n"
                + "- cluster:\n"
                + "    server: https://api." + fullDomain + ":6443\n"
                + "    insecure-skip-tls-verify: true\n"
                + "  name: " + cluster.getInfraId() + "\n"
                + "contexts:\n"
                + "- context:\n"
                + "    cluster: " + cluster.getInfraId() + "\n"
                + "    user: admin\n"
                + "  name: admin\n"
                + "current-context: admin\n"
                + "kind: Config\n"
                + "preferences: {}\n"
                + "users:\n"
                + "- name: admin\n"
                + "  user:\n"
                + "    token: floci-az-mock-token\n";
        return Base64.getEncoder().encodeToString(yaml.getBytes(StandardCharsets.UTF_8));
    }

    private Response notFound(String sub, String rg, String name) {
        return cloudError(404, "ResourceNotFound", null, "The Resource 'openshiftclusters/"
                + name.toLowerCase(Locale.ROOT) + "' under resource group '" + rg.toLowerCase(Locale.ROOT)
                + "' was not found.");
    }

    static Response cloudError(int status, String code, String target, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (target != null) {
            error.put("target", target);
        }
        return json(status, Map.of("error", error));
    }

    private static Response methodNotAllowed() {
        return cloudError(405, "MethodNotAllowed", null, "The requested method is not allowed.");
    }

    private static Response json(int status, Object body) {
        return Response.status(status).entity(body).type(MediaType.APPLICATION_JSON).build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String operationBase(AzureRequest req, String sub, String location) {
        return RequestUrls.resolveBaseUrl(req, config) + "/subscriptions/" + sub + "/providers/"
                + AroArmSurface.NAMESPACE + "/locations/" + location;
    }

    private static String apiVersion(AzureRequest req) {
        return req.queryParams() == null ? "" : req.queryParams().getOrDefault("api-version", "");
    }

    private static String apiVersionQuery(AzureRequest req) {
        String version = apiVersion(req);
        return version.isBlank() ? "" : "?api-version=" + version;
    }

    private boolean mocked() {
        return config.services().aro().mocked();
    }

    private Optional<OpenShiftCluster> find(String sub, String rg, String name) {
        return store.get(clusterId(sub, rg, name)).map(ArmResourceStore.Stored::value);
    }

    private List<OpenShiftCluster> scanAll() {
        return store.all().stream().map(ArmResourceStore.Stored::value).toList();
    }

    private static ArmResourceId clusterId(String sub, String rg, String name) {
        return ArmResourceId.parse("/subscriptions/" + sub + "/resourceGroups/" + rg + "/providers/" + TYPE + "/"
                + name).orElseThrow();
    }

    private static ArmResourceId clusterId(OpenShiftCluster cluster) {
        return ArmResourceId.parse(cluster.armId()).orElseThrow();
    }

    private static OpenShiftCluster copy(OpenShiftCluster cluster) {
        return MAPPER.convertValue(MAPPER.valueToTree(cluster), OpenShiftCluster.class);
    }

    private static boolean sameLocation(String a, String b) {
        return a.toLowerCase(Locale.ROOT).replace(" ", "").equals(b.toLowerCase(Locale.ROOT).replace(" ", ""));
    }

    private static Map<String, String> tags(Object value) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> tags.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        return tags;
    }

    private static Map<String, Object> readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return new LinkedHashMap<>();
        }
        byte[] bytes = stream.readAllBytes();
        if (bytes.length == 0) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> body = MAPPER.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() { });
        return body == null ? new LinkedHashMap<>() : body;
    }

    /**
     * ARO-RP {@code generateInfraID}: the lowercased cluster name with anything but letters, digits and dashes
     * replaced, cut to 21 characters, then a dash and 5 random characters.
     */
    static String infraId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("-{2,}", "-");
        if (base.length() > 21) {
            base = base.substring(0, 21);
        }
        base = base.replaceAll("-+$", "");
        StringBuilder id = new StringBuilder(base).append('-');
        for (int i = 0; i < 5; i++) {
            id.append(INFRA_ID_ALPHABET.charAt(RANDOM.nextInt(INFRA_ID_ALPHABET.length())));
        }
        return id.toString();
    }

    /**
     * A kubeadmin password as the OpenShift installer generates it ({@code pkg/asset/password}): 23 characters from
     * letters and digits without the ambiguous {@code l}, {@code O}, {@code 0} and {@code 1}, dashes at 5, 11 and 17.
     */
    static String kubeadminPassword() {
        char[] password = new char[23];
        for (int i = 0; i < password.length; i++) {
            password[i] = KUBEADMIN_ALPHABET.charAt(RANDOM.nextInt(KUBEADMIN_ALPHABET.length()));
        }
        for (int dash : new int[]{5, 11, 17}) {
            password[dash] = '-';
        }
        return new String(password);
    }

    // ── Resettable, ResourceIndexContributor ────────────────────────────────

    @Override
    public void clear() {
        if (!mocked()) {
            for (OpenShiftCluster cluster : scanAll()) {
                forgetConsole(cluster);
                try {
                    proxyManager.stop(cluster);
                    clusterManager.removeCluster(cluster);
                } catch (RuntimeException e) {
                    LOG.warnv("Error removing ARO cluster {0} during reset: {1}", cluster.getName(), e.getMessage());
                }
            }
        }
        synchronized (lock) {
            store.clear();
            operations.clear();
        }
    }

    @Override
    public boolean indexEnabled() {
        return config.services().aro().enabled();
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
