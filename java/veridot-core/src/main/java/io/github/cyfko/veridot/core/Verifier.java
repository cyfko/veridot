package io.github.cyfko.veridot.core;

import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;


/**
 * Functional interface for verifying tokens and extracting their embedded payloads.
 * <p>
 * Implementations of this interface are responsible for validating the integrity and authenticity
 * of a token previously obtained by the related <code>sign()</code> method and deserializing its payload
 * into an object.
 * </p>
 *
 * <p>
 * This interface is designed for use in microservices and distributed systems where services
 * must verify signed tokens and operate on their contents in a type-safe way.
 * </p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 */
@FunctionalInterface
public interface Verifier {
    /**
     * Verifies the given token and extracts its payload.
     *
     * <p>
     * This method validates the token's signature and expiration, then deserializes its payload
     * into an instance of the specified class type.
     * </p>
     *
     * @param token the token to verify; must not be {@code null} or empty
     * @return the deserialized payload object
     * @throws BrokerExtractionException if the token is invalid, expired, or cannot be decoded
     * @throws DataDeserializationException if the token is valid but the data cannot be deserialized
     */
    Object verify(String token) throws BrokerExtractionException, DataDeserializationException;
}
