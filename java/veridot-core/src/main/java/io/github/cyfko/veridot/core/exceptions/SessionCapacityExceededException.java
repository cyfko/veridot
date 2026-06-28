package io.github.cyfko.veridot.core.exceptions;

/**
 * Thrown during {@link io.github.cyfko.veridot.core.DataSigner#sign DataSigner.sign()} when the
 * maximum number of concurrent active sessions for a group has been reached and the configured
 * eviction policy is {@code REJECT}.
 *
 * <p>This exception will only occur when the {@code GenericSignerVerifier} has been constructed
 * with a positive {@code maxSessions} limit and the
 * {@link io.github.cyfko.veridot.core.EvictionPolicy#REJECT REJECT}
 * eviction policy. With other policies (FIFO, LIFO, LRU), the oldest or newest session is
 * evicted automatically to make room for the new one.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * // Allow at most 2 concurrent sessions; reject any 3rd attempt
 * var sv = new GenericSignerVerifier(broker, trustRoot, "my-signer", longTermKey, 2, EvictionPolicy.REJECT);
 *
 * sv.sign("payload", BasicConfigurer.builder().groupId("user-1").validity(3600).build());
 * sv.sign("payload", BasicConfigurer.builder().groupId("user-1").validity(3600).build());
 * // This 3rd call throws SessionCapacityExceededException:
 * sv.sign("payload", BasicConfigurer.builder().groupId("user-1").validity(3600).build());
 * }</pre>
 *
 * @author Frank KOSSI
 * @since 2.0.0
 * @see io.github.cyfko.veridot.core.EvictionPolicy
 */
public class SessionCapacityExceededException extends VeridotException {

    /** The group that exceeded its active sequence limit. */
    private final String groupId;
    /** The configured maximum number of concurrent sessions for this group. */
    private final int maxSessions;

    /**
     * Constructs a new {@code SessionCapacityExceededException}.
     *
     * @param groupId     the group identifier whose session limit was exceeded
     * @param maxSessions the maximum number of concurrent sessions that was configured
     */
    public SessionCapacityExceededException(String groupId, int maxSessions) {
        super("Session capacity exceeded for group [" + groupId
                + "]: maximum " + maxSessions + " active sessions allowed");
        this.groupId = groupId;
        this.maxSessions = maxSessions;
    }

    /**
     * Returns the group identifier that triggered the capacity violation.
     *
     * @return the {@code groupId} (never {@code null})
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Returns the maximum number of concurrent sessions that was configured at
     * the time the exception was thrown.
     *
     * @return a positive integer representing the session capacity limit
     */
    public int getMaxSessions() {
        return maxSessions;
    }
}
