package io.github.cyfko.veridok.core;

/**
 * Functional interface for generating signed tokens using an ephemeral asymmetric private key.
 * <p>
 * Implementations of this interface are responsible for serializing the provided payload object
 * and generating a signed token that remains valid for a specified duration. The resulting token
 * may or may not conform to the JWT format, depending on the selected {@link TokenMode}.
 * </p>
 *
 * <p>
 * This interface is primarily intended for use in distributed systems where services must issue
 * short-lived, cryptographically verifiable tokens without relying on shared secrets.
 * </p>
 *
 * @author Frank KOSSI
 * @since 0.0.1
 */
@FunctionalInterface
public interface Signer {

    /**
     * Generates a signed token that references the given payload for the specified duration.
     *
     * <p>
     * The payload is serialized and embedded in a cryptographically signed structure.
     * The resulting token is intended to be short-lived and may be encoded as a JWT or
     * in another form depending on the {@link TokenMode}.
     * </p>
     *
     * @param data     the object to be signed; must not be {@code null}
     * @param seconds the duration in seconds for which the token should remain valid; must be positive
     * @param mode     the desired encoding mode for the token (e.g., {@code jwt}, {@code uuid})
     * @param trackingId  a globally unique integer used to reference the generated token for traceability purposes (e.g., revocation)
     * @return a signed token as a string
     * @throws JsonEncodingException if the payload cannot be encoded or the duration is invalid
     */
    String sign(Object data, long seconds, TokenMode mode, long trackingId) throws JsonEncodingException;
}

