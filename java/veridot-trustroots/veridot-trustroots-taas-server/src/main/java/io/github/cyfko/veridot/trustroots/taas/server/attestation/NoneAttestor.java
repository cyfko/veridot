package io.github.cyfko.veridot.trustroots.taas.server.attestation;

import io.github.cyfko.veridot.trustroots.api.TrustEntry;

/**
 * No-op attestation verifier for development and testing environments.
 *
 * <p>Accepts all attestation proofs unconditionally. This plugin is only
 * available when {@code VDOT_ATTESTATION_SKIP=true} is set in the environment.
 * If the environment variable is not set, verification always fails with
 * an error indicating attestation is required.
 */
public class NoneAttestor implements AttestationVerifier {

    private final boolean skipEnabled;

    /**
     * Creates a NoneAttestor that checks the environment for opt-in.
     */
    public NoneAttestor() {
        this.skipEnabled = "true".equalsIgnoreCase(System.getenv("VDOT_ATTESTATION_SKIP"))
                || "true".equalsIgnoreCase(System.getProperty("VDOT_ATTESTATION_SKIP"));
    }

    /**
     * Creates a NoneAttestor with explicit skip configuration (for testing).
     *
     * @param skipEnabled whether attestation verification should be skipped
     */
    public NoneAttestor(boolean skipEnabled) {
        this.skipEnabled = skipEnabled;
    }

    @Override
    public AttestationResult verify(TrustEntry entry, byte[] proof) {
        if (!skipEnabled) {
            throw new IllegalStateException(
                "[V5501] Attestation is required but VDOT_ATTESTATION_SKIP is not set to true. "
                + "Set VDOT_ATTESTATION_SKIP=true to disable attestation verification (dev/test only).");
        }
        return AttestationResult.success("none:skip");
    }

    @Override
    public String pluginName() {
        return "none";
    }
}
