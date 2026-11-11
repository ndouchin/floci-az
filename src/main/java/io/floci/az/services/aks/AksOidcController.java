package io.floci.az.services.aks;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * {@code GET /_floci/aks/oidc/{region}/{tenant}/{key}/.well-known/openid-configuration} and {@code .../openid/v1/jwks}:
 * a cluster's OIDC issuer documents for verifiers that can't resolve {@code {region}.oic.prod-aks.azure.com} to
 * floci-az. The documents are the ones the AKS host serves; the issuer and JWKS URL they name stay AKS's.
 */
@Path("_floci/aks/oidc/{region}/{tenant}/{key}")
@Produces(MediaType.APPLICATION_JSON)
public class AksOidcController {

    private final AksOidcIssuers issuers;

    @Inject
    public AksOidcController(AksOidcIssuers issuers) {
        this.issuers = issuers;
    }

    @GET
    @Path(".well-known/openid-configuration")
    public Response discovery(@PathParam("region") String region, @PathParam("tenant") String tenant,
                              @PathParam("key") String key) {
        return answer(region, tenant + "/" + key + "/.well-known/openid-configuration");
    }

    @GET
    @Path("openid/v1/jwks")
    public Response jwks(@PathParam("region") String region, @PathParam("tenant") String tenant,
                         @PathParam("key") String key) {
        return answer(region, tenant + "/" + key + "/openid/v1/jwks");
    }

    private Response answer(String region, String path) {
        return issuers.document(region, path)
                .map(document -> Response.ok(document).build())
                .orElseGet(() -> Response.status(404).build());
    }
}
