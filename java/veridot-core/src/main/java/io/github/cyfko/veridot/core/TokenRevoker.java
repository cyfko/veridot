package io.github.cyfko.veridot.core;

/**
 * Contract for invalidating previously issued tokens.
 *
 * <p>Revocation is expressed in terms of Protocol V4 identifiers ({@code groupId} and
 * {@code sequenceId}) rather than opaque token strings, ensuring that the revocation is
 * broker-centric. Revocation takes effect for any verifier instance as soon as it observes
 * the published {@code LIVENESS=REVOKED} entry from the broker. This is not instantaneous in a
 * distributed deployment: see {@code io.github.cyfko.veridot.core.impl.Config#RECONCILIATION_INTERVAL_MINUTES}
 * for the bound on cross-instance watermark staleness, and note that broker read consistency is
 * an operational assumption of this guarantee, not one enforced by this library alone.</p>
 *
 * <p>Two revocation scopes are supported:</p>
 * <ul>
 *   <li><strong>Session revocation</strong> — invalidates a single active sequence within a group.</li>
 *   <li><strong>Group revocation</strong> — invalidates all active sequences belonging to a group
 *       (e.g., a user log-out from all devices).</li>
 * </ul>
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * TokenRevoker revoker = new GenericSignerVerifier(broker, trustRoot, "my-signer", longTermKey);
 *
 * // Revoke a specific session (obtained from VerifiedData after verification)
 * revoker.revoke("user-123", "session-A");
 *
 * // Revoke ALL active sessions for a user (e.g., security breach, password change)
 * revoker.revoke("user-123", null);
 * }</pre>
 *
 * <h2>Integration with verification flow</h2>
 * <pre>{@code
 * // After verifying a token, you can revoke its session using the extracted identifiers
 * VerifiedData<String> result = verifier.verify(token, s -> s);
 * revoker.revoke(result.groupId(), result.sequenceId());
 * }</pre>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 * @see VerifiedData
 * @see TokenVerifier
 */
public interface TokenRevoker {

    /**
     * Revokes one specific session or all sessions of a group.
     *
     * <p>If {@code sequenceId} is non-null, only that particular session is revoked.
     * Any subsequent attempt to verify the corresponding token will result in a
     * {@link io.github.cyfko.veridot.core.exceptions.BrokerExtractionException}.</p>
     *
     * <p>If {@code sequenceId} is {@code null}, all active sessions belonging to
     * {@code groupId} are revoked atomically — typically used during a full user
     * sign-out or a group-level security invalidation.</p>
     *
     * @param groupId    the group whose session(s) should be revoked;
     *                   must not be {@code null} or blank
     * @param sequenceId the specific session to revoke, or {@code null} to revoke
     *                   all sessions of the group
     * @throws IllegalArgumentException if {@code groupId} is {@code null} or blank
     */
    void revoke(String groupId, String sequenceId);
}
