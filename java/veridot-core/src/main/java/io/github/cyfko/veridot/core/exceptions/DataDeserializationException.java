package io.github.cyfko.veridot.core.exceptions;

/**
 * Thrown when the raw string payload extracted from a verified token cannot be
 * converted back to the expected domain object by the provided deserializer.
 *
 * <p>This exception occurs at <em>verification time</em>, after the token's cryptographic
 * integrity has already been confirmed. Common causes include:</p>
 * <ul>
 *   <li>The target class does not match the schema of the serialized payload
 *       (e.g., a field was added or renamed after the token was issued)</li>
 *   <li>The deserializer function threw an unchecked exception</li>
 *   <li>The payload is a valid string but not valid JSON (or the expected format)</li>
 *   <li>A type mismatch between the serialized type and the target class
 *       passed to {@link io.github.cyfko.veridot.core.impl.BasicConfigurer#deserializer}</li>
 * </ul>
 *
 * <p>When this exception is thrown, the token itself is valid — only the deserialization
 * of its payload has failed. The caller should treat this as a schema or compatibility error,
 * not a security violation.</p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 * @see io.github.cyfko.veridot.core.TokenVerifier#verify
 * @see io.github.cyfko.veridot.core.impl.BasicConfigurer#deserializer(Class)
 */
public class DataDeserializationException extends VeridotException {

    /**
     * Constructs a new {@code DataDeserializationException} with the specified detail message.
     *
     * @param message a human-readable description of the deserialization failure
     */
    public DataDeserializationException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code DataDeserializationException} with the specified detail message
     * and root cause.
     *
     * @param message a human-readable description of the deserialization failure
     * @param cause   the underlying exception thrown by the deserializer; may be {@code null}
     */
    public DataDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
