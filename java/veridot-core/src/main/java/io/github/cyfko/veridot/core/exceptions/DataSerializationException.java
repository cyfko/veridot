package io.github.cyfko.veridot.core.exceptions;

/**
 * Thrown when the payload provided to {@link io.github.cyfko.veridot.core.DataSigner#sign}
 * cannot be converted to a string by the configured serializer.
 *
 * <p>This exception occurs at <em>signing time</em>. Common causes include:</p>
 * <ul>
 *   <li>The payload object contains non-serializable fields (e.g., circular references,
 *       non-JSON-serializable types)</li>
 *   <li>The custom serializer function threw an unchecked exception</li>
 *   <li>A marshalling library (e.g., Jackson) encountered an unexpected schema mismatch</li>
 * </ul>
 *
 * <p>When thrown, no token is issued and no metadata is published to the broker.</p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 * @see io.github.cyfko.veridot.core.DataSigner#sign
 * @see io.github.cyfko.veridot.core.DataSigner.Configurer#getSerializer()
 */
public class DataSerializationException extends VeridotException {

    /**
     * Constructs a new {@code DataSerializationException} with the specified detail message.
     *
     * @param message a human-readable description of the serialization failure
     */
    public DataSerializationException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code DataSerializationException} with the specified detail message
     * and root cause.
     *
     * @param message a human-readable description of the serialization failure
     * @param cause   the underlying exception thrown by the serializer; may be {@code null}
     */
    public DataSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
