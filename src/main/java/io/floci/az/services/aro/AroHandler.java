package io.floci.az.services.aro;

import io.floci.az.config.EmulatorConfig;
import io.floci.az.core.AzureRequest;
import io.floci.az.core.AzureServiceHandler;
import io.floci.az.core.ServiceRoutes;
import io.floci.az.core.arm.ArmErrors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** Azure Red Hat OpenShift ({@code Microsoft.RedHatOpenShift}) management-plane requests. */
@ApplicationScoped
public class AroHandler implements AzureServiceHandler {

    private final EmulatorConfig config;

    @Inject
    public AroHandler(EmulatorConfig config) {
        this.config = config;
    }

    @Override
    public String getServiceType() {
        return "aro";
    }

    @Override
    public boolean enabled(String serviceType) {
        return config.services().aro().enabled();
    }

    @Override
    public ServiceRoutes routes() {
        return ServiceRoutes.builder()
                .provider(AroArmSurface.NAMESPACE)
                .build();
    }

    @Override
    public Response handle(AzureRequest req) {
        return ArmErrors.error(404, "NotFound", "The requested path is not served.");
    }
}
