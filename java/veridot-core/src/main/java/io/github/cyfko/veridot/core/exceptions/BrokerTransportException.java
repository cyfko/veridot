package io.github.cyfko.veridot.core.exceptions;

/**
 * Thrown when verification metadata cannot be published to the
 * {@link io.github.cyfko.veridot.core.Broker} due to a transport-level failure.
 *
 * <p>This exception occurs at <em>signing time</em>, when
 * {@link io.github.cyfko.veridot.core.DataSigner#sign DataSigner.sign()} attempts to push
 * the cryptographic metadata to the broker but the underlying transport layer fails.
 * Common causes include:</p>
 * <ul>
 *   <li>Network connectivity issues (broker unreachable)</li>
 *   <li>Kafka producer send failure or timeout</li>
 *   <li>Database write error (constraint violation, connection pool exhausted)</li>
 *   <li>Broker acknowledgement timeout</li>
 * </ul>
 *
 * <p>When this exception is thrown, the signed token should be considered invalid
 * because verifying services will not find the corresponding metadata in the broker
 * and will reject it with a {@link BrokerExtractionException}.</p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 * @see io.github.cyfko.veridot.core.DataSigner#sign
 * @see io.github.cyfko.veridot.core.Broker#put
 */
public class BrokerTransportException extends VeridotException {

    /**
     * Constructs a new {@code BrokerTransportException} with the specified detail message.
     *
     * @param message a human-readable description of the transport failure
     */
    public BrokerTransportException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code BrokerTransportException} with the specified detail message
     * and root cause.
     *
     * @param message a human-readable description of the transport failure
     * @param cause   the underlying I/O or network exception; may be {@code null}
     */
    public BrokerTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
