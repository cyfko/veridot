package io.github.cyfko.veridot.core;

import java.security.PublicKey;

/**
 * Represents a resolved long-term identity from the TrustRoot.
 */
public record TrustIdentity(PublicKey publicKey, boolean isRoot) {
    public TrustIdentity {
        if (publicKey == null) {
            throw new IllegalArgumentException("publicKey cannot be null");
        }
    }
}
