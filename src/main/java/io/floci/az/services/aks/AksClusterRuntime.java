package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** What runs inside a cluster's containers: the k3s server's arguments and manifests, and the cluster's nodes. */
final class AksClusterRuntime {

    private final AksClusterManager manager;

    AksClusterRuntime(AksClusterManager manager) {
        this.manager = manager;
    }

    /**
     * k3s server arguments. The API certificate must be valid for every host the cluster is addressed by:
     * {@code localhost} always, plus the Docker daemon's host in published mode.
     */
    List<String> serverArgs(ManagedCluster cluster) {
        List<String> args = new ArrayList<>(List.of("server", "--disable=traefik", "--tls-san=localhost"));
        manager.daemonTlsSan().ifPresent(san -> args.add("--tls-san=" + san));
        return args;
    }

    Map<String, String> manifests(ManagedCluster cluster) {
        return Map.of();
    }

    void reconcileNodes(ManagedCluster cluster, AksReconciler.Persistence persistence) {
        // The k3s server runs its own agent, so the cluster has one node whatever its pools say.
    }
}
