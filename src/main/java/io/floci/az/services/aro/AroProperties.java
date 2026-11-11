package io.floci.az.services.aro;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The writable properties of an OpenShift cluster: what a PUT or PATCH body may set, the defaults ARO and the
 * {@code az aro create} command fill in, the static validation ARO's frontend applies (ARO-RP
 * {@code pkg/api/v20250725/openshiftcluster_validatestatic.go}), and which properties are immutable after creation
 * ({@code openshiftcluster_updatepolicy.go}). Fields this class does not know are kept as the client sent them.
 */
final class AroProperties {

    static final String DEFAULT_POD_CIDR = "10.128.0.0/14";
    static final String DEFAULT_SERVICE_CIDR = "172.30.0.0/16";
    static final String DEFAULT_MASTER_VM_SIZE = "Standard_D8s_v5";
    static final String DEFAULT_WORKER_VM_SIZE = "Standard_D4s_v5";
    static final int DEFAULT_WORKER_COUNT = 3;
    static final int DEFAULT_WORKER_DISK_SIZE_GB = 128;

    /** ARO-RP {@code validate.RxDomainName}: the first label starts with a letter and has at least two characters. */
    private static final Pattern DOMAIN = Pattern.compile(
            "([a-z][-a-z0-9]{0,61}[a-z0-9])(\\.([a-z0-9]|[a-z0-9][-a-z0-9]{0,61}[a-z0-9]))*");
    /** ARO-RP {@code validate.RxDomainNameRFC1123}, which updates accept for domains created before the rule. */
    private static final Pattern DOMAIN_RFC1123 = Pattern.compile(
            "([a-z0-9]|[a-z0-9][-a-z0-9]{0,61}[a-z0-9])(\\.([a-z0-9]|[a-z0-9][-a-z0-9]{0,61}[a-z0-9]))*");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern RESOURCE_GROUP_ID = Pattern.compile(
            "(?i)/subscriptions/([^/]+)/resourceGroups/([-a-z0-9_().]{0,89}[-a-z0-9_()])");
    /** ARO's internal ranges, which no cluster CIDR may overlap ({@code api.JoinCIDRRange}). */
    private static final List<String> JOIN_CIDR_RANGES = List.of("100.64.0.0/16", "169.254.169.0/29", "100.88.0.0/16");
    private static final String MANAGED_DOMAIN_SUFFIX = "aroapp.io";
    private static final String RANDOM_ID_FIRST = "abcdefghijklmnopqrstuvwxyz";
    private static final String RANDOM_ID_REST = "abcdefghijklmnopqrstuvwxyz1234567890";

    /** {@code properties} paths whose value may change after creation; every other known path is immutable. */
    private static final List<String> IMMUTABLE = List.of(
            "clusterProfile.domain", "clusterProfile.version", "clusterProfile.resourceGroupId",
            "clusterProfile.fipsValidatedModules", "networkProfile.podCidr", "networkProfile.serviceCidr",
            "networkProfile.outboundType", "networkProfile.preconfiguredNSG", "masterProfile.vmSize",
            "masterProfile.subnetId", "masterProfile.encryptionAtHost", "masterProfile.diskEncryptionSetId",
            "workerProfiles", "apiserverProfile.visibility", "ingressProfiles");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A validation failure, in ARO's CloudError shape. */
    static final class InvalidProperty extends RuntimeException {
        private final String code;
        private final String target;

        InvalidProperty(String code, String target, String message) {
            super(message);
            this.code = code;
            this.target = target;
        }

        InvalidProperty(String target, String message) {
            this("InvalidParameter", target, message);
        }

        String code() { return code; }
        String target() { return target; }
    }

    /** What a create or update is validated against. */
    record Context(String subscriptionId, String resourceGroup, String location, AroVersions versions) {
    }

    private AroProperties() {
    }

    /**
     * The stored properties of a new cluster from the request's {@code properties}: secrets and read-only fields
     * dropped, defaults filled in, validated.
     */
    static Map<String, Object> forCreate(Map<String, Object> requested, Context context) {
        return normalize(writable(requested), cast(requested.get("servicePrincipalProfile")), context, true);
    }

