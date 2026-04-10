package io.github.cyfko.veridot.core;

/**
 * Contract for querying the active signing state of a token or group.
 *
 * <p>Determines whether valid (non-revoked, non-expired) tokens exist for a given target.
 * The {@code target} can be:</p>
 * <ul>
 *   <li>A {@code groupId} (plain string) — checks if any active sequence exists for that group</li>
 *   <li>A signed JWT token (string containing {@code .}) — checks if that specific token is still active</li>
 *   <li>A {@code messageId} (string starting with {@code 2:}) — checks if that specific sequence is active</li>
 * </ul>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 */
public interface TokenTracker {

    /**
     * Returns {@code true} if at least one active (non-revoked, non-expired) token exists
     * for the given target.
     *
     * @param target a groupId, JWT token, or messageId; must not be {@code null}
     * @return {@code true} if an active token exists, {@code false} otherwise
     */
    boolean hasActiveToken(Object target);
}
