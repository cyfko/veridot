package io.github.cyfko.veridot.trustroots.api;

import java.time.Instant;

/**
 * Record representing a security alert raised during TAAS operations.
 *
 * <p>Stored in the TAAS security alerts log for audit purposes.
 * Alerts are raised when attestation re-validation fails on a rotation
 * (not on first registration).
 *
 * @param subject     Subject identifier of the TrustEntry that triggered the alert.
 * @param version     Version of the TrustEntry.
 * @param alertType   Type of security alert (e.g. "ATTESTATION_FAILED").
 * @param reason      Human-readable reason for the alert.
 * @param detectedAt  Timestamp when the alert was detected.
 */
public record SecurityAlert(
    String subject,
    long version,
    String alertType,
    String reason,
    Instant detectedAt
) {}
