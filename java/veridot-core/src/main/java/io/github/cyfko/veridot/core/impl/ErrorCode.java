package io.github.cyfko.veridot.core.impl;

/**
 * Protocol V5 Error Codes — Appendix C.5 of PROTOCOL_V5.md.
 *
 * <p>Each code uniquely identifies an error condition in the Veridot V5 protocol.
 * Codes are grouped by category:
 * <ul>
 *   <li>V50xx — Envelope errors (§16.1)</li>
 *   <li>V51xx — Trust errors (§16.2)</li>
 *   <li>V52xx — Token lifecycle errors (§8.2)</li>
 *   <li>V53xx — Watermark/Capacity errors (§16.3)</li>
 *   <li>V54xx — Capability errors (§16.4)</li>
 *   <li>V55xx — Encryption errors (§16.5)</li>
 *   <li>V56xx — PQ Encryption errors (§14.5)</li>
 *   <li>V57xx — State Transparency errors (§18)</li>
 *   <li>V58xx — Infrastructure errors (§5.8)</li>
 * </ul>
 */
public enum ErrorCode {

    // ── Category 1: Envelope (§16.1) ─────────────────────────────────
    INVALID_ENVELOPE("V5001"),
    UNREGISTERED_ENTRY_TYPE("V5002"),
    INVALID_IDENTIFIER_LENGTH("V5003"),
    INVALID_PAYLOAD_LENGTH("V5004"),
    RESERVED_FLAG_SET("V5005"),
    INVALID_SCOPE_GRAMMAR("V5006"),
    MALFORMED_PAYLOAD("V5007"),

    // ── Category 2: Trust (§16.2) ────────────────────────────────────
    TRUST_RESOLUTION_FAILED("V5101"),
    SIGNATURE_INVALID("V5102"),
    ENTRY_EXPIRED("V5103"),
    ALGORITHM_MISMATCH("V5104"),
    ATTESTATION_REQUIRED("V5105"),
    ATTESTATION_FAILED("V5106"),
    ATTESTATION_AUTHORITY_UNREACHABLE("V5107"),
    SUBJECT_FORMAT_INVALID("V5108"),
    TRUST_ENTRY_REVOKED("V5109"),
    COMPACT_SIG_FLAG_MISMATCH("V5110"),
    INVALID_SCOPE_FORMAT("V5111"),
    COMPACT_SIG_MISMATCH("V5112"),

    // ── Category 2b: Token Lifecycle (§8.2) ──────────────────────────
    LIVENESS_INVALID("V5202"),
    TOKEN_EXPIRED("V5203"),

    // ── Category 3: Watermark / Capacity (§16.3) ─────────────────────
    VERSION_REJECTED("V5301"),
    CAPACITY_EXCEEDED("V5302"),
    CAPACITY_CONTENTION("V5303"),
    WATERMARK_RECOVERY_IN_PROGRESS("V5304"),

    // ── Category 4: Capability (§16.4) ───────────────────────────────
    DELEGATION_DEPTH_EXCEEDED("V5401"),
    CIRCULAR_DELEGATION("V5402"),
    NO_CAPABILITY("V5403"),
    OPERATION_DENIED("V5404"),
    SCOPE_MISMATCH("V5405"),
    CAPABILITY_EXPIRED("V5406"),

    // ── Category 5: Encryption (§16.5) ───────────────────────────────
    DECRYPTION_FAILED("V5501"),
    NOT_A_RECIPIENT("V5502"),
    KEY_VERSION_NOT_FOUND("V5503"),

    // ── Category 6: PQ Encryption (§14.5) ────────────────────────────
    PQ_KEY_MISSING("V5601"),
    HYBRID_UNWRAP_FAILED("V5602"),

    // ── Category 7: State Transparency (§18) ─────────────────────────
    DIGEST_SIGNATURE_INVALID("V5701"),
    BROKER_OMISSION_SUSPECTED("V5702"),
    LIVENESS_GAP_DETECTED("V5703"),
    CAPABILITY_VERSION_MISMATCH("V5704"),

    // ── Category 8: Infrastructure (§5.8) ─────────────────────────────
    BROKER_UNREACHABLE("V5801"),
    FENCE_SUPERSEDED("V5802"),
    ENTRY_NOT_FOUND("V5803");

    public final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    /**
     * Returns the severity level associated with this error code.
     *
     * @return the severity: CRITICAL for V5701/V5702, WARNING for V5703/V5704, ERROR for all others
     */
    public Severity severity() {
        return switch (this) {
            case DIGEST_SIGNATURE_INVALID, BROKER_OMISSION_SUSPECTED -> Severity.CRITICAL;
            case LIVENESS_GAP_DETECTED, CAPABILITY_VERSION_MISMATCH, FENCE_SUPERSEDED -> Severity.WARNING;
            default -> Severity.ERROR;
        };
    }

    /**
     * Severity levels for Veridot V5 error codes.
     */
    public enum Severity {
        /** Requires immediate operator intervention. */
        CRITICAL,
        /** Protocol violation or processing failure. */
        ERROR,
        /** Informational alert, possible anomaly. */
        WARNING
    }
}
