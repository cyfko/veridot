---
title: Distribution Modes
description: Deep dive into DIRECT, NATIVE, and PRIVATE distribution modes — when to use each, wire formats, and complete code examples.
keywords: [veridot, distribution, DIRECT, NATIVE, PRIVATE, E2EE, hybrid encryption, AES-GCM]
sidebar_position: 6
---

# Distribution Modes

Veridot V5 supports three distribution modes that control how the signed payload is delivered to the caller after signing. Each mode produces a different token format and has distinct security properties. All modes are inherently offline-verifiable once the key is cached.

## Overview

```mermaid
graph LR

    %% Premium Theme
    linkStyle default stroke:#888888,stroke-width:2px;
    classDef default fill:#424242,stroke:#616161,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef service fill:#4527a0,stroke:#5e35b1,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef broker fill:#004d40,stroke:#00695c,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef db fill:#bf360c,stroke:#d84315,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef taas fill:#01579b,stroke:#0277bd,stroke-width:1px,color:#fff,rx:5px,ry:5px;

    subgraph DIRECT
        D1[sign] --> D2[JWT string returned to caller]
        D2 --> D3[Verifier receives JWT]
        D3 --> D4["Resolve Key from TrustRoot & Verify"]
    end

    subgraph NATIVE
        I1[sign] --> I2[SIGNED_DATA stored in broker]
        I2 --> I3[Reference token returned]
        I3 --> I4["Verifier fetches SIGNED_DATA & Verifies"]
    end

    subgraph PRIVATE
        P1[sign] --> P2["AES-GCM encrypt -> SECURE_PAYLOAD"]
        P2 --> P3[Reference token returned]
        P3 --> P4["Verifier decrypts with private key"]
    end

```

## DIRECT Mode (Default)

The signed data is returned directly to the caller as a standard JSON Web Token (JWT). The JWT is self-describing and can be transmitted in HTTP headers, cookies, or any text-based channel. It requires no broker storage for the payload itself.

```java
String jwt = signer.sign("user@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .build());

// jwt → "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJzdWIiOiI0..."
// Send as: Authorization: Bearer <jwt>
```

**Wire format:** Standard JWT (`header.payload.signature`).

**Verification:** The verifier extracts the key identifier, queries the TrustRoot (backed by TAAS) to resolve the public key, and verifies the JWT signature.

## NATIVE Mode

The signed payload is stored inside a `SIGNED_DATA` (0x08) entry on the broker. Only a compact reference token is returned to the caller.

```java
String messageId = signer.sign(sensitivePayload,
    BasicConfigurer.builder()
        .groupId("service-X")
        .distribution(DistributionMode.NATIVE)
        .build());

// messageId → "8:service-X:a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

**Wire format:** `8:<scope>:<key>` (e.g., `8:group:service-X:uuid`).

**Verification:** The verifier parses the reference token, fetches the `SIGNED_DATA` entry from the broker, resolves the TrustRoot, and validates the single binary envelope signature.

## PRIVATE Mode (E2EE)

The payload is end-to-end encrypted using hybrid encryption and stored as a `SECURE_PAYLOAD` (0x07) entry on the broker. Only explicitly listed recipient identities can decrypt it.

### Encryption Scheme

```mermaid
flowchart TD

    %% Premium Theme
    linkStyle default stroke:#888888,stroke-width:2px;
    classDef default fill:#424242,stroke:#616161,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef service fill:#4527a0,stroke:#5e35b1,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef broker fill:#004d40,stroke:#00695c,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef db fill:#bf360c,stroke:#d84315,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef taas fill:#01579b,stroke:#0277bd,stroke-width:1px,color:#fff,rx:5px,ry:5px;

    A[Plaintext payload] --> B["Generate random AES-256-GCM key (key_sym)"]
    B --> C["Encrypt payload with key_sym + nonce"]
    C --> D[Ciphertext]
    B --> E["For each recipient:"]
    E --> F["Encrypt key_sym with recipient's public key"]
    F --> G[RecipientBlock: recipientHash + encryptedKey]
    D --> H["SECURE_PAYLOAD entry"]
    G --> H
    H --> I[Publish to broker]
    I --> J["Return reference: 7:scope:key"]

```

### Signing with PRIVATE Mode

```java
String ref = signer.sign(medicalRecord,
    BasicConfigurer.builder()
        .groupId("patient-456")
        .distribution(DistributionMode.PRIVATE)
        .recipients(List.of("radiology-service@hash123", "oncology-service@hash456"))
        .mimeType("application/json")
        .build());

// ref → "7:group:patient-456:session-uuid"
```

### Verifying a PRIVATE Token

```java
// Only works if the verifier's identity is in the recipients list
VerifiedData<MedicalRecord> result = verifier.verify(ref,
    BasicConfigurer.deserializer(MedicalRecord.class));
```

:::warning
If the verifier's identity is not explicitly listed in the `recipients`, verification fails with `V4205 DECRYPTION_FAILED` — the verifier cannot decrypt the symmetric key.
:::

## Decision Table

| Criterion | DIRECT | NATIVE | PRIVATE |
|---|:---:|:---:|:---:|
| Token leaves your infrastructure | ✅ Yes | ❌ No (only reference) | ❌ No (only reference) |
| Self-describing token | ✅ Yes | ❌ No | ❌ No |
| Payload visible on broker | N/A | ✅ Yes (in SIGNED_DATA) | ❌ No (encrypted) |
| Compact token size | ❌ JWT can be large | ✅ ~50 chars | ✅ ~50 chars |
| E2E confidentiality | ❌ No | ❌ No | ✅ Yes (AES-256-GCM) |
| Recipient restriction | ❌ Anyone | ❌ Anyone with broker | ✅ Explicit recipients |

### When to Use Each

- **DIRECT** — API authentication, mobile/web session tokens, HTTP header transmission.
- **NATIVE** — Internal microservice communication, large payloads, hiding payload sizes on the wire.
- **PRIVATE** — Regulated data (HIPAA, GDPR), PII, scenarios requiring E2E encryption.

## Mixed-Mode Verification

The `verify()` method automatically detects the token format:

```java
VerifiedData<String> r1 = verifier.verify(jwt,       s -> s); // DIRECT
VerifiedData<String> r2 = verifier.verify(messageId, s -> s); // NATIVE
VerifiedData<String> r3 = verifier.verify(ref,       s -> s); // PRIVATE
```

Token format detection:
- Starts with `"7:"` → `SECURE_PAYLOAD` (PRIVATE)
- Starts with `"8:"` → `SIGNED_DATA` (NATIVE)
- Looks like a JWT (`header.payload.signature`) → JWT (DIRECT)

## Next Steps

- [Session Capacity](./session-capacity.md) — control how many concurrent sessions a group can have
- [Error Handling](./error-handling.md) — exception hierarchy for all three modes
