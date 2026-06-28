package io.github.cyfko.veridot.core;

import io.github.cyfko.veridot.core.impl.Scope;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface representing the storage and delivery mechanism for Protocol V4 entries (§12.2).
 */
public interface Broker {

    /**
     * Stores the envelope bytes under the derived storage key (§3.3).
     *
     * @param storageKey the derived storage key
     * @param envelopeBytes the raw bytes of the envelope
     * @return a future that completes when the entry is durably stored
     * @throws io.github.cyfko.veridot.core.exceptions.VeridotException if the envelope is invalid
     */
    CompletableFuture<Void> put(byte[] storageKey, byte[] envelopeBytes);

    /**
     * Retrieves the most recently stored bytes for a given storage key.
     *
     * @param storageKey the storage key
     * @return the raw bytes of the envelope, or null if absent
     */
    byte[] get(byte[] storageKey);

    /**
     * Enumerates all entries and their most recently stored bytes for a given scope (§12.2).
     *
     * @param scope the scope to snapshot
     * @return the list of entries currently stored in the scope
     */
    List<BrokerEntry> snapshot(Scope scope);

    /**
     * Writes directly to the local cache to bypass read-after-write latencies on the signing node.
     *
     * @param storageKey the storage key
     * @param envelopeBytes the raw bytes of the envelope
     */
    void putLocal(byte[] storageKey, byte[] envelopeBytes);

    /**
     * Represents a single entry retrieved during snapshot.
     */
    record BrokerEntry(byte[] storageKey, byte[] envelopeBytes) {}
}
