package io.floci.az.services.aro;

import io.floci.az.core.arm.ArmLane;
import io.floci.az.core.arm.ArmResourceType;
import io.floci.az.core.arm.ArmSurface;
import io.floci.az.core.arm.ArmSurfaceProvider;
import io.floci.az.core.arm.LocationPolicy;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/**
 * The ARM resource types {@link AroHandler} serves. The 2025-07-25 spec puts no pattern on the {@code resourceName}
 * of an OpenShift cluster, so the type declares no name rule.
 */
@ApplicationScoped
public class AroArmSurface implements ArmSurfaceProvider {

    static final String NAMESPACE = "Microsoft.RedHatOpenShift";

    private static final List<ArmSurface> SURFACES = List.of(
        new ArmSurface(NAMESPACE, ArmLane.FILTER, List.of(
            ArmResourceType.resourceGroup(NAMESPACE, "openShiftClusters", LocationPolicy.REQUIRED))));

    @Override
    public List<ArmSurface> armSurfaces() {
        return SURFACES;
    }
}
