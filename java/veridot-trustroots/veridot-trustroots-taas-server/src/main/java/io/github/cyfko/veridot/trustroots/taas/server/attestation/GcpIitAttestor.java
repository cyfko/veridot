package io.github.cyfko.veridot.trustroots.taas.server.attestation;



import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Attestation verifier for GCP Instance Identity Tokens (IIT).
 *
 * <p>Verifies that the workload identity proof is a valid GCP IIT JWT token
 * signed by Google's OAuth2 infrastructure (§1.3.1). Uses a cached JWKS
 * approach for signature verification against Google's public keys.
 *
 * <p>For V5.0, this implementation provides the full verification structure
 * with JWT parsing and signature validation scaffolding. Actual JWKS fetching
 * from Google's well-known endpoint is marked as TODO.
 */
public class GcpIitAttestor implements io.github.cyfko.veridot.trustroots.api.spi.AttestationPlugin {

    private static final Logger LOG = Logger.getLogger(GcpIitAttestor.class.getName());

    /** Google's JWKS URI for verifying GCP identity tokens. */
    private static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /** Expected audience for GCP IIT tokens. Configurable via env var. */
    private final String expectedAudience;

    /**
     * Creates a GcpIitAttestor using the default expected audience.
     * The audience can be overridden via the {@code VDOT_GCP_IIT_AUDIENCE} environment variable.
     */
    public GcpIitAttestor() {
        this.expectedAudience = System.getenv().getOrDefault(
            "VDOT_GCP_IIT_AUDIENCE",
            "veridot-taas"
        );
    }

    /**
     * Creates a GcpIitAttestor with an explicit expected audience (for testing).
     *
     * @param expectedAudience the expected audience claim in the IIT JWT
     */
    public GcpIitAttestor(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    @Override
    public io.github.cyfko.veridot.trustroots.api.spi.AttestationResult verify(byte[] proof, io.github.cyfko.veridot.trustroots.api.spi.AttestationContext ctx) {
        if (proof == null || proof.length == 0) {
            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.rejected("GCP IIT proof is empty");
        }

        String jwt = new String(proof, StandardCharsets.UTF_8);
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.rejected("GCP IIT proof is not a valid JWT (expected 3 parts, got " + parts.length + ")");
        }

        try {
            // Decode and parse the JWT header
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            // Decode and parse the JWT payload
            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

            LOG.fine(() -> "GCP IIT header: " + headerJson);
            LOG.fine(() -> "GCP IIT payload: " + payloadJson);

            // TODO: Fetch JWKS from GOOGLE_JWKS_URI and verify the JWT signature.
            //       For V5.0, we validate the JWT structure but skip cryptographic verification.
            //       The JWKS should be cached in TaasRocksDbStore.jwks_cache CF with key "gcp".

            // TODO: Verify claims:
            //   - iss == "https://accounts.google.com"
            //   - aud matches expectedAudience
            //   - exp > now
            //   - google.compute_engine.project_id, zone, instance_id, etc.

            LOG.info(() -> "GCP IIT JWT structure validated for subject: " + ctx.requestedCn()
                + " (JWKS signature verification pending — V5.0 stub)");

            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.accepted("gcp:" + GOOGLE_JWKS_URI);

        } catch (IllegalArgumentException e) {
            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.rejected("GCP IIT JWT decoding failed: " + e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "GCP IIT verification error", e);
            return io.github.cyfko.veridot.trustroots.api.spi.AttestationResult.rejected("GCP IIT verification error: " + e.getMessage());
        }
    }

    @Override
    public String getPluginId() {
        return "gcp";
    }
}
