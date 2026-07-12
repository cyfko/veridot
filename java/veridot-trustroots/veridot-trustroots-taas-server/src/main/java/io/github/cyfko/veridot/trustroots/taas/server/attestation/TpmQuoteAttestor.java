package io.github.cyfko.veridot.trustroots.taas.server.attestation;

import io.github.cyfko.veridot.trustroots.api.TrustEntry;

import java.util.logging.Logger;

/**
 * Stub attestation verifier for TPM 2.0 Quote verification.
 *
 * <p>This is a V5.0 stub implementation. Full TPM Quote verification
 * (AIK signature validation, PCR value checking, nonce binding) will be
 * implemented in a future release.
 *
 * <p><strong>Warning:</strong> This implementation always returns a failure
 * result in V5.0. It is included to reserve the plugin name and establish
 * the SPI contract for future TPM attestation support.
 */
public class TpmQuoteAttestor implements AttestationVerifier {

    private static final Logger LOG = Logger.getLogger(TpmQuoteAttestor.class.getName());

    /**
     * Creates a TpmQuoteAttestor.
     * Logs a warning that this is a V5.0 stub.
     */
    public TpmQuoteAttestor() {
        LOG.warning("TpmQuoteAttestor is a V5.0 stub — TPM quote verification is not yet implemented. "
            + "All verification attempts will fail.");
    }

    @Override
    public AttestationResult verify(TrustEntry entry, byte[] proof) {
        LOG.warning(() -> "TPM quote verification requested for subject: " + entry.subject()
            + " but TPM attestation is not yet implemented (V5.0 stub)");

        // TODO: Implement TPM 2.0 quote verification:
        //   1. Parse the TPM quote structure from proof bytes
        //   2. Verify AIK (Attestation Identity Key) signature over the quote
        //   3. Validate PCR (Platform Configuration Register) values against expected policy
        //   4. Check nonce binding to prevent replay attacks
        //   5. Verify the AIK certificate chain against a trusted TPM CA

        return AttestationResult.failure(
            "TPM quote verification is not yet implemented (V5.0 stub). "
            + "Use 'k8s', 'gcp', or 'none' attestation plugins instead.");
    }

    @Override
    public String pluginName() {
        return "tpm";
    }
}
