package io.floci.az.services.authorization;

import java.util.Locale;

/**
 * A parsed role path: {@code {scope}/providers/Microsoft.Authorization/{roleAssignments|roleDefinitions}[/{name}]}.
 * Role resources are extension resources, so the scope is whatever precedes the last
 * {@code /providers/Microsoft.Authorization/} segment, in the caller's casing.
 *
 * @param scope     the scope with a leading slash and no trailing one; {@code ""} at the tenant root
 * @param type      the role resource type, or null when the segment names another Microsoft.Authorization type
 * @param name      the resource name, or null for a collection
 * @param remainder anything after the name
 */
record AuthorizationPath(String scope, Type type, String name, String remainder) {

    private static final String MARKER = "/providers/microsoft.authorization/";

    enum Type {
        ROLE_ASSIGNMENT("roleAssignments"),
        ROLE_DEFINITION("roleDefinitions");

        final String segment;

        Type(String segment) {
            this.segment = segment;
        }

        String armType() {
            return "Microsoft.Authorization/" + segment;
        }

        static Type fromSegment(String segment) {
            for (Type type : values()) {
                if (type.segment.equalsIgnoreCase(segment)) {
                    return type;
                }
            }
            return null;
        }
    }

    static AuthorizationPath parse(String path) {
        String normalized = stripQuery(path.startsWith("/") ? path : "/" + path);
        int marker = normalized.toLowerCase(Locale.ROOT).lastIndexOf(MARKER);
        if (marker < 0) {
            return null;
        }
        String scope = stripTrailingSlashes(normalized.substring(0, marker));
        String[] rest = stripTrailingSlashes(normalized.substring(marker + MARKER.length())).split("/", 3);
        Type type = rest.length > 0 && !rest[0].isEmpty() ? Type.fromSegment(rest[0]) : null;
        String name = rest.length > 1 && !rest[1].isEmpty() ? rest[1] : null;
        String remainder = rest.length > 2 ? rest[2] : "";
        return new AuthorizationPath(scope, type, name, remainder);
    }

    /** True when the last Microsoft.Authorization segment of {@code path} names a role resource type. */
    static boolean isRolePath(String path) {
        AuthorizationPath parsed = parse(path);
        return parsed != null && parsed.type() != null
                && !parsed.remainder().toLowerCase(Locale.ROOT).startsWith("providers/");
    }

    /** The ARM id of this path's resource. */
    String resourceId() {
        return scope + "/providers/Microsoft.Authorization/" + type.segment + "/" + name;
    }

    /** The subscription root of the scope ({@code /subscriptions/{sub}}), or {@code ""} when it names none. */
    String subscriptionScope() {
        String[] segments = scope.split("/");
        if (segments.length > 2 && "subscriptions".equalsIgnoreCase(segments[1])) {
            return "/subscriptions/" + segments[2];
        }
        return "";
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private static String stripTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
