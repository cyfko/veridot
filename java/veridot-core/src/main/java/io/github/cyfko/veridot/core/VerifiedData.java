package io.github.cyfko.veridot.core;

/**
 * Immutable result of a successful token verification, carrying the deserialized payload
 * together with the Protocol V3 identifiers that were bound to the token at signing time.
 *
 * <p>This record eliminates the need to re-parse the token string or redundantly embed
 * protocol identifiers ({@code groupId}, {@code sequenceId}) inside the payload itself.
 * After calling {@link TokenVerifier#verify}, callers have direct access to all the
 * information needed to act on the result, correlate sessions, or trigger revocation.</p>
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * VerifiedData<String> result = verifier.verify(token, s -> s);
 *
 * String payload  = result.data();        // the deserialized application payload
 * String group    = result.groupId();     // e.g., "user-123"
 * String session  = result.sequenceId();  // e.g., "session-A" or a UUID
 *
 * // Revoke this specific session later
 * revoker.revoke(group, session);
 * }</pre>
 *
 * <h2>POJO example</h2>
 * <pre>{@code
 * VerifiedData<UserClaims> result = verifier.verify(token,
 *     BasicConfigurer.deserializer(UserClaims.class));
 *
 * String userId = result.groupId();
 * UserClaims claims = result.data();
 * }</pre>
 *
 * @param <T>        the type of the deserialized payload
 * @param groupId    the group identifier extracted from the token's protocol subject
 *                   (e.g., a user ID such as {@code "user-123"} or a service namespace)
 * @param sequenceId the sequence identifier extracted from the token's protocol subject
 *                   (e.g., a session name such as {@code "session-A"} or an auto-generated UUID)
 * @param data       the deserialized payload embedded in the token at signing time
 *
 * @author Frank KOSSI
 * @since 3.0.0
 * @see TokenVerifier#verify
 * @see TokenRevoker#revoke
 */
public record VerifiedData<T>(
    String groupId,
    String sequenceId,
    T data
) {}
