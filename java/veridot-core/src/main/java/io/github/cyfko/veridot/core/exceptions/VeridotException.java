package io.github.cyfko.veridot.core.exceptions;

/**
 * Root exception for all Veridot-specific errors.
 *
 * <p>Callers may catch this single type to handle any Veridot error uniformly,
 * or catch specific subclasses for fine-grained error handling:</p>
 * <ul>
 *   <li>{@link BrokerExtractionException} — token unavailable, revoked, or expired</li>
 *   <li>{@link BrokerTransportException} — network or I/O error when publishing metadata</li>
 *   <li>{@link DataSerializationException} — payload could not be serialized at sign time</li>
 *   <li>{@link DataDeserializationException} — payload could not be deserialized at verify time</li>
 *   <li>{@link SessionCapacityExceededException} — max sessions reached with REJECT policy</li>
 * </ul>
 *
 * <p>All Veridot exceptions are unchecked (extend {@link RuntimeException}) so that they
 * propagate naturally without forcing callers to declare {@code throws} clauses.</p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 */
public class VeridotException extends RuntimeException {

    /**
     * Constructs a new {@code VeridotException} with the specified detail message.
     *
     * @param message a human-readable description of the error condition
     */
    public VeridotException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code VeridotException} with the specified detail message
     * and root cause.
     *
     * @param message a human-readable description of the error condition
     * @param cause   the underlying exception that triggered this error; may be {@code null}
     */
    public VeridotException(String message, Throwable cause) {
        super(message, cause);
    }
}
