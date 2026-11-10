package io.floci.az.services.aks;

import io.floci.az.services.aks.AksOperations.Kind;
import io.floci.az.services.aks.AksOperations.Operation;
import io.floci.az.services.aks.AksOperations.Status;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AksOperationsTest {

    private final AksOperations operations = new AksOperations();

    @Test
    void anOperationIsInProgressWhileItsResourceIsInATransientState() {
        Operation op = operations.start(Kind.CREATE, "/sub/rg/c1", "inst1");

        Status status = operations.status(op.id(), id -> Optional.of(new AksOperations.ResourceState("inst1",
                "Creating", null))).orElseThrow();

        assertEquals("InProgress", status.status());
        assertNull(status.endTime());
    }

    @Test
    void anOperationSucceedsWithItsResourceAndKeepsThatOutcome() {
        Operation op = operations.start(Kind.UPDATE, "/sub/rg/c1", "inst1");

        Status done = operations.status(op.id(), id -> Optional.of(new AksOperations.ResourceState("inst1",
                "Succeeded", null))).orElseThrow();
        Status later = operations.status(op.id(), id -> Optional.of(new AksOperations.ResourceState("inst1",
                "Upgrading", null))).orElseThrow();

        assertEquals("Succeeded", done.status());
        assertNotNull(done.endTime());
        assertEquals("Succeeded", later.status());
    }

    @Test
    void aFailedResourceFailsTheOperationWithItsError() {
        Operation op = operations.start(Kind.CREATE, "/sub/rg/c1", "inst1");

        Status status = operations.status(op.id(), id -> Optional.of(new AksOperations.ResourceState("inst1",
                "Failed", "k3s never became ready"))).orElseThrow();

        assertEquals("Failed", status.status());
        assertEquals("k3s never became ready", status.errorMessage());
    }

    @Test
    void aDeleteSucceedsOnceTheResourceIsGone() {
        Operation op = operations.start(Kind.DELETE, "/sub/rg/c1", "inst1");

        assertEquals("InProgress", operations.status(op.id(), id -> Optional.of(
                new AksOperations.ResourceState("inst1", "Deleting", null))).orElseThrow().status());
        assertEquals("Succeeded", operations.status(op.id(), id -> Optional.empty()).orElseThrow().status());
    }

    @Test
    void aCreateWhoseResourceWasDeletedOrReplacedIsCanceled() {
        Operation gone = operations.start(Kind.CREATE, "/sub/rg/c1", "inst1");
        Operation replaced = operations.start(Kind.CREATE, "/sub/rg/c2", "inst2");

        assertEquals("Canceled", operations.status(gone.id(), id -> Optional.empty()).orElseThrow().status());
        assertEquals("Canceled", operations.status(replaced.id(), id -> Optional.of(
                new AksOperations.ResourceState("other", "Creating", null))).orElseThrow().status());
    }

    @Test
    void anUnknownOperationIsNotFound() {
        assertTrue(operations.status("nope", id -> Optional.empty()).isEmpty());
    }

    @Test
    void onlyTheNewestOperationsAreKept() {
        Operation first = operations.start(Kind.CREATE, "/sub/rg/c0", "i");
        for (int i = 0; i < AksOperations.MAX_TRACKED; i++) {
            operations.start(Kind.CREATE, "/sub/rg/c" + i, "i");
        }

        assertTrue(operations.status(first.id(), id -> Optional.empty()).isEmpty());
    }
}
