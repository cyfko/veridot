---
title: Signing Tokens
description: Issue cryptographically signed tokens with the BasicConfigurer builder — distribution modes, algorithms, and serialization.
keywords: [veridot, signing, token, BasicConfigurer, DIRECT, NATIVE, PRIVATE, ED25519, ML_DSA_65]
sidebar_position: 3
---

# Signing Tokens

Token signing in Veridot V5 is performed through the `DataSigner.sign()` method, configured via the `BasicConfigurer` builder. Under the hood, each call generates the appropriate entry (`SIGNED_DATA`, `SECURE_PAYLOAD` or raw JWT), publishes a `LIVENESS(ACTIVE)` entry, and returns the token. Veridot V5 uses a **Single-Key Architecture**, so the instance's singular private key signs all envelopes.

## The BasicConfigurer Builder

```java
String result = signer.sign(payload,
    BasicConfigurer.builder()
        .groupId("user-123")           // Required — business entity identifier
        .sequenceId("session-A")       // Optional — auto-UUID if omitted
        .validity(3600)                // Required — seconds
        .distribution(DistributionMode.DIRECT)  // Optional — default: DIRECT
        .serializedBy(obj -> toJson(obj))       // Optional — default: Jackson
        .recipients(List.of("verifier-1@hash")) // Optional — for PRIVATE mode
        .mimeType("application/json")           // Optional — for NATIVE/PRIVATE modes
        .build());
```

### Builder Methods Reference

| Method | Required | Default | Description |
|---|:---:|---|---|
| `groupId(String)` | ✅ | — | Business entity identifier (1–125 chars) |
| `sequenceId(String)` | ❌ | Auto-UUID | Session identifier within the group |
| `validity(long)` | ✅ | — | Token validity window in seconds |
| `distribution(DistributionMode)` | ❌ | `DIRECT` | How the token is delivered to the caller |
| `serializedBy(Function<Object, String>)` | ❌ | Jackson `ObjectMapper` | Custom payload serializer |
| `recipients(List<String>)` | ❌ | Empty | Authorized recipient subjects for `PRIVATE` mode |
| `mimeType(String)` | ❌ | `null` | MIME type hint for `NATIVE`/`PRIVATE` payloads |

## Signature Algorithms

Veridot V5 supports the following algorithms for the envelope signature:

| Enum | Wire Code | Key Type | Status |
|---|:---:|---|---|
| `Algorithm.ED25519` | `1` | Ed25519 | Default |
| `Algorithm.EC_P256` | `2` | EC | Supported |
| `Algorithm.EC_P384` | `3` | EC | Supported |
| `Algorithm.RSA_2048`| `4` | RSA | Default |
| `Algorithm.RSA_4096`| `5` | RSA | Default |
| `Algorithm.ED25519_MLDSA65` | `6` | PQ | Supported (Post-Quantum) |

:::tip[Recommendation]
**Use `ED25519`** or **`ML_DSA_65`** for new deployments. Ed25519 provides fast, deterministic signatures. `ML_DSA_65` provides NIST level-3 post-quantum security (but cannot be used in DIRECT mode due to lack of JWT standard).
:::

```java
var sv = new GenericSignerVerifier(
    broker, trustRoot, "auth-service",
    instancePrivateKey,
    Algorithm.ED25519  // ← instance's signature algorithm
);
```

## Distribution Modes

### Comparison: DIRECT vs NATIVE API

Many developers default to standard JWTs (`DIRECT`), but Veridot's true power lies in the offline, broker-based `NATIVE` mode. Here is a comparison of how elegant the transition is in Java 21:

```java
// ❌ DIRECT Mode (Legacy mindset: sends huge JWTs over the wire)
String directJwt = signer.sign(largeUserPayload, BasicConfigurer.builder()
    .groupId("user-123")
    .validity(3600)
    .distribution(DistributionMode.DIRECT) // Heavy JWT returned
    .build());

// ✅ NATIVE Mode (V5 mindset: stores payload in broker, returns 20-byte reference)
String nativeRef = signer.sign(largeUserPayload, BasicConfigurer.builder()
    .groupId("user-123")
    .validity(3600)
    .distribution(DistributionMode.NATIVE) // Returns compact "8:user-123:seq"
    .build());
```

### DIRECT — Self-Contained JWT

The signed data is returned directly to the caller as a JWT string.

```java
// Issue a token valid for 1 hour
String jwt = signer.sign("user@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .validity(3600)
        .build());

// jwt → "eyJhbGciOiJFZERTQSIs..." 
```

**Return value:** The complete signed JWT string.

### NATIVE — Broker-Stored Reference

The payload is natively encoded into a `SIGNED_DATA (0x08)` entry and stored on the broker. A compact reference is returned.

```java
String messageId = signer.sign(sensitivePayload,
    BasicConfigurer.builder()
        .groupId("service-X")
        .distribution(DistributionMode.NATIVE)
        .validity(300)
        .build());

// messageId → "8:group:service-X:uuid" (compact reference)
```

**Return value:** A Protocol V5 reference in the format `8:<scope>:<key>`.

### PRIVATE — End-to-End Encrypted

The payload is encrypted with AES-256-GCM and stored as a `SECURE_PAYLOAD (0x07)` entry. Only explicitly listed recipient identities can decrypt it.

```java
String ref = signer.sign(medicalRecord,
    BasicConfigurer.builder()
        .groupId("patient-456")
        .distribution(DistributionMode.PRIVATE)
        .recipients(List.of("radiology-service@hash123", "oncology-service@hash456"))
        .mimeType("application/json")
        .validity(86400)
        .build());

// ref → "7:group:patient-456:session-uuid" 
```

**Return value:** A reference token in the format `7:<scope>:<key>`.

## What Happens Under the Hood

When `sign()` is called in V5:

1. The payload is serialized via the configurer's serializer.
2. The envelope (JWT, `SIGNED_DATA`, or `SECURE_PAYLOAD`) is built and signed by the instance's **long-term private key**.
3. If mode is NATIVE or PRIVATE, the entry is published to the broker.
4. A `LIVENESS(ACTIVE)` entry is published to the broker to attest session validity.
5. A periodic renewal loop is started for the `LIVENESS` attestation.
6. The token (or reference) is returned to the caller.

## Next Steps

- [Verifying Tokens](./verifying-tokens.md) — the verification pipeline
- [Distribution Modes](./distribution-modes.md) — deep dive into DIRECT, NATIVE, and PRIVATE
- [Revoking Sessions](./revoking-sessions.md) — invalidate tokens before they expire
