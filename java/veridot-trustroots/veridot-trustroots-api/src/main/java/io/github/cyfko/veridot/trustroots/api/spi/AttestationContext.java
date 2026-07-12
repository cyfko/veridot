package io.github.cyfko.veridot.trustroots.api.spi;

public record AttestationContext(
    String requestedCn,
    byte[] publicKey,
    int algorithm
) {}
