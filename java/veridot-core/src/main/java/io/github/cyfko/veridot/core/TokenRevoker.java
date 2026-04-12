package io.github.cyfko.veridot.core;

/**
 * Defines the contract for revoking previously issued tokens or credentials.
 *
 * <p>
 * Implementations of this interface are responsible for invalidating tokens
 * by either targeting a specific token/messageId or an entire group.
 * </p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 */
public interface TokenRevoker {

    /**
     * Revokes a specific sequence or an entire group.
     *
     * <p>If {@code sequenceId} is provided, only that specific sequence is revoked.
     * If {@code sequenceId} is {@code null}, all active sequences belonging to the 
     * given {@code groupId} are revoked.</p>
     *
     * @param groupId the group identifier; must not be {@code null} or blank
     * @param sequenceId the specific sequence to revoke, or {@code null} to revoke the entire group
     * @throws IllegalArgumentException if {@code groupId} is null or blank
     */
    void revoke(String groupId, String sequenceId);
}
