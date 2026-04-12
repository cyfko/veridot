package io.github.cyfko.veridot.core;

/**
 * Defines how the signed token is delivered to the caller after a successful {@link DataSigner#sign} call.
 *
 * <p>Choosing the right mode depends on the security profile and token size requirements of
 * the application:</p>
 * <ul>
 *   <li>Use {@link #DIRECT} when the token can be safely transmitted over the wire
 *       (e.g., in an HTTP Authorization header) and its size is acceptable.</li>
 *   <li>Use {@link #INDIRECT} when the token must not leave the broker (e.g., very sensitive
 *       payloads), or when a compact, opaque reference is preferred over a self-contained token.</li>
 * </ul>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 * @see DataSigner.Configurer#getDistribution()
 * @see io.github.cyfko.veridot.core.impl.BasicConfigurer.Builder#distribution(DistributionMode)
 */
public enum DistributionMode {

    /**
     * The signed token is returned directly to the caller.
     *
     * <p>The token is self-contained: it embeds the payload and can be used directly
     * for verification by any service that has access to the same {@link MetadataBroker}.
     * This is the default mode and the most common choice for API authentication flows.</p>
     *
     * <h4>Example</h4>
     * <pre>{@code
     * String token = signer.sign("user@example.com",
     *     BasicConfigurer.builder()
     *         .groupId("user-123")
     *         .distribution(DistributionMode.DIRECT)
     *         .validity(3600)
     *         .build());
     * // token → opaque signed string, send in Authorization header
     * }</pre>
     */
    DIRECT,

    /**
     * The signed token is stored in the {@link MetadataBroker}; only a compact
     * {@code messageId} is returned to the caller.
     *
     * <p>The {@code messageId} has the form {@code <version>:<groupId>:<sequenceId>}
     * (e.g., {@code "2:user-123:session-A"}). It is used by the verifier to fetch the
     * actual token from the broker at verification time. This mode is useful when
     * token size is a concern, or when the payload is sensitive and should not leave
     * the broker boundary.</p>
     *
     * <h4>Example</h4>
     * <pre>{@code
     * String messageId = signer.sign(sensitivePayload,
     *     BasicConfigurer.builder()
     *         .groupId("service-X")
     *         .distribution(DistributionMode.INDIRECT)
     *         .validity(300)
     *         .build());
     * // messageId → "2:service-X:<uuid>", safe to pass as an opaque reference
     * }</pre>
     */
    INDIRECT
}
