package io.github.cyfko.veridot.core;

/**
 * Defines how the signed token is delivered to the caller after a successful {@link DataSigner#sign} call.
 * Protocol V5 (§7.4).
 *
 * <p>V5 replaces the former indirect mode with {@code NATIVE}. The available modes are:</p>
 * <ul>
 *   <li>{@link #DIRECT} — self-contained signed token, directly usable by verifiers.</li>
 *   <li>{@link #NATIVE} — token stored as a native V5 envelope in the broker; a compact
 *       reference of the form {@code "8:<scope>:<key>"} is returned to the caller.
 *       Verifiers fetch the envelope from the broker using the reference (§7.5).</li>
 *   <li>{@link #PRIVATE} — token encrypted using hybrid encryption (AES-GCM + asymmetric
 *       key wrapping) and stored in the broker; only designated recipients can decrypt.</li>
 * </ul>
 *
 * @author Frank KOSSI
 * @since 5.0.0
 * @see DataSigner.Configurer#getDistribution()
 * @see io.github.cyfko.veridot.core.impl.BasicConfigurer.Builder#distribution(DistributionMode)
 */
public enum DistributionMode {

    /**
     * The signed token is returned directly to the caller.
     *
     * <p>The token is self-contained: it embeds the payload and can be used directly
     * for verification by any service that has access to the same {@link Broker}.
     * This is the default mode and the most common choice for API authentication flows.</p>
     */
    DIRECT,

    /**
     * The signed payload is stored as a native V5 envelope in the {@link Broker};
     * a compact reference token of the form {@code "8:<scope>:<key>"} is returned.
     *
     * <p>Verifiers parse the reference, fetch the envelope from the broker,
     * and verify it natively. This mode avoids transmitting the full envelope
     * over the wire while maintaining self-describing verification (§7.5).</p>
     */
    NATIVE,

    /**
     * The signed payload is encrypted using hybrid encryption (AES-GCM + RSA/ECDH)
     * and stored in the {@link Broker}; only a compact reference token of the form
     * {@code "7:<scope>:<key>"} is returned.
     *
     * <p>Only verifying processors explicitly listed as recipients can decrypt the
     * payload using their private key material. This mode guarantees absolute
     * end-to-end confidentiality on untrusted metadata brokers.</p>
     */
    PRIVATE
}
