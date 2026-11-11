package io.floci.az.services.aks;

import io.floci.az.services.aks.AksVmSizes.VmSize;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AksVmSizesTest {

    @Test
    void documentedGeneralPurposeSizes() {
        assertEquals(new VmSize(2, 7, 0), AksVmSizes.of("Standard_DS2_v2"));
        assertEquals(new VmSize(4, 16, 0), AksVmSizes.of("Standard_D4s_v5"));
        assertEquals(new VmSize(8, 32, 0), AksVmSizes.of("standard_d8s_v5"));
    }

    @Test
    void documentedGpuSizesCarryTheirGpuCount() {
        assertEquals(new VmSize(4, 28, 1), AksVmSizes.of("Standard_NC4as_T4_v3"));
        assertEquals(new VmSize(64, 440, 4), AksVmSizes.of("Standard_NC64as_T4_v3"));
        assertEquals(new VmSize(48, 440, 2), AksVmSizes.of("Standard_NC48ads_A100_v4"));
        assertEquals(new VmSize(96, 1900, 8), AksVmSizes.of("Standard_ND96isr_H100_v5"));
        assertEquals(new VmSize(72, 880, 2), AksVmSizes.of("Standard_NV72ads_A10_v5"));
    }

    @Test
    void anUnlistedSizeTakesItsVcpusFromTheNameAndItsFamilysMemoryPerVcpu() {
        assertEquals(new VmSize(16, 64, 0), AksVmSizes.of("Standard_D16ds_v6"));
        assertEquals(new VmSize(4, 32, 0), AksVmSizes.of("Standard_E4s_v5"));
        assertEquals(new VmSize(8, 16, 0), AksVmSizes.of("Standard_F8s_v2"));
    }

    @Test
    void anUnlistedNFamilySizeIsAGpuSizeWithOneGpu() {
        assertEquals(1, AksVmSizes.of("Standard_NC12s_v9").gpus());
    }

    @Test
    void amdGpuSizesHaveNoNvidiaGpu() {
        assertEquals(0, AksVmSizes.of("Standard_NV16as_v4").gpus());
        assertEquals(0, AksVmSizes.of("Standard_ND96isr_MI300X_v5").gpus());
    }

    @Test
    void anUnparseableSizeFallsBackToTheDefaultSize() {
        assertEquals(new VmSize(2, 7, 0), AksVmSizes.of("whatever"));
        assertEquals(new VmSize(2, 7, 0), AksVmSizes.of(null));
    }

    @Test
    void memoryIsGivenInBytesForTheContainerLimit() {
        assertEquals(7L * 1024 * 1024 * 1024, AksVmSizes.of("Standard_DS2_v2").memoryBytes());
    }
}
