package io.floci.az.services.aks;

import io.floci.az.services.aks.AksModels.ManagedCluster;

import java.util.Locale;

/** {@code floci-az.services.aks.load-balancer.mode}: who answers a cluster's Services of type LoadBalancer. */
enum AksLoadBalancerMode {

    /** k3s's servicelb is off and floci-az plays AKS's cloud controller: an Azure-shaped address and an appliance. */
    EMULATED,
    /** k3s's bundled servicelb, which publishes node addresses, as clusters created before had. */
    SERVICELB,
    /** servicelb is off and nothing answers: Services stay {@code <pending>}. */
    OFF;

    /** The mode the cluster was created with; a cluster stored without one keeps servicelb. */
    static AksLoadBalancerMode of(ManagedCluster cluster) {
        return cluster.getLoadBalancerMode() == null ? SERVICELB : parse(cluster.getLoadBalancerMode());
    }

    static AksLoadBalancerMode parse(String value) {
        if (value == null || value.isBlank()) {
            return EMULATED;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AksLoadBalancerMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalStateException("Invalid floci-az.services.aks.load-balancer.mode value '" + value
                + "': allowed values are emulated, servicelb and off");
    }

    String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