    private static Map<String, Object> normalize(Map<String, Object> props, Map<String, Object> requestedPrincipal,
                                                 Context context, boolean create) {
        Map<String, Object> cluster = profile(props, "clusterProfile");
        String domain = string(cluster, "domain");
        if (domain == null || domain.isEmpty()) {
            domain = randomId();
            cluster.put("domain", domain);
        }
        validateDomain(domain, context.location(), create);
        String version = string(cluster, "version");
        if (version == null || version.isEmpty()) {
            version = context.versions().defaultVersion();
            cluster.put("version", version);
        }
        if (context.versions().resolve(version).isEmpty()) {
            throw new InvalidProperty("properties.clusterProfile.version",
                    "The requested OpenShift version '" + version + "' is invalid.");
        }
        String resourceGroupId = string(cluster, "resourceGroupId");
        if (resourceGroupId == null || resourceGroupId.isEmpty()) {
            resourceGroupId = "/subscriptions/" + context.subscriptionId() + "/resourceGroups/aro-" + domain;
        }
        validateResourceGroupId(resourceGroupId, context);
        cluster.put("resourceGroupId", resourceGroupId.toLowerCase(Locale.ROOT));
        cluster.putIfAbsent("fipsValidatedModules", "Disabled");
        oneOf(cluster, "fipsValidatedModules", "properties.clusterProfile.fipsValidatedModules",
                "The provided value '%s' is invalid.", "Disabled", "Enabled");

        Map<String, Object> servicePrincipal = optionalProfile(props, "servicePrincipalProfile");
        if (servicePrincipal != null) {
            validateServicePrincipal(servicePrincipal, requestedPrincipal, create);
        }

        Map<String, Object> network = profile(props, "networkProfile");
        network.putIfAbsent("podCidr", DEFAULT_POD_CIDR);
        network.putIfAbsent("serviceCidr", DEFAULT_SERVICE_CIDR);
        validateCidr("properties.networkProfile", "podCidr", string(network, "podCidr"), 18, "pod", create);
        validateCidr("properties.networkProfile", "serviceCidr", string(network, "serviceCidr"), 22, "service",
                create);
        network.putIfAbsent("outboundType", "Loadbalancer");
        oneOf(network, "outboundType", "properties.networkProfile.outboundType",
                "The provided outboundType '%s' is invalid: must be UserDefinedRouting or Loadbalancer.",
                "Loadbalancer", "UserDefinedRouting");
        network.putIfAbsent("preconfiguredNSG", "Disabled");
        oneOf(network, "preconfiguredNSG", "properties.networkProfile.preconfiguredNSG",
                "The provided value '%s' is invalid.", "Disabled", "Enabled");
        if ("Loadbalancer".equals(network.get("outboundType")) && !(network.get("loadBalancerProfile") instanceof Map)) {
            network.put("loadBalancerProfile", new LinkedHashMap<>(Map.of("managedOutboundIps", Map.of("count", 1))));
        }

        Map<String, Object> master = profile(props, "masterProfile");
        master.putIfAbsent("vmSize", DEFAULT_MASTER_VM_SIZE);
        master.putIfAbsent("encryptionAtHost", "Disabled");
        oneOf(master, "encryptionAtHost", "properties.masterProfile.encryptionAtHost",
                "The provided value '%s' is invalid.", "Disabled", "Enabled");

        props.put("workerProfiles", workerProfiles(props.get("workerProfiles")));

        Map<String, Object> apiserver = profile(props, "apiserverProfile");
        apiserver.putIfAbsent("visibility", "Public");
        oneOf(apiserver, "visibility", "properties.apiserverProfile.visibility",
                "The provided visibility '%s' is invalid.", "Private", "Public");

        props.put("ingressProfiles", ingressProfiles(props.get("ingressProfiles")));
        if ("UserDefinedRouting".equals(network.get("outboundType"))
                && ("Public".equals(apiserver.get("visibility")) || anyPublicIngress(props))) {
            throw new InvalidProperty("properties.networkProfile.outboundType", "The provided outboundType "
                    + "'UserDefinedRouting' is invalid: cannot use UserDefinedRouting if either API Server Visibility "
                    + "or Ingress Visibility is public.");
        }
        props.putIfAbsent("consoleProfile", new LinkedHashMap<>());
        return order(props);
    }

    /**
     * The stored properties after a PUT ({@code merge == false}: the body replaces the properties, keeping the version
     * and the service principal when it omits them) or a PATCH ({@code merge == true}: the body is merged over the
     * current properties). A change to an immutable property is refused like ARO refuses it.
     */
    static Map<String, Object> forUpdate(Map<String, Object> current, Map<String, Object> requested, boolean merge,
                                         Context context) {
        Map<String, Object> base;
        if (merge) {
            base = deepCopy(current);
            mergeInto(base, writable(requested));
        } else {
            base = writable(requested);
        }
        Map<String, Object> cluster = profile(base, "clusterProfile");
        Map<String, Object> currentCluster = cast(current.get("clusterProfile"));
        for (String kept : List.of("domain", "version", "resourceGroupId")) {
            if (string(cluster, kept) == null && currentCluster.get(kept) != null) {
                cluster.put(kept, currentCluster.get(kept));
            }
        }
        Map<String, Object> normalized = normalize(base, cast(requested.get("servicePrincipalProfile")), context,
                false);
        for (String path : IMMUTABLE) {
            if (!sameValue(path, at(current, path), at(normalized, path))) {
                throw new InvalidProperty("PropertyChangeNotAllowed", "properties." + path,
                        "Changing property 'properties." + path + "' is not allowed.");
            }
        }
        return normalized;
    }

