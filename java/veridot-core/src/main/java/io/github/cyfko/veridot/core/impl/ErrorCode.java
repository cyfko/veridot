package io.github.cyfko.veridot.core.impl;

/**
 * Protocol V4 Error Codes as specified in Appendix B of PROTOCOL_V4.md.
 */
public enum ErrorCode {
    INVALID_ENVELOPE("V4001"),
    UNREGISTERED_ENTRY_TYPE("V4002"),
    INVALID_IDENTIFIER_LENGTH("V4003"),
    INVALID_PAYLOAD_LENGTH("V4004"),
    RESERVED_FLAG_SET("V4005"), // includes COMPACT_SIG mismatch
    INVALID_SCOPE_GRAMMAR("V4006"),
    MALFORMED_PAYLOAD("V4007"),
    TRUST_RESOLUTION_FAILED("V4101"),
    CAPABILITY_NOT_FOUND("V4102"),
    CAPABILITY_EXPIRED("V4103"),
    DELEGATION_DEPTH_EXCEEDED("V4104"),
    STALE_VERSION("V4201"), // includes version = 0
    LIVENESS_NOT_ESTABLISHED("V4202"),
    KEY_EPOCH_EXPIRED("V4203"),
    SIGALG_KEY_MISMATCH("V4204"),
    FENCE_TOKEN_STALE("V4301"),
    CAPACITY_EXCEEDED("V4302"),
    TRANSPORT_UNAVAILABLE("V4401"),
    RECONCILIATION_STALE("V4402");

    public final String code;

    ErrorCode(String code) {
        this.code = code;
    }
}
