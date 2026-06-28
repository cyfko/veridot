package io.github.cyfko.veridot.core.exceptions;

/**
 * Thrown when verification metadata for a token cannot be retrieved from the
 * {@link io.github.cyfko.veridot.core.Broker}, or when the metadata is present
 * but the token fails validation.
 *
 * <p>Common causes include:</p>
 * <ul>
 *   <li>The token has been explicitly revoked via
 *       {@link io.github.cyfko.veridot.core.TokenRevoker#revoke TokenRevoker.revoke()}</li>
 *   <li>The token's TTL has expired and its entry has been evicted from the broker</li>
 *   <li>The broker does not contain a record for the given {@code messageId}
 *       (e.g., the token was issued against a different broker)</li>
 *   <li>Clock drift between the signing service and the verifying service exceeds
 *       the allowed threshold (default: ±5 minutes)</li>
 *   <li>The token format is unrecognized (neither a valid signed token nor a Protocol V4
 *       {@code messageId})</li>
 * </ul>
 *
 * <p>This exception signals that the token should be rejected. The caller should
 * treat the request as unauthenticated and prompt re-authentication where appropriate.</p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 * @see io.github.cyfko.veridot.core.TokenVerifier#verify
 */
public class BrokerExtractionException extends VeridotException {

    /**
     * Constructs a new {@code BrokerExtractionException} with the specified detail message.
     *
     * @param message a human-readable description of why the token could not be verified
     */
    public BrokerExtractionException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code BrokerExtractionException} with the specified detail message
     * and root cause.
     *
     * @param message a human-readable description of why the token could not be verified
     * @param cause   the underlying exception; may be {@code null}
     */
    public BrokerExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