    /**
     * The full domain of the cluster: ARO manages {@code <name>.<location>.aroapp.io} and takes a domain without a dot
     * as the short form of it (ARO-RP {@code dns.ManagedDomain}); a domain with a dot is a custom domain, used as it
     * is.
     */
    static String fullDomain(String domain, String location) {
        String managed = managedDomain(location);
        if (domain.endsWith("." + managed)) {
            return domain;
        }
        return domain.indexOf('.') < 0 ? domain + "." + managed : domain;
    }

    /** {@code <location>.aroapp.io}, the public cloud's managed domain of an ARO region. */
    static String managedDomain(String location) {
        return location.toLowerCase(Locale.ROOT).replace(" ", "") + "." + MANAGED_DOMAIN_SUFFIX;
    }

    private static void validateDomain(String domain, String location, boolean create) {
        String invalid = "The provided domain '" + domain + "' is invalid.";
        if (!(create ? DOMAIN : DOMAIN_RFC1123).matcher(domain).matches()) {
            throw new InvalidProperty("properties.clusterProfile.domain", invalid);
        }
        String managed = managedDomain(location);
        if (domain.endsWith("." + MANAGED_DOMAIN_SUFFIX) && !domain.endsWith("." + managed)) {
            throw new InvalidProperty("properties.clusterProfile.domain", invalid);
        }
        if (domain.endsWith("." + managed) && domain.substring(0, domain.length() - managed.length() - 1)
                .indexOf('.') >= 0) {
            throw new InvalidProperty("properties.clusterProfile.domain", invalid);
        }
    }

    private static void validateResourceGroupId(String resourceGroupId, Context context) {
        Matcher matcher = RESOURCE_GROUP_ID.matcher(resourceGroupId);
        if (!matcher.matches()) {
            throw new InvalidProperty("properties.clusterProfile.resourceGroupId",
                    "The provided resource group '" + resourceGroupId + "' is invalid.");
        }
        if (!matcher.group(1).equalsIgnoreCase(context.subscriptionId())) {
            throw new InvalidProperty("properties.clusterProfile.resourceGroupId", "The provided resource group '"
                    + resourceGroupId + "' is invalid: must be in same subscription as cluster.");
        }
        if (matcher.group(2).equalsIgnoreCase(context.resourceGroup())) {
            throw new InvalidProperty("properties.clusterProfile.resourceGroupId", "The provided resource group '"
                    + resourceGroupId + "' is invalid: must be different from resourceGroup of the OpenShift cluster "
                    + "object.");
        }
    }

    private static void validateServicePrincipal(Map<String, Object> servicePrincipal,
                                                 Map<String, Object> requestedPrincipal, boolean create) {
        String clientId = string(servicePrincipal, "clientId");
        if (clientId == null || !UUID_PATTERN.matcher(clientId).matches()) {
            throw new InvalidProperty("properties.servicePrincipalProfile.clientId",
                    "The provided client ID '" + (clientId == null ? "" : clientId) + "' is invalid.");
        }
        Object secret = requestedPrincipal.get("clientSecret");
        if (create && (!(secret instanceof String s) || s.isEmpty())) {
            throw new InvalidProperty("properties.servicePrincipalProfile.clientSecret",
                    "The provided client secret is invalid.");
        }
    }

