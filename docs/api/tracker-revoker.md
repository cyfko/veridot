# TokenTracker & TokenRevoker

This section documents the interfaces used to monitor token activity states and perform session revocations across a distributed Veridot deployment.

---

## 1. Interface: TokenTracker

The `TokenTracker` allows applications to verify if a user group has any active session, or if a specific token remains active (i.e. not expired or revoked).

### Method
```java
boolean hasActiveToken(Object target);
```

- **Role**: Checks if at least one active, valid token matches the target.
- **Parameters**:
  - `target`: Polymorphic object (must be a `String`). It resolves as follows:
    1. **Signed Token (JWT)**: Extracts the protocol subject and checks if that specific session is active.
    2. **Protocol V4 messageId** (starts with `"4:"`): Directly checks liveness of the specific session.
    3. **groupId**: Treated as a group prefix; queries the Broker for any active sessions belonging to this group.
- **Returns**: `true` if at least one matching active session is found, otherwise `false`.
- **Exceptions**:
  - `IllegalArgumentException`: Thrown if `target` is not a `String`.

---

## 2. Interface: TokenRevoker

The `TokenRevoker` invalidates previously issued tokens by publishing a `LIVENESS` entry with status `REVOKED` (`0x02`) to the Broker.

### Method
```java
void revoke(String groupId, String sequenceId);
```

- **Role**: Revokes either a single specific session or an entire group.
- **Parameters**:
  - `groupId`: The group namespace (must not be `null` or blank).
  - `sequenceId`: The specific session ID to revoke, or `null` to trigger a **Group Revocation** (invalidating all sessions for the group).
- **Exceptions**:
  - `IllegalArgumentException`: Thrown if `groupId` is null or blank.

---

## 3. Code Examples

### Tracking User Session Liveness
Before allowing a high-value action or issuing a new session, verify if the group already has active sessions:

```java
public void handleUserAccess(TokenTracker tracker, String userId) {
    boolean isUserLoggedIn = tracker.hasActiveToken(userId);
    if (isUserLoggedIn) {
        System.out.println("User has an active session. Proceeding.");
    } else {
        System.out.println("User is offline. Redirecting to login.");
    }
}
```

### Session Revocation vs. Group Revocation

#### 1. Revoking a Specific Session (e.g. Logging out from one browser tab)
Extract the identifiers from the verified data, then pass them to the revoker:

```java
VerifiedData<String> result = verifier.verify(token, s -> s);
// Revokes ONLY this specific device session
revoker.revoke(result.groupId(), result.sequenceId());
```

#### 2. Revoking an Entire Group (e.g. Password reset or Security breach)
To log out a user from **all** devices and invalidate all active tokens instantly:

```java
// Revokes ALL active sessions under "user-123" by setting sequenceId to null
revoker.revoke("user-123", null);
```
During a group-wide revocation, `GenericSignerVerifier` fetches all active sessions for the group, publishes a `LIVENESS(REVOKED)` envelope for each session, and stops their background renewal loops.
