package io.github.cyfko.veridot.core;

/**
 * Determines the strategy applied when a new signing attempt would exceed the
 * session capacity limit configured for a group.
 */
public enum EvictionPolicy {
    /**
     * First In, First Out — evicts the session with the earliest publish timestamp.
     */
    FIFO,
    /**
     * Last In, First Out — evicts the session with the most recent publish timestamp.
     */
    LIFO,
    /**
     * Least Recently Used — in this implementation, equivalent to FIFO.
     */
    LRU,
    /**
     * Reject — refuses the signing attempt and throws a SessionCapacityExceededException.
     */
    REJECT
}
