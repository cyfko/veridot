package io.github.cyfko.veridot.trustroots.taas.server.attestation;

/**
 * Result of an attestation verification.
 *
 * @param valid        Whether the attestation proof was verified successfully.
 * @param authorityRef Opaque reference from the attestation authority (nullable if invalid).
 * @param reason       Human-readable reason if invalid, null if valid.
 */
public record AttestationResult(
    boolean valid,
    String authorityRef,
    String reason
) {
    /**
     * Creates a successful result.
     *
     * @param authorityRef the authority reference
     * @return a valid AttestationResult
     */
    public static AttestationResult success(String authorityRef) {
        return new AttestationResult(true, authorityRef, null);
    }

    /**
     * Creates a failed result.
     *
     * @param reason the failure reason
     * @return an invalid AttestationResult
     */
    public static AttestationResult failure(String reason) {
        return new AttestationResult(false, null, reason);
    }
}
