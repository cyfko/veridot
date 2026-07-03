---
title: Signing Tokens
description: Issue cryptographically signed tokens with the BasicConfigurer builder — distribution modes, algorithms, and serialization.
keywords: [veridot, signing, token, BasicConfigurer, DIRECT, INDIRECT, PRIVATE, ED25519]
sidebar_position: 3
---

# Signing Tokens

Token signing in Veridot is performed through the `DataSigner.sign()` method, configured via the `BasicConfigurer` builder. Under the hood, each call generates an ephemeral key pair, publishes a `KEY_EPOCH` entry, publishes a `LIVENESS(ACTIVE)` entry, and returns either the signed JWT or a compact reference.

## The BasicConfigurer Builder

```java
String result = signer.sign(payload,
    BasicConfigurer.builder()
        .groupId("user-123")           // Required — business entity identifier
        .sequenceId("session-A")       // Optional — auto-UUID if omitted
        .validity(3600)                // Required — seconds
        .distribution(DistributionMode.DIRECT)  // Optional — default: DIRECT
        .serializedBy(obj -> toJson(obj))       // Optional — default: Jackson
        .recipients(List.of("verifier-1"))      // Optional — for PRIVATE mode
        .mimeType("application/json")           // Optional — for PRIVATE mode
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
| `recipients(List<String>)` | ❌ | Empty | Authorized recipients for `PRIVATE` mode |
| `mimeType(String)` | ❌ | `null` | MIME type hint for `PRIVATE` payloads |

## Signature Algorithms

Veridot supports four algorithms for the long-term envelope signature:

| Enum | Wire Code | JCA Algorithm | Key Type |
|---|:---:|---|---|
| `Algorithm.RSA_SHA256` | `0x01` | `SHA256withRSA` | RSA |
| `Algorithm.ECDSA_SHA256` | `0x02` | `SHA256withECDSA` | EC |
| `Algorithm.RSA_PSS` | `0x03` | `RSASSA-PSS` | RSA |
| `Algorithm.ED25519` | `0x04` | `Ed25519` | Ed25519 |

:::tip[Recommendation]
**Use `ED25519`** for all new deployments. Ed25519 provides mathematically constant-time verification (immune to timing attacks), small key/signature sizes, and is recommended by NIST SP 800-186. Veridot defaults to Ed25519 for ephemeral key pairs.
:::

```java
var sv = new GenericSignerVerifier(
    broker, trustRoot, "auth-service",
    longTermPrivateKey,
    Algorithm.ED25519  // ← envelope signature algorithm
);
```

## Distribution Modes

### DIRECT — Self-Contained JWT

The signed JWT is returned directly to the caller. This is the default mode.

```java
// Issue a token valid for 1 hour
String jwt = signer.sign("user@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .validity(3600)
        .build());

// jwt → "eyJhbGciOiJFZERTQSIs..." (self-contained, send in Authorization header)
```

**Return value:** The complete signed JWT string.

### INDIRECT — Broker-Stored Reference

The JWT is stored inside the `KEY_EPOCH` entry on the broker. Only a compact `messageId` is returned.

```java
String messageId = signer.sign(sensitivePayload,
    BasicConfigurer.builder()
        .groupId("service-X")
        .distribution(DistributionMode.INDIRECT)
        .validity(300)
        .build());

// messageId → "4:service-X:a1b2c3d4-..." (compact reference)
```

**Return value:** A Protocol V4 `messageId` in the format `<version>:<groupId>:<sequenceId>`.

### PRIVATE — End-to-End Encrypted

The payload is encrypted with AES-256-GCM using a random symmetric key. The symmetric key is then encrypted with each recipient's public key (hybrid encryption) and stored as a `SECURE_PAYLOAD` entry.

```java
String ref = signer.sign(medicalRecord,
    BasicConfigurer.builder()
        .groupId("patient-456")
        .distribution(DistributionMode.PRIVATE)
        .recipients(List.of("radiology-service", "oncology-service"))
        .mimeType("application/json")
        .validity(86400)
        .build());

// ref → "7:group:patient-456:session-uuid" (only listed recipients can decrypt)
```

**Return value:** A `SECURE_PAYLOAD` reference token in the format `7:<scope>:<key>`.

## Custom Serialization

By default, `BasicConfigurer` uses Jackson's `ObjectMapper`. Strings pass through as-is; all other objects are JSON-serialized.

```java
// Custom serializer for Protocol Buffers
String token = signer.sign(protoMessage,
    BasicConfigurer.builder()
        .groupId("device-789")
        .validity(1800)
        .serializedBy(obj -> {
            if (obj instanceof Message proto) {
                return Base64.getEncoder().encodeToString(proto.toByteArray());
            }
            throw new DataSerializationException("Expected protobuf Message");
        })
        .build());
```

## Complete Example

```java
// 1. Set up infrastructure
Broker broker = new KafkaBroker();
PublicKeyTrustRoot trustRoot = issuer ->
    new TrustIdentity(loadKey(issuer), true);

// 2. Create the signer/verifier (implements DataSigner + TokenVerifier + TokenRevoker)
var sv = new GenericSignerVerifier(
    broker, trustRoot, "auth-service",
    loadPrivateKey("auth-service"), Algorithm.ED25519
);

// 3. Sign with a POJO payload
record UserClaims(String email, String role) {}

String token = sv.sign(
    new UserClaims("admin@example.com", "ADMIN"),
    BasicConfigurer.builder()
        .groupId("user-42")
        .sequenceId("web-session")
        .validity(3600)
        .build()
);

// token is now a signed JWT ready for the Authorization header
```

## What Happens Under the Hood

1. The payload is serialized via the configurer's serializer
2. An ephemeral Ed25519 key pair is generated (rotated every 24h by default)
3. A JWT is built and signed with the ephemeral private key
4. A `KEY_EPOCH` entry is published to the broker (signed with the long-term key)
5. A `LIVENESS(ACTIVE)` entry is published to the broker
6. A periodic renewal loop is started for the `LIVENESS` attestation
7. The token (or messageId/reference) is returned

## Next Steps

- [Verifying Tokens](./verifying-tokens.md) — the 9-step verification pipeline
- [Distribution Modes](./distribution-modes.md) — deep dive into DIRECT, INDIRECT, and PRIVATE
- [Revoking Sessions](./revoking-sessions.md) — invalidate tokens before they expire
