package io.floci.az.services.aks;

import io.floci.az.core.arm.ArmResourceId;
import io.floci.az.core.arm.ArmResourceStore;
import io.floci.az.services.aks.AksModels.AgentPoolProfile;
import io.floci.az.services.aks.AksModels.ManagedCluster;
import io.floci.az.services.aks.AksVersions.Version;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Brings a cluster's containers to what its record asks for, off the request thread: the PUT, DELETE, start and
 * stop handlers write the record and return, and the operation they hand out reports the provisioning state this
 * class leaves. Runs one reconcile per cluster at a time; a record changed during a reconcile (a newer PUT) is
 * reconciled again before the cluster settles.
 */
final class AksReconciler {

    private static final Logger LOG = Logger.getLogger(AksReconciler.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** The record was deleted or replaced while it was being reconciled. */
    static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("the cluster was deleted or replaced while it was reconciled", null, false, false);
        }
    }

    private final ArmResourceStore<ManagedCluster> store;
    private final AksClusterManager manager;
    private final AksVersions versions;
    private final AksClusterRuntime runtime;
    private final Duration readyTimeout;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AtomicInteger threads = new AtomicInteger();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aks-reconciler-" + threads.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    AksReconciler(ArmResourceStore<ManagedCluster> store, AksClusterManager manager, AksVersions versions,
                  AksClusterRuntime runtime, Duration readyTimeout) {
        this.store = store;
        this.manager = manager;
        this.versions = versions;
        this.runtime = runtime;
        this.readyTimeout = readyTimeout;
    }

    void submit(ArmResourceId id) {
        executor.submit(() -> run(id));
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private void run(ArmResourceId id) {
        ReentrantLock lock = locks.computeIfAbsent(id.canonicalKey(), k -> new ReentrantLock());
        lock.lock();
        try {
            reconcile(id);
        } catch (RuntimeException e) {
            LOG.errorf(e, "AKS reconcile of %s failed", id.id());
        } finally {
            lock.unlock();
        }
    }

    void reconcile(ArmResourceId id) {
        Optional<ManagedCluster> loaded = store.get(id).map(ArmResourceStore.Stored::value);
        if (loaded.isEmpty()) {
            return;
        }
        ManagedCluster cluster = loaded.get();
        if ("Deleting".equals(cluster.getProvisioningState())) {
            delete(id, cluster);
            return;
        }
        long generation = cluster.getGeneration();
        try {
            if ("Stopped".equals(cluster.getPowerState())) {
                manager.stopContainers(cluster, nodeNames(cluster));
            } else {
                ensureControlPlane(id, cluster);
                runtime.reconcileNodes(cluster, new Persistence(id, cluster.getInstanceId()));
            }
            settle(id, cluster.getInstanceId(), generation);
        } catch (Cancelled e) {
            LOG.infov("AKS cluster {0} went away while it was reconciled; removing what was started for it",
                    cluster.getName());
            manager.removeCluster(cluster);
        } catch (RuntimeException e) {
            LOG.errorf(e, "AKS cluster %s failed", cluster.getName());
            fail(id, cluster.getInstanceId(), e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void ensureControlPlane(ArmResourceId id, ManagedCluster cluster) {
        Version version = Version.parse(cluster.getCurrentKubernetesVersion()).orElseGet(versions::defaultVersion);
        String image = versions.image(version);
        boolean hadCredentials = cluster.getCaData() != null;
        AksClusterManager.ServerStart start = manager.prepareServer(cluster, image, runtime.serverArgs(cluster),
                runtime.startupFiles(cluster), runtime.manifests(cluster));
        if (start == AksClusterManager.ServerStart.CREATED_FRESH && hadCredentials) {
            LOG.warnv("AKS cluster {0} lost its data volume and was recreated empty: its CA changed, fetch its"
                    + " credentials again", cluster.getName());
        }
        persist(id, cluster.getInstanceId(), current -> {
            current.setContainerId(cluster.getContainerId());
            current.setHostPort(cluster.getHostPort());
            current.setServerImage(cluster.getServerImage());
            current.setServerArgsHash(cluster.getServerArgsHash());
            current.setEndpoint(cluster.getEndpoint());
            current.setInternalEndpoint(cluster.getInternalEndpoint());
            if (cluster.getEndpoint() != null) {
                current.setFqdn(cluster.getEndpoint().replace("https://", ""));
            }
        });
        waitUntil(id, cluster.getInstanceId(), () -> manager.isApiReady(cluster),
                "the API server did not answer /readyz within " + readyTimeout.toSeconds() + " s");
        manager.extractCredentials(cluster);
        persist(id, cluster.getInstanceId(), current -> {
            current.setCaData(cluster.getCaData());
            current.setClientCertificateData(cluster.getClientCertificateData());
            current.setClientKeyData(cluster.getClientKeyData());
        });
    }

    private void delete(ArmResourceId id, ManagedCluster cluster) {
        try {
            manager.removeCluster(cluster);
        } catch (RuntimeException e) {
            LOG.warnv("Error removing the containers of AKS cluster {0}: {1}", cluster.getName(), e.getMessage());
        }
        store.deleteIf(current -> current.armId().equalsIgnoreCase(cluster.armId())
                && cluster.getInstanceId().equals(current.getInstanceId()));
    }

    /** Marks the cluster and its pools Succeeded, unless a newer PUT changed the record meanwhile. */
    private void settle(ArmResourceId id, String instanceId, long generation) {
        AtomicBoolean changed = new AtomicBoolean();
        store.update(id, current -> {
            if (!instanceId.equals(current.getInstanceId())) {
                return current;
            }
            if (current.getGeneration() != generation) {
                changed.set(true);
                return current;
            }
            current.setProvisioningState("Succeeded");
            current.setLastError(null);
            List<AgentPoolProfile> remaining = new ArrayList<>();
            for (AgentPoolProfile pool : current.pools()) {
                if ("Deleting".equals(pool.getProvisioningState())) {
                    continue;
                }
                pool.setProvisioningState("Succeeded");
                pool.setPowerState(current.getPowerState());
                remaining.add(pool);
            }
            current.setAgentPoolProfiles(remaining);
            return current;
        });
        if (changed.get()) {
            submit(id);
        }
    }

    private void fail(ArmResourceId id, String instanceId, String message) {
        store.update(id, current -> {
            if (!instanceId.equals(current.getInstanceId())) {
                return current;
            }
            current.setProvisioningState("Failed");
            current.setLastError(message);
            for (AgentPoolProfile pool : current.pools()) {
                if (!"Succeeded".equals(pool.getProvisioningState())) {
                    pool.setProvisioningState("Failed");
                }
            }
            return current;
        });
    }

    private void persist(ArmResourceId id, String instanceId, Consumer<ManagedCluster> change) {
        AtomicBoolean current = new AtomicBoolean();
        store.update(id, stored -> {
            if (!instanceId.equals(stored.getInstanceId()) || "Deleting".equals(stored.getProvisioningState())) {
                return stored;
            }
            current.set(true);
            change.accept(stored);
            return stored;
        });
        if (!current.get()) {
            throw new Cancelled();
        }
    }

    private void waitUntil(ArmResourceId id, String instanceId, BooleanSupplier condition, String timeoutMessage) {
        long deadline = System.nanoTime() + readyTimeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(timeoutMessage);
            }
            ensureCurrent(id, instanceId);
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
        }
    }

    private void ensureCurrent(ArmResourceId id, String instanceId) {
        Optional<ManagedCluster> stored = store.get(id).map(ArmResourceStore.Stored::value);
        if (stored.isEmpty() || !instanceId.equals(stored.get().getInstanceId())
                || "Deleting".equals(stored.get().getProvisioningState())) {
            throw new Cancelled();
        }
    }

    private static List<String> nodeNames(ManagedCluster cluster) {
        List<String> names = new ArrayList<>();
        cluster.pools().forEach(p -> p.nodes().forEach(n -> names.add(n.getName())));
        return names;
    }

    /** What {@link AksClusterRuntime} uses to write runtime state back and wait without owning the store. */
    final class Persistence {
        private final ArmResourceId id;
        private final String instanceId;

        Persistence(ArmResourceId id, String instanceId) {
            this.id = id;
            this.instanceId = instanceId;
        }

        void persist(Consumer<ManagedCluster> change) {
            AksReconciler.this.persist(id, instanceId, change);
        }

        void waitUntil(BooleanSupplier condition, String timeoutMessage) {
            AksReconciler.this.waitUntil(id, instanceId, condition, timeoutMessage);
        }
    }
}