    private static void validateCidr(String path, String field, String cidr, int largestPrefix, String kind,
                                     boolean create) {
        String target = path + "." + field;
        String[] parts = cidr == null ? new String[0] : cidr.split("/");
        int prefix;
        long address;
        try {
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid CIDR address: " + cidr);
            }
            prefix = Integer.parseInt(parts[1]);
            address = ipv4(parts[0]);
            if (prefix < 0 || prefix > 32) {
                throw new IllegalArgumentException("invalid CIDR address: " + cidr);
            }
        } catch (IllegalArgumentException e) {
            if (cidr != null && cidr.contains(":")) {
                throw new InvalidProperty(target, "The provided " + kind + " CIDR '" + cidr
                        + "' is invalid: must be IPv4.");
            }
            throw new InvalidProperty(target, "The provided " + kind + " CIDR '" + cidr + "' is invalid: '"
                    + "invalid CIDR address: " + cidr + "'.");
        }
        if (create) {
            for (String join : JOIN_CIDR_RANGES) {
                String[] j = join.split("/");
                if (overlaps(address, prefix, ipv4(j[0]), Integer.parseInt(j[1]))) {
                    throw new InvalidProperty("InvalidCIDRRange", path, "Azure Red Hat OpenShift uses 100.64.0.0/16, "
                            + "169.254.169.0/29, and 100.88.0.0/16 IP address ranges internally. Do not include this '"
                            + cidr + "' IP address range in any other CIDR definitions in your cluster.");
                }
            }
        }
        if (prefix > largestPrefix) {
            throw new InvalidProperty(target, "The provided vnet CIDR '" + cidr + "' is invalid: must be /"
                    + largestPrefix + " or larger.");
        }
        long network = address & mask(prefix);
        if (network != address) {
            throw new InvalidProperty("InvalidNetworkAddress", target, "The provided " + kind + " CIDR '" + cidr
                    + "' is invalid, expecting: '" + dotted(network) + "/" + prefix + "'.");
        }
    }

    private static List<Object> workerProfiles(Object value) {
        List<Map<String, Object>> requested = listOfMaps(value);
        if (value != null && requested.size() != 1) {
            throw new InvalidProperty("properties.workerProfiles", "There should be exactly one worker profile.");
        }
        Map<String, Object> worker = requested.isEmpty() ? new LinkedHashMap<>() : requested.getFirst();
        worker.putIfAbsent("name", "worker");
        worker.putIfAbsent("vmSize", DEFAULT_WORKER_VM_SIZE);
        worker.putIfAbsent("diskSizeGB", DEFAULT_WORKER_DISK_SIZE_GB);
        worker.putIfAbsent("count", DEFAULT_WORKER_COUNT);
        worker.putIfAbsent("encryptionAtHost", "Disabled");
        String path = "properties.workerProfiles['" + worker.get("name") + "']";
        if (!(worker.get("count") instanceof Number count) || count.intValue() < 0) {
            throw new InvalidProperty(path + ".count", "The provided worker count '" + worker.get("count")
                    + "' is invalid.");
        }
        if (!(worker.get("diskSizeGB") instanceof Number disk) || disk.intValue() < 128) {
            throw new InvalidProperty(path + ".diskSizeGB", "The provided worker disk size '"
                    + worker.get("diskSizeGB") + "' is invalid.");
        }
        oneOf(worker, "encryptionAtHost", path + ".encryptionAtHost", "The provided value '%s' is invalid.",
                "Disabled", "Enabled");
        return new ArrayList<>(List.of(worker));
    }

    private static List<Object> ingressProfiles(Object value) {
        List<Map<String, Object>> requested = listOfMaps(value);
        if (value != null && requested.size() != 1) {
            throw new InvalidProperty("properties.ingressProfiles", "There should be exactly one ingress profile.");
        }
        Map<String, Object> ingress = requested.isEmpty() ? new LinkedHashMap<>() : requested.getFirst();
        ingress.putIfAbsent("name", "default");
        ingress.putIfAbsent("visibility", "Public");
        String path = "properties.ingressProfiles['" + ingress.get("name") + "']";
        if (!"default".equals(ingress.get("name"))) {
            throw new InvalidProperty(path + ".name", "The provided ingress name '" + ingress.get("name")
                    + "' is invalid.");
        }
        oneOf(ingress, "visibility", path + ".visibility", "The provided visibility '%s' is invalid.",
                "Private", "Public");
        return new ArrayList<>(List.of(ingress));
    }

    private static boolean anyPublicIngress(Map<String, Object> props) {
        return listOfMaps(props.get("ingressProfiles")).stream().anyMatch(p -> "Public".equals(p.get("visibility")));
    }

    /** The request's properties without the read-only fields ARO ignores on input and without the secrets. */
    private static Map<String, Object> writable(Map<String, Object> requested) {
        Map<String, Object> props = deepCopy(requested == null ? Map.of() : requested);
        props.remove("provisioningState");
        props.remove("workerProfilesStatus");
        Map<String, Object> cluster = optionalProfile(props, "clusterProfile");
        if (cluster != null) {
            cluster.remove("pullSecret");
            cluster.remove("oidcIssuer");
        }
        Map<String, Object> console = optionalProfile(props, "consoleProfile");
        if (console != null) {
            console.remove("url");
        }
        Map<String, Object> servicePrincipal = optionalProfile(props, "servicePrincipalProfile");
        if (servicePrincipal != null) {
            servicePrincipal.remove("clientSecret");
        }
        Map<String, Object> apiserver = optionalProfile(props, "apiserverProfile");
        if (apiserver != null) {
            apiserver.remove("url");
            apiserver.remove("ip");
        }
        Map<String, Object> network = optionalProfile(props, "networkProfile");
        if (network != null) {
            cast(network.get("loadBalancerProfile")).remove("effectiveOutboundIps");
        }
        for (Map<String, Object> ingress : listOfMaps(props.get("ingressProfiles"))) {
            ingress.remove("ip");
        }
        Map<String, Object> identities = cast(cast(props.get("platformWorkloadIdentityProfile"))
                .get("platformWorkloadIdentities"));
        for (Object identity : identities.values()) {
            cast(identity).remove("clientId");
            cast(identity).remove("objectId");
        }
        return props;
    }

    /** The properties in the order the 2025-07-25 spec declares them, which is the order ARO serializes them in. */
    static Map<String, Object> order(Map<String, Object> props) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String key : List.of("provisioningState", "clusterProfile", "consoleProfile", "servicePrincipalProfile",
                "platformWorkloadIdentityProfile", "networkProfile", "masterProfile", "workerProfiles",
                "workerProfilesStatus", "apiserverProfile", "ingressProfiles")) {
            if (props.containsKey(key)) {
                ordered.put(key, props.get(key));
            }
        }
        props.forEach(ordered::putIfAbsent);
        return ordered;
    }

    /** A random domain the way {@code az aro create} picks one when none is given: a letter and seven alphanumerics. */
    static String randomId() {
        StringBuilder id = new StringBuilder();
        id.append(RANDOM_ID_FIRST.charAt(RANDOM.nextInt(RANDOM_ID_FIRST.length())));
        for (int i = 0; i < 7; i++) {
            id.append(RANDOM_ID_REST.charAt(RANDOM.nextInt(RANDOM_ID_REST.length())));
        }
        return id.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopy(Map<String, Object> source) {
        return MAPPER.convertValue(source, LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static void mergeInto(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            Object existing = target.get(entry.getKey());
            if (existing instanceof Map<?, ?> existingMap && entry.getValue() instanceof Map<?, ?> patchMap) {
                mergeInto((Map<String, Object>) existingMap, (Map<String, Object>) patchMap);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static Object at(Map<String, Object> props, String path) {
        Object node = props;
        for (String segment : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(segment);
        }
        return node;
    }

    private static boolean sameValue(String path, Object before, Object after) {
        if (before instanceof String a && after instanceof String b && path.endsWith("resourceGroupId")) {
            return a.equalsIgnoreCase(b);
        }
        return Objects.equals(MAPPER.valueToTree(before), MAPPER.valueToTree(after));
    }

    private static void oneOf(Map<String, Object> profile, String field, String target, String message,
                              String... allowed) {
        Object value = profile.get(field);
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new InvalidProperty(target, String.format(message, value));
    }

    private static Map<String, Object> profile(Map<String, Object> props, String key) {
        Map<String, Object> existing = optionalProfile(props, key);
        if (existing != null) {
            return existing;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        props.put(key, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalProfile(Map<String, Object> props, String key) {
        return props.get(key) instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cast(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
        }
        return result;
    }

    static String string(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s ? s : null;
    }

    private static long ipv4(String text) {
        String[] octets = text.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("not IPv4: " + text);
        }
        long value = 0;
        for (String octet : octets) {
            int n = Integer.parseInt(octet);
            if (n < 0 || n > 255) {
                throw new IllegalArgumentException("not IPv4: " + text);
            }
            value = (value << 8) | n;
        }
        return value;
    }

    private static long mask(int prefix) {
        return prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
    }

    private static boolean overlaps(long a, int aPrefix, long b, int bPrefix) {
        int common = Math.min(aPrefix, bPrefix);
        return (a & mask(common)) == (b & mask(common));
    }

    private static String dotted(long address) {
        return ((address >> 24) & 0xFF) + "." + ((address >> 16) & 0xFF) + "." + ((address >> 8) & 0xFF) + "."
                + (address & 0xFF);
    }

    /** A cluster's {@code clusterID}, which OpenShift generates as a random UUID. */
    static String newClusterUuid() {
        return UUID.randomUUID().toString();
    }
}
