package io.floci.az.core.docker;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;

import java.util.List;
import java.util.Map;

/**
 * Immutable specification for a Docker container to be created.
 * Use {@link ContainerBuilder} to construct instances of this record.
 *
 * @param image Docker image name (required)
 * @param name Container name (optional, Docker generates one if null)
 * @param env Environment variables as "KEY=value" strings
 * @param cmd Command to run (overrides image CMD)
 * @param entrypoint Entrypoint to use (overrides image ENTRYPOINT)
 * @param memoryBytes Memory limit in bytes (null = no limit)
 * @param portBindings Map of container port to host port (0 = dynamic allocation)
 * @param exposedPorts Ports to expose (required for port bindings)
 * @param networkMode Docker network name or mode (null = default bridge)
 * @param mounts Volume mounts (named volumes, bind mounts, tmpfs)
 * @param binds Legacy bind mounts (prefer mounts for new code)
 * @param extraHosts Extra /etc/hosts entries as "hostname:ip" strings
 * @param labels Extra Docker labels merged over the default emulator labels
 * @param logConfig Docker log driver configuration (null = daemon default)
 * @param privileged Whether to run the container in privileged mode (required for k3s)
 * @param cgroupnsMode Docker cgroup namespace mode (for example, "host")
 * @param dnsServers DNS server IPs to inject into the container (e.g. floci-az's embedded DNS)
 * @param workingDir Working directory inside the container (overrides image WORKDIR)
 * @param user User the container process runs as, formatted "uid[:gid]" (null = image USER)
 * @param groupAdd Supplementary group IDs added to the container process
 * @param hostname Hostname inside the container (null = Docker's default, the short container id)
 * @param nanoCpus CPU limit in billionths of a CPU (null = no limit)
 * @param joinNetworkAtCreate Join {@code networkMode} when the container is created even with port bindings,
 *                            so it is the container's primary network (default route and first address)
 */
public record ContainerSpec(
        String image,
        String name,
        List<String> env,
        List<String> cmd,
        List<String> entrypoint,
        Long memoryBytes,
        Map<Integer, Integer> portBindings,
        List<Integer> exposedPorts,
        String networkMode,
        List<Mount> mounts,
        List<Bind> binds,
        List<String> extraHosts,
        Map<String, String> labels,
        LogConfig logConfig,
        boolean privileged,
        String cgroupnsMode,
        List<String> dnsServers,
        String workingDir,
        String user,
        List<String> groupAdd,
        String hostname,
        Long nanoCpus,
        boolean joinNetworkAtCreate
) {
    /** The spec without a hostname, a CPU limit or a primary network, the shape it had before they existed. */
    public ContainerSpec(String image, String name, List<String> env, List<String> cmd, List<String> entrypoint,
                         Long memoryBytes, Map<Integer, Integer> portBindings, List<Integer> exposedPorts,
                         String networkMode, List<Mount> mounts, List<Bind> binds, List<String> extraHosts,
                         Map<String, String> labels, LogConfig logConfig, boolean privileged, String cgroupnsMode,
                         List<String> dnsServers, String workingDir, String user, List<String> groupAdd) {
        this(image, name, env, cmd, entrypoint, memoryBytes, portBindings, exposedPorts, networkMode, mounts, binds,
                extraHosts, labels, logConfig, privileged, cgroupnsMode, dnsServers, workingDir, user, groupAdd,
                null, null, false);
    }

    /**
     * Creates a minimal spec with just the image name.
     * All other fields will be null or empty collections.
     */
    public ContainerSpec(String image) {
        this(image, null, List.of(), null, null, null, Map.of(), List.of(), null,
                List.of(), List.of(), List.of(), Map.of(), null, false, null, List.of(),
                null, null, List.of());
    }

    /**
     * Returns true if this spec has any port bindings configured.
     */
    public boolean hasPortBindings() {
        return portBindings != null && !portBindings.isEmpty();
    }

    /**
     * Returns true if this spec has a memory limit configured.
     */
    public boolean hasMemoryLimit() {
        return memoryBytes != null && memoryBytes > 0;
    }

    /**
     * Returns true if this spec has a CPU limit configured.
     */
    public boolean hasCpuLimit() {
        return nanoCpus != null && nanoCpus > 0;
    }

    /**
     * Returns true if log rotation is configured.
     */
    public boolean hasLogConfig() {
        return logConfig != null;
    }
}
