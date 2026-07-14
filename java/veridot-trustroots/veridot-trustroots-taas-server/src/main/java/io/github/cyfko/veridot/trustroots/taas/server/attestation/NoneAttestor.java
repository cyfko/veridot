package io.github.cyfko.veridot.trustroots.taas.server.attestation;

import io.github.cyfko.veridot.trustroots.api.spi.AttestationContext;
import io.github.cyfko.veridot.trustroots.api.spi.AttestationPlugin;
import io.github.cyfko.veridot.trustroots.api.spi.AttestationResult;

/**
 * A dummy attestation plugin that performs no verification.
 * ONLY permitted for root trust bootstrap or local dev.
 */
public class NoneAttestor implements AttestationPlugin {
    
    /** Default constructor. */
    public NoneAttestor() {}

    @Override
    public String getPluginId() {
        return "none";
    }

    @Override
    public AttestationResult verify(byte[] proof, AttestationContext ctx) {
        return AttestationResult.accepted(null);
    }
}
