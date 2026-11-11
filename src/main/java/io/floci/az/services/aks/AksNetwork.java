package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.az.services.aks.AksHandler.AksRequestException;
import io.floci.az.services.aks.AksModels.NetworkProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cluster network AKS reports and k3s runs: the plugin, the pod and service ranges and the DNS service address,
 * with AKS's defaults when the request leaves them out.
 */
final class AksNetwork {

    static final String DEFAULT_POD_CIDR = "10.244.0.0/16";
    static final String DEFAULT_SERVICE_CIDR = "10.0.0.0/16";
    static final String DEFAULT_DNS_SERVICE_IP = "10.0.0.10";
    static final int LEGACY_MAX_PODS = 110;

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

    private AksNetwork() {
    }

    /**
     * The profile a create request asks for, completed with AKS's defaults: Azure CNI Overlay when no plugin is
     * named, the 10.244.0.0/16 pod range for overlay and kubenet, 10.0.0.0/16 for services and 10.0.0.10 for DNS.
     *
     * @throws AksRequestException for a malformed range, or a DNS address outside the service range
     */
    static NetworkProfile resolve(JsonNode node) {
        NetworkProfile profile = new NetworkProfile();
        String plugin = text(node, "networkPlugin");
        String mode = text(node, "networkPluginMode");
        if (plugin == null) {
            plugin = "azure";
            mode = mode == null ? "overlay" : mode;
        }
        profile.setNetworkPlugin(plugin);
        profile.setNetworkPluginMode(mode);
        profile.setNetworkPolicy(text(node, "networkPolicy"));
        profile.setNetworkDataplane(text(node, "networkDataplane"));
        String podCidr = firstOf(node, "podCidr", "podCidrs");
        if (podCidr == null && hasOwnPodRange(profile)) {
            podCidr = DEFAULT_POD_CIDR;
        }
        profile.setPodCidr(podCidr);
        String serviceCidr = firstOf(node, "serviceCidr", "serviceCidrs");
        profile.setServiceCidr(serviceCidr == null ? DEFAULT_SERVICE_CIDR : serviceCidr);
        String dns = text(node, "dnsServiceIP");
        profile.setDnsServiceIP(dns == null ? DEFAULT_DNS_SERVICE_IP : dns);
        String outbound = text(node, "outboundType");
        profile.setOutboundType(outbound == null ? "loadBalancer" : outbound);
        String sku = text(node, "loadBalancerSku");
        profile.setLoadBalancerSku(sku == null ? "standard" : sku);

        if (profile.getPodCidr() != null) {
            cidr("podCidr", profile.getPodCidr());
        }
        long[] services = cidr("serviceCidr", profile.getServiceCidr());
        long dnsAddress = address("dnsServiceIP", profile.getDnsServiceIP());
        if (dnsAddress < services[0] || dnsAddress > services[1]) {
            throw new AksRequestException(400, "DnsServiceIpOutOfServiceCidr", "The DNS service IP "
                    + profile.getDnsServiceIP() + " is out of the range defined by service CIDR "
                    + profile.getServiceCidr() + ".");
        }
        return profile;
    }

    /** Overlay, kubenet and bring-your-own CNI give pods a range of their own; Azure CNI gives them subnet IPs. */
    private static boolean hasOwnPodRange(NetworkProfile profile) {
        return !"azure".equalsIgnoreCase(profile.getNetworkPlugin())
                || "overlay".equalsIgnoreCase(profile.getNetworkPluginMode());
    }

    /** The range k3s hands pod addresses from: the pod CIDR, or the default one when pods take subnet IPs. */
    static String clusterCidr(NetworkProfile profile) {
        return profile.getPodCidr() == null ? DEFAULT_POD_CIDR : profile.getPodCidr();
    }

    /**
     * k3s server arguments for the profile. AKS enforces network policies only when a policy engine is chosen, so
     * k3s's own policy controller is off otherwise. A cluster recorded without a profile keeps k3s's defaults,
     * since its data store was initialized with them.
     */
    static List<String> serverArgs(NetworkProfile profile) {
        if (profile == null) {
            return List.of();
        }
        List<String> args = new ArrayList<>(List.of("--cluster-cidr=" + clusterCidr(profile),
                "--service-cidr=" + profile.getServiceCidr(), "--cluster-dns=" + profile.getDnsServiceIP()));
        if (!enforcesNetworkPolicy(profile)) {
            args.add("--disable-network-policy");
        }
        return args;
    }

    private static boolean enforcesNetworkPolicy(NetworkProfile profile) {
        String policy = profile.getNetworkPolicy();
        if (policy != null && !"none".equalsIgnoreCase(policy)) {
            return true;
        }
        return "cilium".equalsIgnoreCase(profile.getNetworkDataplane());
    }

    /** AKS's default max pods per node: 250 for overlay and no plugin, 30 for Azure CNI, 110 for kubenet. */
    static int defaultMaxPods(NetworkProfile profile) {
        if (profile == null) {
            return LEGACY_MAX_PODS;
        }
        String plugin = profile.getNetworkPlugin() == null ? "" : profile.getNetworkPlugin().toLowerCase(Locale.ROOT);
        return switch (plugin) {
            case "kubenet" -> 110;
            case "azure" -> "overlay".equalsIgnoreCase(profile.getNetworkPluginMode()) ? 250 : 30;
            default -> 250;
        };
    }

    /** First and last address of an IPv4 CIDR as unsigned values. */
    private static long[] cidr(String field, String value) {
        int slash = value.indexOf('/');
        if (slash < 0) {
            throw invalid(field, value);
        }
        long base = address(field, value.substring(0, slash));
        int prefix;
        try {
            prefix = Integer.parseInt(value.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw invalid(field, value);
        }
        if (prefix < 0 || prefix > 32) {
            throw invalid(field, value);
        }
        long size = 1L << (32 - prefix);
        long first = base & ~(size - 1) & 0xFFFFFFFFL;
        return new long[] {first, first + size - 1};
    }

    private static long address(String field, String value) {
        Matcher m = value == null ? null : IPV4.matcher(value);
        if (m == null || !m.matches()) {
            throw invalid(field, value);
        }
        long result = 0;
        for (int group = 1; group <= 4; group++) {
            int octet = Integer.parseInt(m.group(group));
            if (octet > 255) {
                throw invalid(field, value);
            }
            result = (result << 8) | octet;
        }
        return result;
    }

    private static AksRequestException invalid(String field, String value) {
        return new AksRequestException(400, "InvalidParameter", "The value '" + value + "' of networkProfile."
                + field + " is not a valid IPv4 " + (field.endsWith("IP") ? "address" : "CIDR") + ".");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private static String firstOf(JsonNode node, String single, String list) {
        String value = text(node, single);
        if (value != null) {
            return value;
        }
        JsonNode values = node == null ? null : node.get(list);
        if (values != null && values.isArray()) {
            for (JsonNode v : values) {
                if (!v.asText().contains(":")) {
                    return v.asText();
                }
            }
        }
        return null;
    }
}
