---
title: Revoking Sessions
description: Instantly revoke individual sessions or all sessions for a group, and understand how revocation propagates through the Veridot protocol.
keywords: [veridot, revocation, session, LIVENESS, REVOKED, monotonic]
sidebar_position: 5
---

# Revoking Sessions

Veridot provides instant revocation through the `TokenRevoker.revoke()` method. Revocation is expressed in terms of protocol identifiers — `groupId` and `sequenceId` — and takes effect as soon as verifiers observe the `LIVENESS(REVOKED)` entry from the broker.

## Single Session Revocation

Revoke one specific session within a group:

```java
// After verifying a token, revoke that specific session
VerifiedData<String> result = verifier.verify(token, s -> s);
revoker.revoke(result.groupId(), result.sequenceId());
// e.g., revoker.revoke("user-123", "session-A");
```

## Group-Wide Revocation

Revoke **all** active sessions for a group by passing `null` as the `sequenceId`:

```java
// Security breach: revoke all sessions for this user
revoker.revoke("user-123", null);
```

This iterates over all active sessions in the group and publishes a `LIVENESS(REVOKED)` entry for each one.

:::tip[Use cases for group-wide revocation]
- Password change / credential rotation
- Account compromise detection
- User-initiated "sign out everywhere"
- Administrative account suspension
:::

## How Revocation Propagates

When you call `revoke()`, the following happens:

```mermaid
sequenceDiagram
    participant App as Your Application
    participant SV as GenericSignerVerifier
    participant B as Broker
    participant V as Other Verifiers

    App->>SV: revoke("user-123", "session-A")
    SV->>SV: Build LIVENESS entry (status=REVOKED, version=N+1)
    SV->>SV: Sign envelope with long-term private key
    SV->>B: PUT LIVENESS(REVOKED) for (group:user-123, session-A)
    SV->>SV: Stop renewal loop for this session
    Note over B: Entry stored with version N+1
    V->>B: GET LIVENESS for (group:user-123, session-A)
    B->>V: LIVENESS(REVOKED, version=N+1)
    Note over V: Version N+1 > watermark N → accepted
    Note over V: status=REVOKED → session invalid
```

### The LIVENESS Entry

Revocation publishes a `LIVENESS` entry with these payload fields:

| Field | Value | Description |
|---|---|---|
| `status` | `0x02` (`REVOKED`) | The session is no longer valid |
| `asOf` | Current timestamp (ms) | When the revocation was asserted |
| `validUntil` | Expiry timestamp (ms) | Freshness window of this attestation |

### Monotonic Version Guarantee

The revocation entry carries a `version` strictly greater than the previous `LIVENESS(ACTIVE)` version. This guarantees:

1. **No rollback** — once a session is revoked, no previously-cached `ACTIVE` attestation with a lower version can override it
2. **Total ordering** — any verifier seeing both the `ACTIVE` and `REVOKED` entries will always accept the `REVOKED` one (higher version wins)
3. **Broker independence** — even if the broker is compromised, it cannot produce a valid `REVOKED → ACTIVE` transition because it lacks the long-term private key

## Irreversibility

:::warning
Revocation is **irreversible**. Once a `LIVENESS(REVOKED)` entry is published:

- The session's renewal loop is stopped
- No operation in the protocol can revert a `REVOKED` status back to `ACTIVE`
- The monotonic version invariant (§11.1) prevents any lower-version `ACTIVE` entry from being accepted

To re-authorize a user, you must call `sign()` to create a **new** session with a new sequenceId.
:::

```java
// ❌ This does NOT reactivate a revoked session
revoker.revoke("user-123", "session-A");
// There is no "unrevoke" API

// ✅ Create a new session instead
String newToken = signer.sign("user@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .sequenceId("session-B")  // new session
        .validity(3600)
        .build());
```

## Revocation Propagation Latency

Revocation propagation depends on two factors:

| Factor | Bound | Configuration |
|---|---|---|
| Broker read consistency | Eventual (transport-dependent) | Kafka: typically < 1s |
| Reconciliation interval | Periodic (default: 15 min) | `VDOT_RECONCILIATION_INTERVAL_MINUTES` |

For most deployments, revocation is observed within seconds. The reconciliation interval provides a safety net for missed individual entry deliveries.

## Integration Pattern

```java
// Complete revocation flow in a REST endpoint
@POST
@Path("/logout")
public Response logout(@HeaderParam("Authorization") String auth) {
    try {
        // 1. Verify the current token
        VerifiedData<String> result = verifier.verify(
            auth.replace("Bearer ", ""), s -> s);

        // 2. Revoke this specific session
        revoker.revoke(result.groupId(), result.sequenceId());

        return Response.ok().build();
    } catch (BrokerExtractionException e) {
        // Token was already invalid
        return Response.status(401).build();
    }
}

@POST
@Path("/logout-all")
public Response logoutAll(@PathParam("userId") String userId) {
    // Revoke all sessions for this user
    revoker.revoke(userId, null);
    return Response.ok().build();
}
```

## Next Steps

- [Distribution Modes](./distribution-modes.md) — understand how revocation works across DIRECT, INDIRECT, and PRIVATE modes
- [Session Capacity](./session-capacity.md) — automatic eviction policies that trigger revocation
