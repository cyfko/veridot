---
title: API Reference
description: Complete Java 21 API reference for the Veridot Protocol V5 distributed verification library.
keywords: [veridot, java api, DataSigner, TokenVerifier, GenericSignerVerifier, TAAS]
sidebar_position: 1
---

# Java API Reference (V5)

This section documents the public Java 21 API surface for Veridot Protocol V5.

## Core Interfaces

| Interface | Description |
|-----------|-------------|
| [`DataSigner`](#datasigner) | Signs a payload → returns JWT (DIRECT) or reference (NATIVE/PRIVATE) |
| [`TokenVerifier`](#tokenverifier) | Verifies a token → returns `VerifiedData<T>` |
| [`TokenRevoker`](#tokenrevoker) | Publishes `LIVENESS(REVOKED)` entry for a session |
| [`TokenTracker`](#tokentracker) | Checks liveness status locally |
| [`Broker`](#broker) | Untrusted storage for V5 envelopes |
| [`TrustRoot`](#trustroot) | Backed by TAAS, resolves identifiers to `TrustIdentity` |

---

## DataSigner

```java
@FunctionalInterface
public interface DataSigner {
    String sign(Object data, Configurer configurer);
}
```

In V5, identity is attestation-first and tied to the instance. The `DataSigner` uses the instance's unique ED25519 or RSA key.

### Configurer

| Method | Return | Description |
|--------|--------|-------------|
| `getGroupId()` | `String` | Logical group (e.g., userId) |
| `getSequenceId()` | `String` | Session ID within group |
| `getDistribution()` | `DistributionMode` | `DIRECT`, `NATIVE`, `PRIVATE` |

---

## TokenVerifier

```java
@FunctionalInterface
public interface TokenVerifier {
    <T> VerifiedData<T> verify(String token, Function<String, T> deserializer);
}
```

Performs full offline verification by validating the V5 Envelope signature, TAAS TrustRoot lookup, LIVENESS checking, and CAPABILITY authorization.

---

## TokenRevoker

```java
public interface TokenRevoker {
    void revoke(String groupId, String sequenceId);
}
```

Publishes a strictly monotonic `LIVENESS(REVOKED)` envelope.

---

## TrustRoot (TAAS)

```java
public sealed interface TrustRoot permits TAASClientTrustRoot, LocalCachingTrustRoot {
    PublicKey resolve(String subject);
}
```

V5 removes `KEY_EPOCH` and dynamically establishes trust via the TAAS. The `subject` string resolves to a `TrustIdentity` containing the key, algorithm, and `isRoot` flag.

---

## Enums

### Algorithm

| Value | Code | JCA Signature | Recommended |
|-------|:----:|---------------|:-----------:|
| `ED25519` | `1` (`0x01`) | `Ed25519` | ✅ |
| `EC_P256` | `2` (`0x02`) | `SHA256withECDSA` | ⚠️ |
| `EC_P384` | `3` (`0x03`) | `SHA384withECDSA` | ⚠️ |
| `RSA_2048` | `4` (`0x04`) | `SHA256withRSA` | ❌ |
| `RSA_4096` | `5` (`0x05`) | `SHA256withRSA` | ⚠️ |
| `ED25519_MLDSA65` | `6` (`0x06`) | `Ed25519+ML-DSA-65` | ✅ |

### DistributionMode

| Value | Description |
|-------|-------------|
| `DIRECT` | Standard JWT string, self-contained |
| `NATIVE` | `8:<scope>:<key>`, payload is `SIGNED_DATA` in broker |
| `PRIVATE` | `7:<scope>:<key>`, encrypted payload `SECURE_PAYLOAD` in broker |

---

## Error Codes

Veridot V5 standardizes error formats using `V5xxx` prefixes.

| Code | Meaning |
|------|---------|
| `V5001` | Magic or Version mismatch (`0x56 0x44 0x05` required) |
| `V5002` | Unregistered entryType |
| `V5005` | Trailing bytes detected |
| `V5007` | Invalid TLV structure |
| `V5101` | TAAS Attestation Failure |
| `V5102` | Invalid Subject Format for TrustIdentity |

```java
public class VeridotException extends RuntimeException {
    private final String errorCode; // e.g. "V5005"
    // ...
}
```
