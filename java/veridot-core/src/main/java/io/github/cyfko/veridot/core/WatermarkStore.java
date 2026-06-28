package io.github.cyfko.veridot.core;

/**
 * Pluggable store for persisting and restoring version watermarks
 * to prevent version rollback and replay attacks (§11.1, §12.3.1).
 */
public interface WatermarkStore {
    /**
     * Saves the serialized watermark snapshot.
     *
     * @param snapshot the serialized watermark snapshot bytes
     */
    void save(byte[] snapshot);

    /**
     * Loads the serialized watermark snapshot.
     *
     * @return the serialized watermark snapshot bytes, or null if none exists
     */
    byte[] load();
}
