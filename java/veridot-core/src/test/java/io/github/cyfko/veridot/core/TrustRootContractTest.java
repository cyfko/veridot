package io.github.cyfko.veridot.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reusable contract test for TrustRoot implementations, validating safety invariants.
 */
public abstract class TrustRootContractTest {

    protected abstract TrustRoot getTrustRoot();

    @Test
    public void resolve_unknown_issuer_should_throw_exception() {
        TrustRoot trust = getTrustRoot();
        if (trust instanceof PublicKeyTrustRoot) {
            assertThrows(Exception.class, () -> trust.resolve("unknown-issuer-id-12345"));
        }
    }

    @Test
    public void verifySignature_on_internal_error_should_return_false_not_true() {
        TrustRoot trust = getTrustRoot();
        if (trust instanceof DelegatedTrustRoot delegated) {
            // An internal error (e.g. invalid signature format or null/empty inputs) should always fail closed
            boolean result = false;
            try {
                result = delegated.verifySignature("any", new byte[0], new byte[0], (byte) 0x01);
            } catch (Exception e) {
                // If it throws, that is also fail-closed, which is safe.
                return;
            }
            assertFalse(result, "DelegatedTrustRoot verifySignature must fail-closed on invalid/empty inputs");
        }
    }
}
