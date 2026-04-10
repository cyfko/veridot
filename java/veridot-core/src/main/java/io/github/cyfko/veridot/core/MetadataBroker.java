package io.github.cyfko.veridot.core;

import io.github.cyfko.veridot.core.exceptions.BrokerTransportException;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface representing a lightweight broker responsible for propagating metadata messages
 * along with cryptographic key identifiers used in token signing.
 * <p>
 * Implementations of this interface act as a communication mechanism between services
 * in a distributed system, enabling one component to broadcast a signed metadata message
 * (typically including a {@code messageId}) and others to retrieve it later by key.
 * </p>
 *
 * <p>
 * This abstraction is useful in scenarios where ephemeral public keys are distributed
 * alongside verifiable context metadata, such as in distributed token verification schemes.
 * </p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 */
public interface MetadataBroker {

    /**
     * Asynchronously publishes a metadata message associated with a given key.
     *
     * <p>
     * The message typically includes a public key identifier and related signing metadata.
     * Calling this method should make the data available for subsequent {@link #get(String)} calls.
     * Sending an empty string for {@code message} signals revocation and MUST remove the entry.
     * </p>
     *
     * @param key     a unique key under which the message should be published (e.g. {@code messageId})
     * @param message the metadata message to broadcast; empty string signals revocation
     * @return a {@link CompletableFuture} that completes when the message has been sent
     * @throws BrokerTransportException if the event containing the pair {@code key}-{@code message} can not be sent
     */
    CompletableFuture<Void> send(String key, String message) throws BrokerTransportException;

    /**
     * Retrieves the metadata message associated with the given key.
     *
     * @param key the key for which the metadata is being requested
     * @return the metadata message previously sent via {@link #send(String, String)}
     * @throws BrokerExtractionException if the key is unknown, revoked, or the message is otherwise unavailable
     */
    String get(String key) throws BrokerExtractionException;

    /**
     * Retrieves all keys in the broker that start with the given prefix.
     *
     * <p>This is used for group-level operations such as listing all active sequences of a group,
     * enforcing session limits, or revoking an entire group.</p>
     *
     * @param prefix the key prefix to search for (e.g., {@code "2:user123:"})
     * @return a list of matching keys; empty list if none found (never {@code null})
     * @throws BrokerExtractionException if the lookup fails
     */
    List<String> getKeysByPrefix(String prefix) throws BrokerExtractionException;
}
