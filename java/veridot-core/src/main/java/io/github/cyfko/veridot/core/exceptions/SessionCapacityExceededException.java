package io.github.cyfko.veridot.core.exceptions;

/**
 * Thrown when a signing attempt is rejected because the maximum number of
 * active sessions for the group has been reached and the eviction policy
 * is {@code REJECT}.
 */
public class SessionCapacityExceededException extends VeridotException {
    private final String groupId;
    private final int maxSessions;

    public SessionCapacityExceededException(String groupId, int maxSessions) {
        super("Session capacity exceeded for group [" + groupId
                + "]: maximum " + maxSessions + " active sessions allowed");
        this.groupId = groupId;
        this.maxSessions = maxSessions;
    }

    public String getGroupId() {
        return groupId;
    }

    public int getMaxSessions() {
        return maxSessions;
    }
}
