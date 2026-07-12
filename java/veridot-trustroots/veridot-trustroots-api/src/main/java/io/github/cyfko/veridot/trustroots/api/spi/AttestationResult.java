package io.github.cyfko.veridot.trustroots.api.spi;

public record AttestationResult(
    boolean valid,
    String authorityRef,
    String reason
) {
    public static AttestationResult accepted(String authorityRef) {
        return new AttestationResult(true, authorityRef, null);
    }
    public static AttestationResult rejected(String reason) {
        return new AttestationResult(false, null, reason);
    }
}
