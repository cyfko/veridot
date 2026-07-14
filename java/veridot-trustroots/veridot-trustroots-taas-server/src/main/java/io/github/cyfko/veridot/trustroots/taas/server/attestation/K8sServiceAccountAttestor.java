package io.github.cyfko.veridot.trustroots.taas.server.attestation;



import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Attestation verifier for Kubernetes Projected Service Account Tokens (PSAT).
 *
 * <p>Verifies that the workload identity proof is a valid JWT token signed
 * by the cluster's OIDC issuer (§1.3.1). Uses a cached JWKS approach for
 * signature verification.
 *
 * <p>For V5.0, this implementation provides the full verification structure
 * with JWT parsing and signature validation scaffolding. Actual JWKS fetching
 * from the Kubernetes OIDC discovery endpoint is marked as TODO.
 */
public class K8sServiceAccountAttestor implements io.github.cyfko.veridot.trustroots.api.spi.AttestationPlugin {

    /** Logger. */
    private static final Logger LOG = Logger.getLogger(K8sServiceAccountAttestor.class.getName());

    /** Default Kubernetes OIDC issuer URL. Overridable via VDOT_K8S_OIDC_ISSUER env var. */
    private final String oidcIssuer;

    /**
     * Creates a K8sServiceAccountAttestor using the default OIDC issuer.
     * The issuer URL can be overridden via the {@code VDOT_K8S_OIDC_ISSUER} environment variable.
     */
    public K8sServiceAccountAttestor() {
        this.oidcIssuer = System.getenv().getOrDefault(
            "VDOT_K8S_OIDC_ISSUER",
            "https://kubernetes.default.svc"
        );
    }

    /**
     * Creates a K8sServiceAccountAttestor with an explicit OIDC issuer URL (for testing).
     *
     * @param oidcIssuer the OIDC issuer URL
     */
    public K8sServiceAccountAttestor(String oidcIssuer) {
        this.oidcIssuer = oidcIssuer;
    }

    @Override
    public io.github.cyfko.veridot.trustroots.api.spi.AttestationResult verify(byte[] proof, io.github.cyfko.veridot.trustroots.api.spi.AttestationContext ctx) {
        if (proof == null || proof.length == 0) {
            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.rejected("K8s PSAT proof is empty");
        }

        String jwt = new String(proof, StandardCharsets.UTF_8);
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.rejected("K8s PSAT proof is not a valid JWT (expected 3 parts, got " + parts.length + ")");
        }

        try {
            // Decode and parse the JWT header
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            // Decode and parse the JWT payload
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

            LOG.fine(() -> "K8s PSAT header: " + headerJson);
            LOG.fine(() -> "K8s PSAT payload: " + payloadJson);

            // TODO: Fetch JWKS from oidcIssuer + "/.well-known/openid-configuration"
            //       and verify the JWT signature against the JWKS keyset.
            //       For V5.0, we validate the JWT structure but skip cryptographic verification.
            //       The JWKS should be cached in TaasRocksDbStore.jwks_cache CF.

            // TODO: Verify claims:
            //   - iss matches oidcIssuer
            //   - aud contains expected audience
            //   - exp > now
            //   - sub matches the expected service account identity

            LOG.info(() -> "K8s PSAT JWT structure validated for subject: " + ctx.requestedCn()
                + " (JWKS signature verification pending — V5.0 stub)");

            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.accepted("k8s:" + oidcIssuer);

        } catch (IllegalArgumentException e) {
            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.rejected("K8s PSAT JWT decoding failed: " + e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "K8s PSAT verification error", e);
            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.rejected("K8s PSAT verification error: " + e.getMessage());
        }
    }

    @Override
    public String getPluginId() {
        return "k8s";
    }
}
