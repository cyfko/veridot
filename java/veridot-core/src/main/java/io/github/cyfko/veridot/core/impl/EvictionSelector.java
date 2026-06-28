package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.exceptions.VeridotException;
import java.util.List;

/**
 * Selects a session for eviction according to the configured Eviction Policy (§10.2).
 */
final class EvictionSelector {

    public SessionCounter.SessionInfo select(byte pol, List<SessionCounter.SessionInfo> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }

        return switch (pol) {
            case 0x01 -> // FIFO: lowest asOf (first in sorted list)
                sessions.get(0);
            case 0x02 -> // LIFO: highest asOf (last in sorted list)
                sessions.get(sessions.size() - 1);
            case 0x03 -> // LRU: lowest asOf (first in sorted list)
                sessions.get(0);
            case 0x04 -> // REJECT
                throw new VeridotException(ErrorCode.CAPACITY_EXCEEDED, null, "Session capacity limit reached under REJECT policy");
            default ->
                throw new VeridotException(ErrorCode.MALFORMED_PAYLOAD, null, "Unknown eviction policy code: " + pol);
        };
    }
}
