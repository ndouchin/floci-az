package io.floci.az.services.aks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;

/**
 * {@code POST /_floci/aks/clusters/{instanceId}/token-review}: the authentication webhook a cluster's k3s API server
 * is configured with ({@code authentication-token-webhook-config-file}). Floci-only routes, reached by the cluster,
 * never by Azure clients.
 */
@Path("_floci/aks/clusters/{instanceId}")
public class AksWebhookController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AksWebhooks webhooks;

    @Inject
    public AksWebhookController(AksWebhooks webhooks) {
        this.webhooks = webhooks;
    }

    @POST
    @Path("token-review")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response tokenReview(@PathParam("instanceId") String instanceId, byte[] body) {
        return Response.ok(webhooks.tokenReview(instanceId, read(body))).build();
    }

    static JsonNode read(byte[] body) {
        try {
            return body == null || body.length == 0 ? MAPPER.createObjectNode() : MAPPER.readTree(body);
        } catch (IOException malformed) {
            // A body that is not JSON reviews as an empty request, which every webhook refuses.
            return MAPPER.createObjectNode();
        }
    }
}
