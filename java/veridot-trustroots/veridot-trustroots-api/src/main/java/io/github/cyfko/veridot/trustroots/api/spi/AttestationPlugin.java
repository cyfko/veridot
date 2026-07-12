package io.github.cyfko.veridot.trustroots.api.spi;

public interface AttestationPlugin {
    String getPluginId();
    AttestationResult verify(byte[] proof, AttestationContext ctx);
}
