---
title: Error Handling
description: Complete exception hierarchy, error codes, catch patterns for sign() and verify(), and HTTP status code mapping.
keywords: [veridot, exceptions, error handling, BrokerExtractionException, ErrorCode, HTTP status]
sidebar_position: 14
---

# Error Handling

Veridot uses a structured exception hierarchy rooted in `VeridotException`. Every exception carries an optional `ErrorCode` (from Protocol V4 Appendix B) and an `entryId` for traceability.

## Exception Hierarchy

```mermaid
classDiagram
    class RuntimeException {
        <<JDK>>
    }
    class VeridotException {
        +getErrorCode() ErrorCode
        +getEntryId() String
    }
    class BrokerExtractionException {
        "Verification failures"
    }
    class BrokerTransportException {
        "Broker write failures"
    }
    class DataSerializationException {
        "Payload serialization failures"
    }
    class DataDeserializationException {
        "Payload deserialization failures"
    }
    class SessionCapacityExceededException {
        +getGroupId() String
        +getMaxSessions() int
    }
    RuntimeException <|-- VeridotException
    VeridotException <|-- BrokerExtractionException
    VeridotException <|-- BrokerTransportException
    VeridotException <|-- DataSerializationException
    VeridotException <|-- DataDeserializationException
    VeridotException <|-- SessionCapacityExceededException
```

## Exception Reference

| Exception | Thrown by | When | Has ErrorCode |
|---|---|---|:---:|
| `VeridotException` | Internal | Any Protocol V4 violation | ✅ |
| `BrokerExtractionException` | `verify()` | Token invalid, expired, revoked, or metadata unavailable | Sometimes |
| `BrokerTransportException` | `sign()` | Publishing metadata to broker fails (network, timeout) | ❌ |
| `DataSerializationException` | `sign()` | Payload cannot be serialized by the configurer's serializer | ❌ |
| `DataDeserializationException` | `verify()` | Payload valid but cannot be deserialized to target type | ❌ |
| `SessionCapacityExceededException` | `sign()` | Max sessions reached under `REJECT` policy | ❌ |

## ErrorCode and entryId

Every `VeridotException` can carry structured diagnostic information:

```java
try {
    verifier.verify(token, s -> s);
} catch (VeridotException e) {
    ErrorCode code = e.getErrorCode();  // e.g., ErrorCode.LIVENESS_NOT_ESTABLISHED
    String entryId = e.getEntryId();    // e.g., "group:user-123/LIVENESS/session-A"

    if (code != null) {
        log.error("Veridot error {} ({}) for entry {}",
            code.name(), code.code, entryId);
    }
}
```

### Protocol V4 Error Codes

| Code | Name | Description |
|---|---|---|
| `V4001` | `INVALID_ENVELOPE` | Magic bytes or protocol version mismatch |
| `V4002` | `UNREGISTERED_ENTRY_TYPE` | Unknown entry type code |
| `V4003` | `INVALID_IDENTIFIER_LENGTH` | Scope or key length out of bounds |
| `V4004` | `INVALID_PAYLOAD_LENGTH` | Payload length out of bounds |
| `V4005` | `RESERVED_FLAG_SET` | Reserved flag bit set, or `COMPACT_SIG` inconsistent with `sigAlg` |
| `V4006` | `INVALID_SCOPE_GRAMMAR` | Scope doesn't match `group:*`, `site:*`, or `global` |
| `V4007` | `MALFORMED_PAYLOAD` | TLV payload doesn't match entry type schema |
| `V4101` | `TRUST_RESOLUTION_FAILED` | Issuer unresolvable or signature verification failed |
| `V4102` | `CAPABILITY_NOT_FOUND` | No valid capability authorizes issuer for scope |
| `V4103` | `CAPABILITY_EXPIRED` | Capability found but expired |
| `V4104` | `DELEGATION_DEPTH_EXCEEDED` | Capability chain exceeds `maxDelegationDepth` |
| `V4201` | `STALE_VERSION` | Entry version ≤ recorded watermark, or version = 0 |
| `V4202` | `LIVENESS_NOT_ESTABLISHED` | No fresh `ACTIVE` liveness attestation available |
| `V4203` | `KEY_EPOCH_EXPIRED` | Current time outside `[validFrom, validUntil)` |
| `V4204` | `SIGALG_KEY_MISMATCH` | Signature algorithm inconsistent with resolved key type |
| `V4205` | `DECRYPTION_FAILED` | Hybrid decryption of SECURE_PAYLOAD failed |
| `V4301` | `FENCE_TOKEN_STALE` | Fence counter ≤ recorded watermark |
| `V4302` | `CAPACITY_EXCEEDED` | Max sessions reached under `REJECT` policy |
| `V4401` | `TRANSPORT_UNAVAILABLE` | Broker read/write failure |
| `V4402` | `RECONCILIATION_STALE` | Reconciliation staleness limit exceeded |

