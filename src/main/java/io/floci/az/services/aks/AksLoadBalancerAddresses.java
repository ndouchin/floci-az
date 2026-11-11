package io.floci.az.services.aks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Public load balancer addresses: one per load balancer, from {@code load-balancer.public-cidr} (RFC 5737 TEST-NET-2
 * by default, never routed, and the range Azure's own examples use), unique across every cluster of the emulator
 * because they share one Docker network. The network's first address is Docker's gateway.
 */
final class AksLoadBalancerAddresses {

    static final String HOST_PORT_ADDRESS = "127.0.0.1";

    private AksLoadBalancerAddresses() {
    }

    /** An allocated address, or why none could be had. */
    record Allocation(String address, String error) {

        static Allocation of(String address) {
            return new Allocation(address, null);
        }

        static Allocation refused(String error) {
            return new Allocation(null, error);
        }
    }

    /**
     * The address a public load balancer takes: the one it asks for, when it is in the range and free, else the lowest
     * free one.
     */
    static Allocation allocate(String cidr, String requested, Collection<String> used) {
        long base = network(cidr);
        int prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
        long size = 1L << (32 - prefix);
        if (requested != null) {
            long wanted = toLong(requested);
            if (wanted <= base + 1 || wanted >= base + size - 1) {
                return Allocation.refused("requested address " + requested + " is not in the public range " + cidr);
            }
            if (used.contains(requested)) {
                return Allocation.refused("requested address " + requested + " is held by another load balancer");
            }
            return Allocation.of(requested);
        }
        for (long candidate = base + 2; candidate < base + size - 1; candidate++) {
            String address = toAddress(candidate);
            if (!used.contains(address)) {
                return Allocation.of(address);
            }
        }
        return Allocation.refused("no free address left in the public range " + cidr);
    }

    /** {@code status.loadBalancer.ingress}: one entry with the frontend address, as AKS writes it. */
    static List<Map<String, String>> ingress(String address) {
        List<Map<String, String>> ingress = new ArrayList<>();
        if (address != null) {
            ingress.add(Map.of("ip", address));
        }
        return ingress;
    }

    /** {@code TCP/80=127.0.0.1:41234,UDP/53=127.0.0.1:41235}, sorted by port name. */
    static String hostPortsAnnotation(Map<String, Integer> hostPorts) {
        StringBuilder value = new StringBuilder();
        new TreeMap<>(hostPorts).forEach((port, hostPort) -> {
            if (!value.isEmpty()) {
                value.append(',');
            }
            value.append(port).append('=').append(HOST_PORT_ADDRESS).append(':').append(hostPort);
        });
        return value.toString();
    }

    private static long network(String cidr) {
        int prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return toLong(cidr.substring(0, cidr.indexOf('/'))) & mask;
    }

    static long toLong(String address) {
        long value = 0;
        for (String octet : address.split("\\.")) {
            value = (value << 8) | Integer.parseInt(octet);
        }
        return value;
    }

    static String toAddress(long value) {
        return ((value >> 24) & 0xFF) + "." + ((value >> 16) & 0xFF) + "." + ((value >> 8) & 0xFF) + "." + (value & 0xFF);
    }
}
