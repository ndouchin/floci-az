package io.floci.az.services.entra;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * The signing keys of an OIDC issuer the emulator itself runs (an AKS cluster's service account issuer), resolved
 * in-process: the issuer URL is never fetched, so an assertion from such an issuer verifies offline.
 */
public interface OidcIssuerKeys {

    /** The JWKS of {@code issuer}, when this source runs it. */
    Optional<JsonNode> jwks(String issuer);
}
