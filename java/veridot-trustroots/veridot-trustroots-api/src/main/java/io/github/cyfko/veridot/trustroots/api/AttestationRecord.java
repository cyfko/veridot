package io.github.cyfko.veridot.trustroots.api;

import java.time.Instant;

/**
 * Record representing a successful attestation verification event.
 *
 * <p>Stored in the TAAS attestation log for audit purposes (§5.4).
 *
 * @param subject     Subject identifier of the attested TrustEntry.
 * @param version     Version of the attested TrustEntry.
 * @param plugin      Name of the attestation plugin used.
 * @param authorityRef Opaque reference from the attestation authority.
 * @param verifiedAt  Timestamp of attestation verification.
 */
public record AttestationRecord(
    String subject,
    long version,
    String plugin,
    String authorityRef,
    Instant verifiedAt
) {}
