package io.github.cyfko.veridot.core;

import io.github.cyfko.veridot.core.exceptions.BrokerExtractionException;
import io.github.cyfko.veridot.core.exceptions.DataDeserializationException;

import java.util.function.Function;

/**
 * Contract for verifying a signed token and extracting its embedded payload along with
 * the Protocol V4 identifiers that were bound to the token at signing time.
 *
 * <p>Implementations validate the cryptographic integrity and temporal validity of the token,
 * retrieve the associated verification metadata from a {@link Broker}, and
 * deserialize the payload into the caller-specified type.</p>
 *
 * <p>The method accepts both token formats produced by {@link DataSigner#sign}:</p>
 * <ul>
 *   <li>A <em>signed token</em> (issued in {@link DistributionMode#DIRECT} mode)</li>
 *   <li>A Protocol V4 {@code messageId} (issued in {@link DistributionMode#INDIRECT} mode)</li>
 * </ul>
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * TokenVerifier verifier = new GenericSignerVerifier(broker, trustRoot, "my-signer", longTermKey);
 *
 * // Verify and extract the payload
 * VerifiedData<String> result = verifier.verify(token, s -> s);
 *
 * String email     = result.data();       // deserialized payload
 * String groupId   = result.groupId();    // e.g., "user-123"
 * String sessionId = result.sequenceId(); // e.g., "session-A"
 *
 * // Revoke this specific session later using the extracted identifiers
 * revoker.revoke(groupId, sessionId);
 * }</pre>
 *
 * <h2>POJO deserialization</h2>
 * <pre>{@code
 * VerifiedData<UserData> result = verifier.verify(token,
 *     BasicConfigurer.deserializer(UserData.class));
 * UserData user = result.data();
 * }</pre>
 *
 * @author Frank KOSSI
 * @since 1.0.0
 * @see DataSigner
 * @see VerifiedData
 * @see io.github.cyfko.veridot.core.impl.BasicConfigurer#deserializer(Class)
 */
@FunctionalInterface
public interface TokenVerifier {

    /**
     * Verifies the given token and returns the deserialized payload together with
     * the Protocol V4 identifiers ({@code groupId} and {@code sequenceId}) that were
     * bound to the token at signing time.
     *
     * <p>The verification process:</p>
     * <ol>
     *   <li>Resolves the token format (signed token vs {@code messageId})</li>
     *   <li>Retrieves the verification metadata (KEY_EPOCH envelope) from the {@link Broker}</li>
     *   <li>Validates the structural integrity and bounds of the identifiers and key epoch</li>
     *   <li>Validates the long-term cryptographic signature of the key epoch against a {@link TrustRoot}</li>
     *   <li>Verifies the authorization chain of capabilities for the signer ID</li>
     *   <li>Enforces version monotonicity checks via the {@code VersionWatermark}</li>
     *   <li>Validates the temporal validity (TTL) and clock drift (≤ ±5 min)</li>
     *   <li>Verifies the liveness of the session by checking the {@code LIVENESS=ACTIVE} envelope</li>
     *   <li>Verifies the cryptographic signature of the token using the ephemeral key</li>
     *   <li>Deserializes the payload using the provided {@code deserializer}</li>
     * </ol>
     *
     * @param <T>          the target type of the deserialized payload
     * @param token        the token to verify (signed token or {@code messageId});
     *                     must not be {@code null} or empty
     * @param deserializer function to convert the raw string payload to the target type;
     *                     must not be {@code null}
     * @return a {@link VerifiedData} carrying the deserialized payload and the
     *         protocol identifiers ({@code groupId}, {@code sequenceId})
     * @throws BrokerExtractionException    if the token is invalid, expired, revoked,
     *                                      or its verification metadata is unavailable
     * @throws DataDeserializationException if the token is cryptographically valid but
     *                                      the payload cannot be deserialized
     */
    <T> VerifiedData<T> verify(String token, Function<String, T> deserializer)
            throws BrokerExtractionException, DataDeserializationException;
}
