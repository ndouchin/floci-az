package io.floci.az.services.aks;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * vCPUs, memory and NVIDIA GPUs of the Azure VM sizes agent pools name, used to size node containers and to tell
 * GPU nodes apart. Listed sizes come from the Azure VM size pages; any other size takes its vCPU count from the
 * name (the number after the family letters) and its family's usual memory per vCPU (8 GiB for E, 2 GiB for F,
 * 4 GiB otherwise), and the N families count one GPU.
 */
final class AksVmSizes {

    record VmSize(int vcpus, double memoryGiB, int gpus) {
        long memoryBytes() {
            return Math.round(memoryGiB * 1024 * 1024 * 1024);
        }
    }

    static final VmSize DEFAULT = new VmSize(2, 7, 0);

    private static final Map<String, VmSize> KNOWN = Map.ofEntries(
            entry("standard_ds1_v2", new VmSize(1, 3.5, 0)),
            entry("standard_ds2_v2", new VmSize(2, 7, 0)),
            entry("standard_ds3_v2", new VmSize(4, 14, 0)),
            entry("standard_ds4_v2", new VmSize(8, 28, 0)),
            entry("standard_ds5_v2", new VmSize(16, 56, 0)),
            entry("standard_d2s_v5", new VmSize(2, 8, 0)),
            entry("standard_d4s_v5", new VmSize(4, 16, 0)),
            entry("standard_d8s_v5", new VmSize(8, 32, 0)),
            entry("standard_d16s_v5", new VmSize(16, 64, 0)),
            entry("standard_d32s_v5", new VmSize(32, 128, 0)),
            entry("standard_nc4as_t4_v3", new VmSize(4, 28, 1)),
            entry("standard_nc8as_t4_v3", new VmSize(8, 56, 1)),
            entry("standard_nc16as_t4_v3", new VmSize(16, 110, 1)),
            entry("standard_nc64as_t4_v3", new VmSize(64, 440, 4)),
            entry("standard_nc24ads_a100_v4", new VmSize(24, 220, 1)),
            entry("standard_nc48ads_a100_v4", new VmSize(48, 440, 2)),
            entry("standard_nc96ads_a100_v4", new VmSize(96, 880, 4)),
            entry("standard_nd96isr_h100_v5", new VmSize(96, 1900, 8)),
            entry("standard_nv36ads_a10_v5", new VmSize(36, 440, 1)),
            entry("standard_nv36adms_a10_v5", new VmSize(36, 880, 1)),
            entry("standard_nv72ads_a10_v5", new VmSize(72, 880, 2)));

    private static final Pattern NAME = Pattern.compile("standard_([a-z]+?)(\\d+)[a-z]*(?:_.*)?");
    private static final double GIB_PER_VCPU = 4;
    private static final Map<Character, Double> FAMILY_GIB_PER_VCPU = Map.of('e', 8d, 'f', 2d);

    private AksVmSizes() {
    }

    static VmSize of(String vmSize) {
        if (vmSize == null) {
            return DEFAULT;
        }
        String key = vmSize.trim().toLowerCase(Locale.ROOT);
        VmSize known = KNOWN.get(key);
        if (known != null) {
            return known;
        }
        Matcher m = NAME.matcher(key);
        if (!m.matches()) {
            return DEFAULT;
        }
        int vcpus = Integer.parseInt(m.group(2));
        char family = m.group(1).charAt(0);
        int gpus = family == 'n' ? 1 : 0;
        return new VmSize(vcpus, vcpus * FAMILY_GIB_PER_VCPU.getOrDefault(family, GIB_PER_VCPU), gpus);
    }
}
