package io.floci.az.services.aks;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The long-running operations AKS hands out on PUT, DELETE, start and stop. An operation's status is read from the
 * resource it acts on each time it is polled, so it reports {@code InProgress} for as long as the cluster or pool
 * is in a transient state and settles on the state the resource reaches. Once settled, the outcome is kept: a
 * later operation on the same resource does not change it. Held in memory, newest {@value #MAX_TRACKED} only.
 */
final class AksOperations {

    static final int MAX_TRACKED = 1000;

    private static final Set<String> TRANSIENT = Set.of("Creating", "Updating", "Upgrading", "Scaling",
            "Starting", "Stopping", "Deleting");

    enum Kind { CREATE, UPDATE, DELETE, START, STOP }

    record Operation(String id, Kind kind, String resourceKey, String instanceId, Instant startTime) {
    }

    /** What the poll needs from the resource: which instance it is, its provisioning state and last error. */
    record ResourceState(String instanceId, String provisioningState, String lastError) {
    }

    record Status(String id, String status, Instant startTime, Instant endTime, String errorMessage) {
        boolean terminal() {
            return !"InProgress".equals(status);
        }
    }

    private final Map<String, Operation> operations = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Operation> eldest) {
            return size() > MAX_TRACKED;
        }
    };
    private final Map<String, Status> settled = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Status> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    synchronized Operation start(Kind kind, String resourceKey, String instanceId) {
        Operation op = new Operation(UUID.randomUUID().toString(), kind, resourceKey, instanceId, Instant.now());
        operations.put(op.id(), op);
        return op;
    }

    /** Records an operation that finished when it was issued, such as any operation in mocked mode. */
    synchronized Operation completed(Kind kind, String resourceKey, String instanceId) {
        Operation op = start(kind, resourceKey, instanceId);
        settled.put(op.id(), new Status(op.id(), "Succeeded", op.startTime(), op.startTime(), null));
        return op;
    }

    /**
     * The operation's status, read from its resource through {@code lookup} (keyed by the resource key it was
     * started with); empty for an id this registry never issued or no longer holds.
     */
    synchronized Optional<Status> status(String id, Function<String, Optional<ResourceState>> lookup) {
        Operation op = operations.get(id);
        if (op == null) {
            return Optional.empty();
        }
        Status known = settled.get(id);
        if (known != null) {
            return Optional.of(known);
        }
        Status status = derive(op, lookup.apply(op.resourceKey()));
        if (status.terminal()) {
            settled.put(id, status);
        }
        return Optional.of(status);
    }

    private static Status derive(Operation op, Optional<ResourceState> resource) {
        if (resource.isEmpty()) {
            return finished(op, op.kind() == Kind.DELETE ? "Succeeded" : "Canceled", null);
        }
        ResourceState state = resource.get();
        if (op.instanceId() != null && !op.instanceId().equals(state.instanceId())) {
            return finished(op, op.kind() == Kind.DELETE ? "Succeeded" : "Canceled", null);
        }
        String provisioning = state.provisioningState();
        if (provisioning == null || TRANSIENT.contains(provisioning)) {
            return new Status(op.id(), "InProgress", op.startTime(), null, null);
        }
        if ("Failed".equals(provisioning)) {
            return finished(op, "Failed", state.lastError() == null ? "The operation failed." : state.lastError());
        }
        if ("Canceled".equals(provisioning)) {
            return finished(op, "Canceled", null);
        }
        return finished(op, "Succeeded", null);
    }

    private static Status finished(Operation op, String status, String error) {
        return new Status(op.id(), status, op.startTime(), Instant.now(), error);
    }

    synchronized void clear() {
        operations.clear();
        settled.clear();
    }
}
