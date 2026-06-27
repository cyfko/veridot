package io.github.cyfko.veridot.core;

/**
 * Contract for querying the active state of a token or an entire group of tokens.
 *
 * <p>Determines whether at least one valid (non-revoked, non-expired) token exists
 * for a given target. This is useful for guarding resources — for example, before
 * allowing a user action, or before issuing a new token to a group that may already
 * have an active session.</p>
 *
 * <p>The {@code target} parameter is polymorphic and accepted in three forms:</p>
 * <ul>
 *   <li>A <strong>groupId</strong> plain string — checks if any active sequence exists
 *       for the entire group (e.g., {@code "user-123"})</li>
 *   <li>A <strong>signed token</strong> — checks if that specific token is still active</li>
 *   <li>A <strong>Protocol V3 messageId</strong> (format: {@code <version>:<groupId>:<sequenceId>})
 *       — checks if that specific sequence is still active</li>
 * </ul>
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * TokenTracker tracker = new GenericSignerVerifier(broker, trustAnchor, "my-signer", longTermKey);
 *
 * // Check if a user has any active session
 * boolean userIsLoggedIn = tracker.hasActiveToken("user-123");
 *
 * // Check if a specific token is still valid (not expired, not revoked)
 * boolean tokenIsValid = tracker.hasActiveToken(tokenString);
 *
 * // Check if a specific messageId corresponds to an active session
 * boolean sessionIsActive = tracker.hasActiveToken("3:user-123:session-A");
 * }</pre>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 * @see TokenRevoker
 */
public interface TokenTracker {

    /**
     * Returns {@code true} if at least one active (non-revoked, non-expired) token
     * exists for the given target.
     *
     * <p>The {@code target} is resolved as follows:</p>
     * <ol>
     *   <li>If it is a <strong>signed token</strong>, the implementation extracts its
     *       protocol subject and checks the corresponding sequence in the broker.</li>
     *   <li>If it is a <strong>Protocol V3 messageId</strong> (starts with the version
     *       prefix, e.g., {@code "3:"}), the specific sequence is checked directly.</li>
     *   <li>Otherwise, it is treated as a <strong>groupId</strong> and the broker is
     *       queried for any non-reserved active sequences within that group.</li>
     * </ol>
     *
     * @param target a groupId string, a signed token, or a Protocol V3 messageId;
     *               must not be {@code null}
     * @return {@code true} if at least one active token is found, {@code false} otherwise
     * @throws IllegalArgumentException if {@code target} is not a {@code String}
     */
    boolean hasActiveToken(Object target);
}