## Catch Patterns

### For sign()

```java
try {
    String token = signer.sign(payload,
        BasicConfigurer.builder()
            .groupId("user-123")
            .validity(3600)
            .build());
    return Response.ok(token).build();

} catch (DataSerializationException e) {
    // Payload object can't be serialized — fix your data model
    log.error("Serialization failed: {}", e.getMessage());
    return Response.status(400).entity("Invalid payload format").build();

} catch (SessionCapacityExceededException e) {
    // REJECT policy hit the limit
    log.warn("Capacity exceeded for {}: max {}", e.getGroupId(), e.getMaxSessions());
    return Response.status(429)
        .header("Retry-After", "60")
        .entity("Too many active sessions").build();

} catch (BrokerTransportException e) {
    // Broker is unreachable — token was NOT issued
    log.error("Broker unavailable: {}", e.getMessage());
    return Response.status(503).entity("Service temporarily unavailable").build();

} catch (VeridotException e) {
    // Catch-all for other protocol violations
    log.error("Signing failed: {} ({})", e.getMessage(),
        e.getErrorCode() != null ? e.getErrorCode().code : "N/A");
    return Response.status(500).build();
}
```

### For verify()

```java
try {
    VerifiedData<UserClaims> result = verifier.verify(token,
        BasicConfigurer.deserializer(UserClaims.class));
    return Response.ok(result.data()).build();

} catch (DataDeserializationException e) {
    // Token is cryptographically valid but payload can't be deserialized
    // This is a schema error, NOT a security violation
    log.warn("Deserialization failed: {}", e.getMessage());
    return Response.status(400).entity("Payload schema mismatch").build();

} catch (BrokerExtractionException e) {
    // Token is invalid, expired, revoked, or unverifiable
    log.info("Verification rejected: {}", e.getMessage());
    return Response.status(401).entity("Invalid or expired token").build();
}
```

## HTTP Status Code Mapping

| Exception | Recommended HTTP Status | Rationale |
|---|:---:|---|
| `DataSerializationException` | `400 Bad Request` | Client sent unsupported payload |
| `DataDeserializationException` | `400 Bad Request` | Schema mismatch (not a security issue) |
| `BrokerExtractionException` | `401 Unauthorized` | Token verification failed |
| `SessionCapacityExceededException` | `429 Too Many Requests` | Rate/capacity limiting |
| `BrokerTransportException` | `503 Service Unavailable` | Infrastructure issue |
| `VeridotException` (other) | `500 Internal Server Error` | Unexpected protocol violation |

:::warning
Always return `401` for `BrokerExtractionException`, not `403`. Veridot intentionally does not distinguish between "revoked," "expired," and "forged" tokens at the API boundary — this prevents attackers from learning which checks their forgery passed.
:::

## Next Steps

- [Environment Variables](./environment-variables.md) — tune timeouts and thresholds that influence error behavior
- [Core Concepts](./core-concepts.md) — understand the protocol primitives behind these errors
