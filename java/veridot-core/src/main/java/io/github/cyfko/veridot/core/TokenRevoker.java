package io.github.cyfko.veridot.core;

/**
 * Defines the contract for revoking previously issued tokens or credentials.
 *
 * <p>
 * Implementations of this interface are responsible for invalidating or blacklisting
 * a token identified by the given {@code target}. The {@code target} can be either:
 * </p>
 *
 * <ul>
 *   <li>a globally unique identifier used to track the token (e.g., a long or UUID)</li>
 *   <li>the token itself (e.g., a signed {@code String})</li>
 * </ul>
 *
 * <p>
 * Future implementations may support additional {@code target} types.
 * This interface is especially useful in distributed systems where token invalidation
 * must be decoupled from token creation and verified at runtime.
 * </p>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 */
public interface TokenRevoker {

    /**
     * Revokes a token or credential associated with the given target.
     *
     * @param target a unique identifier ({@link Long}), the token itself ({@link String}), or another supported object
     *               representing the token to revoke; must not be {@code null}
     */
    void revoke(Object target);
}
