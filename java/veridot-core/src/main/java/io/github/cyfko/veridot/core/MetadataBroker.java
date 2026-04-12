package io.github.cyfko.veridot.core;

import io.github.cyfko.veridot.core.exceptions.BrokerTransportException;
import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction of the distributed store used to propagate and retrieve token
 * <em>verification metadata</em> across services.
 *
 * <p>When a token is signed, the issuing service publishes a Protocol V2 metadata message
 * (containing cryptographic material and expiry information) under a unique key
 * (the {@code messageId}) via {@link #send}. Any service that later needs to verify the
 * token calls {@link #get} with the same key to retrieve and validate that metadata.</p>
 *
 * <p>This abstraction is intentionally technology-agnostic: implementations may back
 * it with Apache Kafka, a relational database, Redis, or any other durable storage
 * that satisfies the consistency and availability requirements of the deployment.</p>
 *
 * <h2>Known implementations</h2>
 * <ul>
 *   <li>{@code KafkaMetadataBrokerAdapter} — Kafka + embedded RocksDB (veridot-kafka)</li>
 *   <li>{@code DatabaseMetadataBroker} — JDBC-compatible RDBMS (veridot-databases)</li>
 *   <li>{@code InMemoryMetadataBroker} — in-process map, for testing only</li>
 * </ul>
 *
 * <h2>Revocation semantics</h2>
 * <p>Sending an <em>empty string</em> as the {@code message} signals revocation for that key.
 * Implementations MUST treat an empty-string send as a deletion of the corresponding entry,
 * so that subsequent {@link #get} calls throw {@link BrokerExtractionException}.</p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 * @see DataSigner
 * @see TokenVerifier
 */
public interface MetadataBroker {

    /**
     * Asynchronously publishes a verification metadata message under the given key.
     *
     * <p>The {@code key} is the Protocol V2 {@code messageId} (format:
     * {@code <version>:<groupId>:<sequenceId>}) or a reserved key (e.g., revocation or
     * configuration keys). The {@code message} is a Protocol V2-formatted string containing
     * encoded cryptographic and expiry metadata.</p>
     *
     * <p><strong>Revocation signal:</strong> sending an empty string for {@code message}
     * instructs the implementation to delete the entry for {@code key}, effectively revoking
     * it. Subsequent calls to {@link #get(String)} for that key MUST throw
     * {@link BrokerExtractionException}.</p>
     *
     * @param key     the unique key under which the message is published (e.g., a
     *                Protocol V2 {@code messageId}); must not be {@code null} or blank
     * @param message the verification metadata message to store; an empty string signals
     *                revocation and MUST cause the entry to be deleted
     * @return a {@link CompletableFuture} that completes normally when the message has been
     *         durably persisted, or exceptionally if persistence fails
     * @throws BrokerTransportException if a transport-level error prevents the message from
     *                                  being sent (e.g., network failure, broker unavailable)
     */
    CompletableFuture<Void> send(String key, String message) throws BrokerTransportException;

    /**
     * Retrieves the verification metadata message associated with the given key.
     *
     * <p>This method is called during token verification to fetch the cryptographic
     * material needed to validate a token's signature and expiry.</p>
     *
     * @param key the Protocol V2 {@code messageId} or reserved key for which metadata
     *            is requested; must not be {@code null}
     * @return the metadata message previously stored via {@link #send(String, String)};
     *         never {@code null} or empty
     * @throws BrokerExtractionException if the key is unknown, has been revoked (empty-string
     *                                   send), or the broker cannot satisfy the request
     */
    String get(String key) throws BrokerExtractionException;

    /**
     * Returns all keys stored in the broker whose string representation starts with the
     * given prefix.
     *
     * <p>Used internally for group-level operations such as listing active sequences,
     * enforcing session capacity limits, or revoking an entire group. The prefix
     * typically has the form {@code "<version>:<groupId>:"} (e.g., {@code "2:user-123:"}).</p>
     *
     * <p>Example:</p>
     * <pre>{@code
     * List<String> keys = broker.getKeysByPrefix("2:user-123:");
     * // e.g., ["2:user-123:session-A", "2:user-123:session-B", "2:user-123:__REVOKE__"]
     * }</pre>
     *
     * @param prefix the key prefix to search for; must not be {@code null}
     * @return a list of matching keys; empty list if none found (never {@code null})
     * @throws BrokerExtractionException if the prefix lookup fails due to a broker error
     */
    List<String> getKeysByPrefix(String prefix) throws BrokerExtractionException;
}
