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
     * Revokes a specific token or sequence identified by the given target.
     *
     * <p>The {@code target} can be:</p>
     * <ul>
     *   <li>A JWT token string (contains {@code .}) — the messageId is extracted from the {@code sub} claim</li>
     *   <li>A Protocol V2 messageId (starts with {@code 2:}) — used directly as the broker key</li>
     * </ul>
     *
     * @param target the token or messageId to revoke; must not be {@code null}
     * @throws IllegalArgumentException if the target format is not recognized
     */
    void revoke(Object target);

    /**
     * Revokes all active tokens for the given group.
     *
     * <p>After this call, all sequences belonging to {@code groupId} are invalidated.
     * Any subsequent verification of tokens from this group will fail.</p>
     *
     * @param groupId the group identifier whose tokens should all be revoked; must not be {@code null} or blank
     */
    void revokeGroup(String groupId);
}
