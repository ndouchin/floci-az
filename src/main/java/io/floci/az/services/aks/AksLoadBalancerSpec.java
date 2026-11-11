package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What AKS's cloud controller manager (cloud-provider-azure) would build for one Service, read from the Service's
 * JSON. A cloud controller handles a Service only when its type is {@code LoadBalancer} and it names no
 * {@code loadBalancerClass} (kubernetes/cloud-provider {@code wantsLoadBalancer}). A public load balancer takes a new
 * frontend address; {@code service.beta.kubernetes.io/azure-load-balancer-internal: "true"} makes it internal, on the
 * cluster's network; {@code azure-load-balancer-ipv4} (else the deprecated {@code spec.loadBalancerIP}) asks for an
 * address ({@code load-balancer-standard.md}, {@code internal-lb.md}). {@code error} is why a Service stays pending.
 */
record AksLoadBalancerSpec(
        String uid,
        String namespace,
        String name,
        String resourceVersion,
        boolean wantsLoadBalancer,
        boolean deleting,
        List<String> finalizers,
        boolean internal,
        String requestedIp,
        List<Port> ports,
        boolean localTrafficPolicy,
        List<String> sourceRanges,
        JsonNode statusIngress,
        String hostPortsAnnotation,
        String error) {

    /** One Service port; {@code nodePort} is 0 when {@code allocateLoadBalancerNodePorts: false} left it out. */
    record Port(String name, String protocol, int port, int nodePort) {

        /** {@code TCP/80}: how a port is named in records and in the host-ports annotation. */
        String key() {
            return protocol + "/" + port;
        }
    }

    static final String CLEANUP_FINALIZER = "service.kubernetes.io/load-balancer-cleanup";
    static final String HOST_PORTS_ANNOTATION = "floci.io/load-balancer-host-ports";
    static final String INTERNAL = "service.beta.kubernetes.io/azure-load-balancer-internal";
    static final String IPV4 = "service.beta.kubernetes.io/azure-load-balancer-ipv4";
    static final String INTERNAL_SUBNET = "service.beta.kubernetes.io/azure-load-balancer-internal-subnet";
    private static final Pattern IPV4_ADDRESS = Pattern.compile(
            "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV4_CIDR = Pattern.compile(IPV4_ADDRESS.pattern() + "/(3[0-2]|[12]?\\d)");

    static AksLoadBalancerSpec from(JsonNode service) {
        JsonNode metadata = service.path("metadata");
        JsonNode spec = service.path("spec");
        JsonNode annotations = metadata.path("annotations");
        boolean wants = "LoadBalancer".equals(spec.path("type").asText())
                && (!spec.has("loadBalancerClass") || spec.path("loadBalancerClass").isNull());
        List<String> finalizers = new ArrayList<>();
        metadata.path("finalizers").forEach(finalizer -> finalizers.add(finalizer.asText()));
        boolean internal = "true".equalsIgnoreCase(annotations.path(INTERNAL).asText(""));
        String requested = annotations.path(IPV4).asText("").trim();
        if (requested.isEmpty()) {
            requested = spec.path("loadBalancerIP").asText("").trim();
        }
        List<Port> ports = new ArrayList<>();
        for (JsonNode port : spec.path("ports")) {
            ports.add(new Port(port.path("name").asText(""), port.path("protocol").asText("TCP").toUpperCase(Locale.ROOT),
                    port.path("port").asInt(), port.path("nodePort").asInt(0)));
        }
        List<String> sourceRanges = new ArrayList<>();
        spec.path("loadBalancerSourceRanges").forEach(range -> sourceRanges.add(range.asText().trim()));
        String error = null;
        if (!requested.isEmpty() && !IPV4_ADDRESS.matcher(requested).matches()) {
            error = "invalid load balancer IP address '" + requested + "'";
        } else if (!requested.isEmpty() && internal) {
            error = "a fixed internal address (" + requested + ") cannot be held: internal load balancers take the "
                    + "address the cluster network gives their appliance";
        }
        for (String range : sourceRanges) {
            if (error == null && !IPV4_CIDR.matcher(range).matches()) {
                error = "invalid loadBalancerSourceRanges entry '" + range + "'";
            }
        }
        return new AksLoadBalancerSpec(metadata.path("uid").asText(), metadata.path("namespace").asText("default"),
                metadata.path("name").asText(), metadata.path("resourceVersion").asText(null), wants,
                metadata.hasNonNull("deletionTimestamp"), List.copyOf(finalizers), internal,
                requested.isEmpty() ? null : requested, List.copyOf(ports),
                "Local".equals(spec.path("externalTrafficPolicy").asText()), List.copyOf(sourceRanges),
                service.path("status").path("loadBalancer").path("ingress"),
                annotations.path(HOST_PORTS_ANNOTATION).asText(null), error);
    }

    boolean hasCleanupFinalizer() {
        return finalizers.contains(CLEANUP_FINALIZER);
    }
}
