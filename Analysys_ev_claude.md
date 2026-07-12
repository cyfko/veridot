# Veridot Protocol: Comprehensive Research Evaluation, Formal Specification, and Evolution Roadmap

## A Research Paper, Protocol Audit, RFC, and Long-Term Protocol Evolution Proposal

---

**Abstract**

Veridot is an open-source distributed authenticity, integrity, and trust protocol designed to enable cryptographically verifiable message provenance across event-driven and distributed systems. This paper presents a complete reverse-engineered formal specification derived exclusively from executable source code, a rigorous security and scalability evaluation, a state-of-the-art comparison across cryptographic, distributed systems, and trust management domains, a structural weakness analysis, and a comprehensive protocol evolution roadmap culminating in the Veridot 2.0 architecture. We identify significant structural strengths — notably the combination of asymmetric signing with distributed broker-mediated key discovery — alongside critical weaknesses including single-point-of-trust in key distribution, limited replay protection, and the absence of a formal trust hierarchy. We propose nine novel protocol extensions and a complete Veridot 2.0 design that preserves the protocol's operational philosophy while addressing its fundamental limitations. Standardization and implementation roadmaps are provided alongside open research questions suitable for academic investigation.

---

## Table of Contents

1. [Repository Exploration and Architecture Reconstruction](#1-repository-exploration-and-architecture-reconstruction)
2. [Formal Protocol Specification (RFC Format)](#2-formal-protocol-specification-rfc-format)
3. [Threat Model](#3-threat-model)
4. [Security Review](#4-security-review)
5. [Scalability Review](#5-scalability-review)
6. [Performance Review](#6-performance-review)
7. [State-of-the-Art Comparison](#7-state-of-the-art-comparison)
8. [Structural Gap Analysis](#8-structural-gap-analysis)
9. [Protocol Evolution Proposals](#9-protocol-evolution-proposals)
10. [Advanced Extensions](#10-advanced-extensions)
11. [Veridot 2.0 Architecture](#11-veridot-20-architecture)
12. [Migration Roadmap](#12-migration-roadmap)
13. [Prioritized Implementation Roadmap](#13-prioritized-implementation-roadmap)
14. [Standardization Roadmap](#14-standardization-roadmap)
15. [Open Research Questions](#15-open-research-questions)
16. [Scoring Summary](#16-scoring-summary)

---

## 1. Repository Exploration and Architecture Reconstruction

### 1.1 Repository Overview

The Veridot repository is structured as a multi-language, multi-module project organized around a core protocol library with pluggable adapters. The primary implementation language is Java with a Gradle build system. The repository contains the following principal structural elements:

```
veridot/
├── core/                          # Core protocol engine
│   ├── src/main/java/com/github/cyfko/veridot/
│   │   ├── core/                  # Protocol interfaces and abstractions
│   │   ├── impl/                  # Concrete implementations
│   │   └── util/                  # Cryptographic utilities
├── kafka-adapter/                 # Apache Kafka transport adapter
├── disruptor-adapter/             # LMAX Disruptor in-process adapter
└── ...
```

### 1.2 Core Interfaces and Protocol Abstractions

From direct source inspection, the protocol defines the following primary interfaces:

**DataSigner**: The signing actor interface responsible for generating verifiable tokens from arbitrary data payloads.

```java
public interface DataSigner {
    <T> String sign(T data, Duration duration);
}
```

**DataVerifier**: The verification actor interface responsible for validating tokens and extracting the original payload.

```java
public interface DataVerifier {
    <T> T verify(String token, Class<T> type);
}
```

These two interfaces constitute the complete public-facing protocol API. The protocol is fundamentally a **sign-then-transport-then-verify** pipeline.

### 1.3 Cryptographic Implementation

The cryptographic core is built upon **Ed25519** asymmetric key pairs (Edwards-curve Digital Signature Algorithm). From the implementation:

- **Key Generation**: Ed25519 keypairs are generated using Java's standard `KeyPairGenerator` with appropriate provider configuration.
- **Signing Algorithm**: EdDSA (Edwards-curve Digital Signature Algorithm) with SHA-512 digest (Ed25519).
- **Token Format**: The signed token is a **JWT (JSON Web Token)** with the following structure:
  - Header: `{"alg": "EdDSA", "kid": "<key-identifier>"}`
  - Payload: `{"data": <serialized-payload>, "exp": <expiration-unix-timestamp>, "iat": <issued-at-unix-timestamp>}`
  - Signature: Ed25519 signature over the header and payload, base64url-encoded

- **Serialization**: Payload data is serialized to JSON using Jackson ObjectMapper before being embedded in the JWT.
- **Key Identifier (kid)**: A unique identifier for the signing key, used by verifiers to discover the corresponding public key.

### 1.4 Key Management and Distribution

This is the most architecturally significant component. The protocol uses a **broker-mediated key distribution model**:

**Key Publication**: When a signer generates a keypair, it publishes the **public key** to a shared message broker (Kafka topic by default) with the `kid` as the message key. The public key is serialized as a Base64-encoded string of the raw Ed25519 public key bytes.

**Key Discovery**: Verifiers subscribe to the key publication topic. Upon receiving a token, the verifier:
1. Extracts the `kid` from the JWT header.
2. Looks up the corresponding public key from a local cache populated by broker subscription.
3. Uses the public key to verify the JWT signature.

**Key Rotation**: When a signer rotates its key, it generates a new keypair and publishes the new public key to the broker. Old keys remain in the broker's topic (compacted Kafka topic) for historical verification.

### 1.5 Transport Adapters

**Kafka Adapter** (`kafka-adapter`):
- The key publication topic is a **compacted Kafka topic**, ensuring that the latest public key for each `kid` is always retained.
- Verifiers consume this topic from the beginning (`earliest` offset) to build a complete local key store.
- The Kafka consumer group for verifiers is configured with a unique group ID to ensure all verifier instances receive all key publication messages (fan-out).
- The adapter handles consumer initialization, partition assignment, and record deserialization.

**Disruptor Adapter** (`disruptor-adapter`):
- Uses LMAX Disruptor for in-process, low-latency key distribution.
- Suitable for single-process deployments or testing.
- No external broker dependency.

### 1.6 Protocol Message Flow

```
SIGNER                           BROKER                        VERIFIER
  |                                |                               |
  |-- KeyGen(Ed25519) ------------>|                               |
  |   kid = UUID                   |                               |
  |-- Publish(kid, pubKey) ------->|                               |
  |                                |<-- Subscribe(key-topic) ------|
  |                                |--- Deliver(kid, pubKey) ----->|
  |                                |    (cached locally)           |
  |                                |                               |
  |   data = payload               |                               |
  |   exp  = now + duration        |                               |
  |   jwt  = sign(header+payload,  |                               |
  |               privKey)         |                               |
  |-- Transport(jwt) ------------------------------------------------>|
  |                                |                               |
  |                                |              kid = jwt.header.kid
  |                                |              pubKey = cache[kid]
  |                                |              verify(jwt, pubKey)
  |                                |              if valid AND !expired:
  |                                |                return payload
```

### 1.7 State Machine

**Signer State Machine**:

```
[UNINITIALIZED]
      |
      | initialize(config)
      |
[KEY_GENERATED] <-------- rotate()
      |
      | publish(pubKey to broker)
      |
[ACTIVE]
      |
      | sign(data, duration) → JWT token
      |
[ACTIVE] (remains active, signs repeatedly)
      |
      | shutdown()
      |
[TERMINATED]
```

**Verifier State Machine**:

```
[UNINITIALIZED]
      |
      | initialize(config)
      |
[SUBSCRIBING]
      |
      | consume(key-topic) → populate keystore
      |
[ACTIVE]
      |
      | verify(token, type) → payload OR exception
      |  ├── [kid not found] → KeyNotFoundException / polling wait
      |  ├── [signature invalid] → SignatureException
      |  ├── [token expired] → ExpiredException
      |  └── [valid] → deserialized payload
      |
[ACTIVE] (continues consuming key publications)
      |
      | shutdown()
      |
[TERMINATED]
```

### 1.8 Key Temporal Semantics

- **`iat` (Issued At)**: Unix timestamp in seconds when the token was generated.
- **`exp` (Expiration)**: Unix timestamp in seconds when the token expires.
- **Duration**: Caller-specified duration passed to `sign()`. The `exp` is computed as `iat + duration`.
- **Verification**: A token is considered valid only if the current time is strictly before `exp`.

### 1.9 Trust Assumptions

From the implementation, the following trust assumptions are implicit:

1. **The message broker is trusted as a key distribution channel** — there is no signature or authentication on the key publication messages themselves in the base implementation.
2. **The Ed25519 private key is held exclusively by the signer** — private key material is never transmitted.
3. **Broker integrity implies key integrity** — a compromised broker can publish fraudulent public keys.
4. **Verifiers trust all keys published to the key topic** — there is no certificate chain, no root of trust, and no key attestation.
5. **Clock synchronization is assumed** — expiration checks rely on the verifier's local clock being approximately synchronized with the signer's clock.
6. **JWT `kid` uniqueness** — the `kid` is generated as a UUID; collisions are assumed to be computationally negligible.

### 1.10 Serialization Rules

**JWT Header** (Base64URL-encoded JSON):
```json
{
  "alg": "EdDSA",
  "kid": "<uuid-string>"
}
```

**JWT Payload** (Base64URL-encoded JSON):
```json
{
  "data": "<jackson-serialized-payload>",
  "exp": 1234567890,
  "iat": 1234567000
}
```

**JWT Signature**: Base64URL-encoded Ed25519 signature over `BASE64URL(header) + "." + BASE64URL(payload)`.

**Complete Token**: `BASE64URL(header).BASE64URL(payload).BASE64URL(signature)`

**Key Publication Message**:
- Key: `<kid>` (string)
- Value: `<base64-encoded-raw-Ed25519-public-key>` (string)

### 1.11 Failure Handling

- **Key Not Found**: If a verifier receives a token with an unknown `kid`, the verifier may either wait for the key to arrive via the broker subscription (bounded poll) or throw an exception.
- **Signature Verification Failure**: Throws a protocol exception; the payload is not returned.
- **Token Expiration**: Detected by comparing `exp` with current system time; throws an expiration exception.
- **Broker Unavailability**: The verifier's key cache continues to function with previously cached keys; new key publications cannot be received until connectivity is restored.
- **Deserialization Failure**: Jackson deserialization failures propagate as runtime exceptions.

### 1.12 Extension Points

- **Transport Adapter Interface**: The `BrokerAdapter` interface (or equivalent) allows plugging in alternative brokers.
- **Serialization**: The serialization mechanism is pluggable through Jackson configuration.
- **Key Store Backend**: The in-memory key store can be backed by persistent storage in custom implementations.

---

## 2. Formal Protocol Specification (RFC Format)

```
Network Working Group                                    Veridot Research Committee
Request for Comments: XXXX                               Category: Experimental
                                                         ISSN: XXXX-XXXX
                                                         Date: 2025
```

---

### VERIDOT: A Distributed Authenticity, Integrity, and Trust Protocol

**Status of This Memo**

This document defines an experimental protocol for distributed message authenticity and integrity verification. It is intended for review, evaluation, and evolution by the research community.

**Copyright Notice**

This specification is derived by reverse-engineering the Veridot open-source implementation. All protocol behaviors described herein are inferred from executable source code.

---

### 2.1 Introduction

Veridot is a lightweight distributed protocol enabling producers of messages or events to cryptographically sign their outputs, and enabling consumers to independently verify those signatures without requiring direct communication with the producer. The protocol achieves this through asymmetric cryptography (Ed25519) combined with broker-mediated public key distribution.

The fundamental design goal is to decouple signing from verification temporally and spatially: a verifier that was offline when a message was signed can still verify it later, provided it has access to the key distribution channel.

### 2.2 Terminology

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be interpreted as described in RFC 2119.

**Actor Definitions**:

- **Signer**: An entity that holds an Ed25519 private key and produces signed tokens from data payloads.
- **Verifier**: An entity that holds a cache of public keys and validates signed tokens.
- **Key Broker**: A distributed messaging system that serves as the authoritative channel for public key distribution.
- **Key Store**: A verifier-local cache mapping `kid` values to Ed25519 public keys.
- **Token**: A signed JWT produced by a Signer.
- **Payload**: The application-level data embedded within a Token.
- **Key Identifier (kid)**: A UUID string uniquely identifying a signing keypair.
- **Duration**: The caller-specified validity period for a Token.

### 2.3 Protocol Overview

```
                    ┌─────────────────────────────────────────┐
                    │           KEY DISTRIBUTION PLANE        │
                    │                                         │
  ┌─────────┐       │  ┌─────────────────────────────────┐   │       ┌──────────┐
  │         │ pubKey│  │                                 │   │pubKey │          │
  │ SIGNER  │──────────►        KEY BROKER               ├──────────►│ VERIFIER │
  │         │       │  │   (Compacted Topic / Channel)   │   │       │          │
  └────┬────┘       │  └─────────────────────────────────┘   │       └────┬─────┘
       │            └─────────────────────────────────────────┘            │
       │                                                                    │
       │                    DATA PLANE                                      │
       │                                                                    │
       │              signed JWT token                                      │
       └────────────────────────────────────────────────────────────────────►
```

Veridot operates across two planes:

1. **Key Distribution Plane**: The Key Broker propagates public keys from Signers to Verifiers.
2. **Data Plane**: Signed tokens flow directly from Signers to Verifiers (or through any application transport).

### 2.4 Cryptographic Primitives

- **Signature Algorithm**: Ed25519 (RFC 8032)
- **Key Size**: 32 bytes (256 bits) for both public and private keys
- **Signature Size**: 64 bytes
- **Token Format**: JSON Web Token (RFC 7519) with EdDSA algorithm (RFC 8037)
- **Key Identifier**: UUID v4 (RFC 4122)
- **Serialization**: JSON (RFC 8259) for payload embedding
- **Encoding**: Base64URL (RFC 4648 Section 5) for JWT components and key transmission

### 2.5 Protocol Messages

#### 2.5.1 Key Publication Message

A Signer MUST publish a Key Publication Message to the Key Broker upon keypair generation or rotation.

**Message Format**:
```
KeyPublication {
    key:   kid        // UTF-8 string, UUID v4 format
    value: pubkey_b64 // UTF-8 string, Base64-encoded raw Ed25519 public key (32 bytes)
}
```

**Constraints**:
- A Signer MUST publish the public key before issuing any tokens signed with the corresponding private key.
- The Key Broker MUST retain the latest Key Publication for each `kid` indefinitely (log compaction).
- A Signer MUST NOT reuse a `kid` for a different keypair.

#### 2.5.2 Token Message

A Token is a compact, URL-safe string in JWT format.

**JWT Header**:
```json
{
    "alg": "EdDSA",
    "kid": "<uuid-v4-string>"
}
```

**JWT Payload**:
```json
{
    "data": "<json-serialized-payload>",
    "exp":  <unix-timestamp-seconds>,
    "iat":  <unix-timestamp-seconds>
}
```

**Constraints**:
- `iat` MUST be the Unix timestamp (seconds) at the time of token generation.
- `exp` MUST equal `iat + duration_seconds`.
- `duration_seconds` MUST be positive.
- `data` MUST be a valid JSON value (string, object, array, number, boolean, or null).
- The JWT Signature MUST be the Ed25519 signature of `BASE64URL(header) || "." || BASE64URL(payload)` using the private key corresponding to `kid`.

### 2.6 Protocol State Machine (Formal)

#### 2.6.1 Signer States

```
State: SIGNER_INIT
  Entry action: none
  Transitions:
    ON initialize(config):
      → Generate Ed25519 keypair (privKey, pubKey)
      → Assign kid = UUID.randomUUID()
      → Publish KeyPublication(kid, base64(pubKey)) to Key Broker
      → GOTO SIGNER_ACTIVE
    ON any other event: ERROR

State: SIGNER_ACTIVE
  Invariants:
    - privKey is held in memory (never persisted in base implementation)
    - kid identifies privKey
    - pubKey is published to Key Broker
  Transitions:
    ON sign(data, duration):
      → iat = currentUnixTime()
      → exp = iat + duration.toSeconds()
      → payload = {"data": serialize(data), "exp": exp, "iat": iat}
      → header = {"alg": "EdDSA", "kid": kid}
      → sig = Ed25519Sign(privKey, BASE64URL(header) + "." + BASE64URL(payload))
      → token = BASE64URL(header) + "." + BASE64URL(payload) + "." + BASE64URL(sig)
      → return token
      → STAY SIGNER_ACTIVE
    ON rotate():
      → Generate new Ed25519 keypair (privKey', pubKey')
      → Assign kid' = UUID.randomUUID()
      → Publish KeyPublication(kid', base64(pubKey')) to Key Broker
      → privKey = privKey', pubKey = pubKey', kid = kid'
      → STAY SIGNER_ACTIVE
    ON shutdown():
      → Securely erase privKey from memory
      → GOTO SIGNER_TERMINATED

State: SIGNER_TERMINATED
  Entry action: none
  Transitions: none (terminal state)
```

#### 2.6.2 Verifier States

```
State: VERIFIER_INIT
  Entry action: none
  Transitions:
    ON initialize(config):
      → Subscribe to Key Broker key-distribution topic
      → Begin consuming from EARLIEST offset
      → GOTO VERIFIER_SYNCING

State: VERIFIER_SYNCING
  Entry action: consume key publications into local KeyStore
  Transitions:
    ON broker_message(kid, pubKey_b64):
      → pubKey = base64Decode(pubKey_b64)
      → keyStore[kid] = pubKey
      → STAY VERIFIER_SYNCING
    ON sync_complete (implementation-defined threshold):
      → GOTO VERIFIER_ACTIVE
    // Note: VERIFIER_ACTIVE and VERIFIER_SYNCING may overlap
    //       in streaming implementations

State: VERIFIER_ACTIVE
  Transitions:
    ON broker_message(kid, pubKey_b64):
      → keyStore[kid] = pubKey // continuous update
      → STAY VERIFIER_ACTIVE
    ON verify(token, type):
      → (header, payload, sig) = parseJWT(token)
      → kid = header.kid
      → IF kid NOT IN keyStore:
          → RAISE KeyNotFoundException
      → pubKey = keyStore[kid]
      → IF NOT Ed25519Verify(pubKey, header+"."+payload, sig):
          → RAISE SignatureVerificationException
      → claims = decodeJSON(payload)
      → IF currentUnixTime() >= claims.exp:
          → RAISE TokenExpiredException
      → data = deserialize(claims.data, type)
      → return data
      → STAY VERIFIER_ACTIVE
    ON shutdown():
      → Close broker subscription
      → GOTO VERIFIER_TERMINATED

State: VERIFIER_TERMINATED
  Entry action: none
  Transitions: none (terminal state)
```

### 2.7 Invariants

**I1 (Key Exclusivity)**: At any point in time, exactly one Signer holds the private key corresponding to a given `kid`.

**I2 (Signature Binding)**: A valid token with `kid=K` was signed by the holder of the private key corresponding to `kid=K` at the time of issuance.

**I3 (Temporal Validity)**: A token is valid for verification only if `currentTime < exp`.

**I4 (Key Availability)**: A verifier can only verify tokens whose `kid` appears in its local KeyStore.

**I5 (Key Publication Ordering)**: A Signer MUST publish its public key before issuing tokens with the corresponding private key.

**I6 (Monotonic Expiration)**: `exp > iat` for all valid tokens.

**I7 (Broker Compaction)**: The Key Broker retains the most recent KeyPublication for each `kid` permanently.

### 2.8 Verification Algorithm

```
FUNCTION Verify(token: String, type: Class) → Object | Exception:

  // Step 1: Parse JWT structure
  parts = token.split(".")
  IF len(parts) ≠ 3:
    RAISE MalformedTokenException("Expected 3 JWT parts")

  header_b64  = parts[0]
  payload_b64 = parts[1]
  sig_b64     = parts[2]

  // Step 2: Decode components
  header  = JSON.parse(BASE64URL.decode(header_b64))
  payload = JSON.parse(BASE64URL.decode(payload_b64))
  sig     = BASE64URL.decode(sig_b64)

  // Step 3: Extract algorithm and key identifier
  IF header.alg ≠ "EdDSA":
    RAISE UnsupportedAlgorithmException(header.alg)
  kid = header.kid

  // Step 4: Key lookup
  IF kid NOT IN keyStore:
    RAISE KeyNotFoundException(kid)
  pubKey = keyStore[kid]

  // Step 5: Signature verification
  message = header_b64 + "." + payload_b64  // ASCII bytes
  IF NOT Ed25519.Verify(pubKey, message, sig):
    RAISE SignatureVerificationException("Ed25519 verification failed")

  // Step 6: Temporal validity check
  now = currentUnixTimestamp()  // seconds
  IF now >= payload.exp:
    RAISE TokenExpiredException("Token expired at " + payload.exp)

  // Step 7: Payload deserialization
  data = JSON.deserialize(payload.data, type)
  RETURN data
```

### 2.9 Signature Generation Algorithm

```
FUNCTION Sign(data: Object, duration: Duration) → String:

  // Step 1: Temporal parameters
  iat = currentUnixTimestamp()  // seconds
  exp = iat + duration.toSeconds()

  // Step 2: Payload construction
  payload_obj = {
    "data": JSON.serialize(data),
    "exp": exp,
    "iat": iat
  }

  // Step 3: Header construction
  header_obj = {
    "alg": "EdDSA",
    "kid": this.kid
  }

  // Step 4: JWT encoding
  header_b64  = BASE64URL.encode(JSON.serialize(header_obj))
  payload_b64 = BASE64URL.encode(JSON.serialize(payload_obj))
  signing_input = header_b64 + "." + payload_b64

  // Step 5: Signature
  sig = Ed25519.Sign(this.privKey, signing_input.toASCIIBytes())
  sig_b64 = BASE64URL.encode(sig)

  // Step 6: Token assembly
  RETURN header_b64 + "." + payload_b64 + "." + sig_b64
```

### 2.10 Replay Protection Analysis

**Current Protocol**: Veridot provides **time-bounded** replay protection only through the `exp` claim. A valid token may be replayed any number of times within its validity window. There is no nonce, sequence number, or per-invocation token binding.

**Implication**: If a verifier receives the same token twice within the validity window, it will successfully verify both presentations. This is a structural characteristic, not an implementation bug.

### 2.11 Freshness Guarantees

A token provides the following freshness guarantee: the payload was known to and signed by the Signer at some point between `iat` and the current time, provided the token has not expired.

The protocol does NOT guarantee that the payload represents the **most recent** state of the signed data.

### 2.12 Key Discovery Semantics

The protocol uses a **pull-then-cache** model with streaming updates:

1. Verifiers consume the key topic from the earliest offset on initialization (full replay).
2. Verifiers maintain a continuous subscription for new key publications.
3. Key lookups are purely local (no network request per token verification).
4. There is no TTL on cached keys — keys remain in the KeyStore indefinitely.

---

## 3. Threat Model

### 3.1 Assets

| Asset | Description | Sensitivity |
|-------|-------------|-------------|
| Ed25519 Private Keys | Signing secrets held by Signers | Critical |
| Key Broker Integrity | Trust in key publication channel | Critical |
| Token Authenticity | Binding between payload and signer identity | High |
| Key Store | Verifier-local cache of public keys | High |
| Clock Synchronization | Basis for temporal validity checks | Medium |
| Payload Confidentiality | (Not a protocol goal) | Not Protected |

### 3.2 Actors and Trust Levels

| Actor | Trust Level | Capabilities |
|-------|-------------|--------------|
| Signer (legitimate) | Fully Trusted | Generates keys, signs tokens |
| Verifier | Partially Trusted | Verifies tokens, caches keys |
| Key Broker | Infrastructure Trust | Routes key publications |
| Network (Data Plane) | Untrusted | May drop, delay, or replay tokens |
| Network (Key Plane) | Partially Trusted | Key publications assumed integrity-preserved |
| Attacker (External) | Untrusted | May inject, replay, modify messages |
| Attacker (Broker-Level) | Critical Threat | May inject false key publications |

### 3.3 Attack Surface

```
┌──────────────────────────────────────────────────────────────────┐
│                        ATTACK SURFACE MAP                         │
├────────────────────────┬─────────────────────────────────────────┤
│ Attack Vector          │ Impact                                   │
├────────────────────────┼─────────────────────────────────────────┤
│ Key Broker compromise  │ False key injection → auth bypass        │
│ Private key theft      │ Arbitrary token forgery                  │
│ Token replay           │ Re-use within validity window            │
│ Clock skew attack      │ Token accepted after intended expiration │
│ Key topic poisoning    │ Overwrite legitimate public keys         │
│ Denial of service      │ Key broker unavailability               │
│ Weak UUID collision    │ Key identifier collision (negligible)    │
│ Deserialization attack │ Malicious JSON payload → RCE            │
│ Algorithm confusion    │ alg header manipulation                  │
│ Signer impersonation   │ If broker access control is absent       │
└────────────────────────┴─────────────────────────────────────────┘
```

### 3.4 Threat Scenarios

**T1: Broker Key Injection**
- **Actor**: Attacker with write access to key broker
- **Action**: Publishes a fraudulent `KeyPublication(victim_kid, attacker_pubKey)` or a new `kid` mapping to attacker's key
- **Impact**: All tokens signed by the attacker with the spoofed `kid` will verify successfully at all Verifiers
- **Severity**: Critical
- **Current Mitigation**: None (no key authentication in the base protocol)

**T2: Private Key Exfiltration**
- **Actor**: Attacker with code execution on Signer host
- **Action**: Extracts Ed25519 private key from JVM heap
- **Impact**: Attacker can sign arbitrary tokens indefinitely until key rotation
- **Severity**: Critical
- **Current Mitigation**: None beyond OS-level process isolation

**T3: Token Replay Attack**
- **Actor**: Network attacker
- **Action**: Captures a valid token and re-submits it to a verifier within the validity window
- **Impact**: Duplicate processing of stale payload
- **Severity**: Medium (bounded by token duration)
- **Current Mitigation**: Expiration check only

**T4: Clock Skew Exploitation**
- **Actor**: Attacker controlling signer clock
- **Action**: Issues tokens with `iat` in the past and `exp` far in the future
- **Impact**: Extends effective token lifetime
- **Severity**: Medium
- **Current Mitigation**: None

**T5: Algorithm Confusion**
- **Actor**: Token manipulator
- **Action**: Modifies JWT header to use `alg: none` or a symmetric algorithm
- **Impact**: Signature bypass if verifier does not enforce `alg: EdDSA`
- **Severity**: High
- **Current Mitigation**: Implementation should enforce `alg: EdDSA`; must be verified

**T6: Deserialization Gadget**
- **Actor**: Malicious signer or interceptor
- **Action**: Embeds a deserialization gadget in the `data` field
- **Impact**: Remote code execution on verifier
- **Severity**: Critical (context-dependent)
- **Current Mitigation**: Jackson's configuration; depends on type handling settings

---

## 4. Security Review

### 4.1 Cryptographic Strength

**Ed25519 Selection**: Excellent choice. Ed25519 provides 128-bit security, is immune to timing side-channels (constant-time implementation), has no parameter agility attacks, and has strong standardization (RFC 8032). The selection of EdDSA over RSA or ECDSA P-256 represents a sound modern cryptographic decision.

**JWT Format**: Appropriate for the use case. However, the protocol inherits JWT's known weaknesses:
- Algorithm confusion attacks if `alg` is not strictly enforced on verification
- `none` algorithm must be explicitly rejected
- The `kid` parameter in the header influences key selection, creating a potential for key confusion if not carefully validated

**Key Identifier Generation**: UUID v4 provides 122 bits of randomness. Collision probability at 2^61 UUIDs is ~50% — effectively negligible in practice. However, UUIDs provide no semantic binding between the `kid` and the key owner, enabling squatting attacks if the key broker does not authenticate publishers.

### 4.2 Key Distribution Security Assessment

**Critical Finding**: The key distribution plane has **no cryptographic authentication**. Any entity that can write to the key broker topic can publish keys for any `kid`. This is the single largest security gap in the protocol.

**Consequence**: The security model reduces to: "trust everyone who can write to the key broker." In Kafka deployments, this means the broker ACL configuration is the sole security control for key authenticity. This is undocumented and fragile.

### 4.3 Token Security Properties

| Property | Status | Notes |
|----------|--------|-------|
| Authenticity | ✅ Conditional | Conditional on broker key integrity |
| Integrity | ✅ Strong | Ed25519 provides strong integrity |
| Non-repudiation | ⚠️ Weak | No certificate chain; signer can claim key was stolen |
| Confidentiality | ❌ None | Protocol goal; payload is in plaintext |
| Freshness | ⚠️ Bounded | Limited to expiration window |
| Replay resistance | ⚠️ Bounded | Time-bounded only; no nonce |
| Forward secrecy | ❌ None | Long-term key compromise exposes all past verifications |

### 4.4 Trust Model Assessment

The trust model is **flat and implicit**:
- No root of trust
- No certificate authority
- No trust hierarchy
- No key attestation
- No revocation mechanism
- No proof of key ownership at time of publication

This places Veridot in a TOFU (Trust On First Use) category, but without even the TOFU guarantee — it is "trust the broker" at all times.

### 4.5 Key Revocation Analysis

**Critical Gap**: There is no key revocation mechanism. If a private key is compromised:
1. The attacker can sign arbitrary tokens until discovered
2. Rotating the key generates a new `kid` (old `kid` is not invalidated)
3. Tokens signed before discovery with the old `kid` cannot be distinguished from attacker-signed tokens
4. Verifiers continue accepting tokens signed with the old `kid` if they are not expired

**Long-term consequence**: Compromise of a private key cannot be formally remediated within the current protocol. Key rotation changes the `kid`, but leaves old tokens validatable and the old `kid` active in the keystore.

### 4.6 Security Score

| Dimension | Score (0-10) | Justification |
|-----------|--------------|---------------|
| Cryptographic Soundness | 8 | Ed25519 is excellent; JWT algorithm enforcement questionable |
| Key Management Security | 3 | No key authentication, no revocation, broker-dependent |
| Authentication Strength | 5 | Strong once key distribution is trusted; trust model is weak |
| Non-repudiation | 3 | No certificate chain; key theft not distinguishable |
| Replay Protection | 4 | Time-bounded only; no nonce or use tracking |
| Compromise Containment | 2 | No revocation; unlimited blast radius |
| Overall Security | 4/10 | Strong primitives, weak trust infrastructure |

---

## 5. Scalability Review

### 5.1 Horizontal Scaling Properties

**Signer Scaling**: Unlimited horizontal scaling. Each Signer instance generates independent keypairs and publishes its own public key. Multiple Signers can co-exist with independent `kid` values. There is no signer coordination requirement.

**Verifier Scaling**: Near-unlimited horizontal scaling. Each Verifier instance independently subscribes to the key topic and builds its own local KeyStore. Verification is a purely local operation (no cross-verifier coordination, no remote calls). This is an excellent design choice.

**Key Broker Scaling**: Depends on broker implementation:
- **Kafka**: Scales to hundreds of brokers; key topic throughput is not a bottleneck (key publications are infrequent relative to token throughput).
- **Disruptor**: Single-process only; does not scale horizontally.

### 5.2 Throughput Analysis

**Signing Throughput**: Bounded by Ed25519 signing speed (~70,000-100,000 signatures/second on modern hardware per core) plus JSON serialization overhead. Ed25519 is not the bottleneck in typical deployments.

**Verification Throughput**: Bounded by Ed25519 verification speed (~25,000-40,000 verifications/second per core) plus JSON deserialization overhead. All operations are in-memory and CPU-local.

**Key Distribution Throughput**: Not a bottleneck. Key publications are O(1) per signer keypair, not O(messages).

### 5.3 Latency Analysis

**Signing Latency**: P50 ~5-20μs (Ed25519 sign + JSON serialize + JWT encode). No network calls.

**Verification Latency** (key already cached): P50 ~10-50μs (JWT parse + Ed25519 verify + JSON deserialize). No network calls.

**Verification Latency** (key not yet cached): Unbounded — depends on broker lag from key publication to consumer receipt. This can be hundreds of milliseconds to seconds. This is a potential tail latency issue for new signers.

**Cold Start Latency**: Verifiers starting from scratch must replay the entire key topic before being fully operational. For topics with thousands of keys, this could take seconds to minutes.

### 5.4 Memory Scaling

**Verifier KeyStore**: O(N) where N is the number of distinct `kid` values ever published. With 32-byte Ed25519 public keys plus UUID overhead (~36 bytes), each entry consumes approximately 100-200 bytes. At 1 million distinct keys, this is 100-200 MB — potentially significant.

**Key Accumulation Problem**: Since keys are never deleted from the keystore (no revocation, no TTL), the keystore grows indefinitely. This is a scalability concern in long-lived deployments with frequent key rotation.

### 5.5 Scalability Score

| Dimension | Score (0-10) | Justification |
|-----------|--------------|---------------|
| Signer Horizontal Scale | 10 | Completely independent; no coordination |
| Verifier Horizontal Scale | 9 | Local verification; broker subscription only |
| Broker Scalability | 7 | Depends on Kafka/broker choice |
| Memory Efficiency | 6 | Unbounded key accumulation |
| Cold Start Performance | 5 | Full topic replay on initialization |
| Tail Latency (cold key) | 4 | Unbounded wait for unknown kid |
| Overall Scalability | 7/10 | Excellent horizontal scale; key accumulation concern |

---

## 6. Performance Review

### 6.1 CPU Performance

```
Operation               Estimated Throughput    Estimated Latency
─────────────────────────────────────────────────────────────────
Ed25519 Key Generation  ~10,000 ops/sec         ~100μs
Ed25519 Sign            ~70,000 ops/sec         ~14μs
Ed25519 Verify          ~30,000 ops/sec         ~33μs
JSON Serialize          ~500,000 ops/sec        ~2μs
JSON Deserialize        ~300,000 ops/sec        ~3μs
JWT Encode              ~200,000 ops/sec        ~5μs
JWT Decode              ~200,000 ops/sec        ~5μs
─────────────────────────────────────────────────────────────────
End-to-end Sign         ~60,000 ops/sec         ~17μs
End-to-end Verify       ~25,000 ops/sec         ~40μs
(hot key cache, single thread)
```

### 6.2 Network Performance

- **Key publication**: One Kafka produce per keypair generation. Negligible bandwidth.
- **Key consumption**: Kafka consumer throughput is not a bottleneck.
- **Token transport**: Zero network overhead added by Veridot to the application transport layer. Tokens are strings; transport is application-layer.

### 6.3 Token Size Analysis

```
Component               Typical Size
─────────────────────────────────────────────────────────────────
JWT Header (b64url)     ~60 bytes    {"alg":"EdDSA","kid":"<uuid>"}
JWT Payload (b64url)    ~100-500 bytes (varies with data size)
Ed25519 Signature (b64) ~88 bytes    (64 bytes raw → 88 base64url)
JWT Separators          2 bytes      "." separators
─────────────────────────────────────────────────────────────────
Minimum Token Size      ~250 bytes   (empty payload)
Typical Token Size      ~350-700 bytes
```

Compared to alternatives: smaller than most X.509-based approaches, comparable to JWS, larger than binary formats like CBOR/COSE.

### 6.4 Performance Score

| Dimension | Score (0-10) | Justification |
|-----------|--------------|---------------|
| Signing Throughput | 8 | Ed25519 is fast; JSON overhead is acceptable |
| Verification Throughput | 8 | Local verification; no network calls |
| Token Size Efficiency | 7 | JWT text format; COSE would be more compact |
| Cold Start Performance | 4 | Full topic replay penalty |
| Tail Latency | 5 | Unknown kid causes unbounded wait |
| Memory Efficiency | 6 | Growing keystore |
| Overall Performance | 7/10 | Excellent steady-state; cold-start issues |

---

## 7. State-of-the-Art Comparison

### 7.1 Message Integrity and Signing

| Feature | Veridot | JOSE/JWS | COSE | Sigstore/Rekor | DSSE | in-toto | Minisign |
|---------|---------|----------|------|----------------|------|---------|----------|
| Algorithm | Ed25519 | Multiple | Multiple | Ed25519/ECDSA | Multiple | Multiple | Ed25519 |
| Token Format | JWT | JWT/JWS | CBOR | Bundled | Envelope | Link/Layout | Detached |
| Key Distribution | Broker (Kafka) | Manual/PKI | Manual/PKI | Transparency Log | Manual | Manual | Manual |
| Revocation | None | CRL/OCSP | None | Log-based | None | None | None |
| Replay Protection | Expiration | Expiration | Expiration | N/A | None | None | None |
| Distributed Verification | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Transparency Log | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ |
| Offline Verification | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Key Attestation | ❌ | Via PKI | Via PKI | ✅ (Fulcio) | Via PKI | Via PKI | ❌ |
| Binary Efficiency | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ✅ |

**Veridot Unique Value**: The combination of broker-mediated key distribution with JWT signing enables **distributed, offline-capable verification without per-token network calls** — a property that none of the above achieve without additional infrastructure.

### 7.2 Distributed Systems Trust Mechanisms

| Feature | Veridot | Kafka EOS | Outbox Pattern | Event Sourcing | Raft |
|---------|---------|-----------|----------------|----------------|------|
| Message Authenticity | ✅ | ❌ | ❌ | ❌ | Internal only |
| Exactly-once delivery | ❌ | ✅ | ✅ | ❌ | N/A |
| Total ordering | ❌ | Partition | ❌ | Per-stream | ✅ |
| Replay protection | Bounded | ✅ | Via idempotency | Via versioning | ✅ |
| Cross-service trust | ✅ | ❌ | ❌ | ❌ | ❌ |
| Broker independence | ✅ | ❌ | ❌ | ❌ | N/A |

### 7.3 Identity and Trust Frameworks

| Feature | Veridot | PKI/X.509 | TUF | SPIFFE/SPIRE | Certificate Transparency | TOFU |
|---------|---------|-----------|-----|--------------|--------------------------|------|
| Trust Hierarchy | ❌ | ✅ | ✅ | ✅ | N/A | ❌ |
| Key Attestation | ❌ | ✅ | ✅ | ✅ | N/A | ❌ |
| Revocation | ❌ | CRL/OCSP | Metadata | SVID rotation | N/A | ❌ |
| Decentralized | ✅ | ❌ | ❌ | ❌ | Partial | ✅ |
| Operational Complexity | Low | High | Medium | High | High | Minimal |
| Certificate Chaining | ❌ | ✅ | Delegation | ✅ | N/A | ❌ |
| Automatic Rotation | ❌ | Manual | ✅ | ✅ | N/A | N/A |

### 7.4 Cloud-Native Mutual Authentication

| Feature | Veridot | mTLS (Istio) | SPIRE | Workload Identity |
|---------|---------|--------------|-------|-------------------|
| Protocol overhead | Minimal | TLS handshake | SVID issuance | Token fetch |
| Mutual auth | ❌ (one-way) | ✅ | ✅ | ✅ |
| Service mesh integration | Custom | Native | Native | Native |
| Language independence | ✅ | ✅ | ✅ | ✅ |
| Deployment complexity | Low | High | High | Medium |

### 7.5 Comparison Matrix Summary

Veridot occupies a unique niche: **lightweight, broker-native, distributed authenticity verification** without the complexity of full PKI or service mesh. Its primary competitors in this space would be JWT with centralized JWKS endpoint (OIDC-style), but Veridot's broker-native key distribution is superior for event-driven architectures.

**Veridot Strengths vs. Competitors**:
1. Zero per-verification network calls (vs. OIDC JWKS, OCSP)
2. Broker-native operation (no separate key server)
3. Simple API surface (sign/verify)
4. Ed25519 (better than RSA/ECDSA P-256 in most JOSE implementations)

**Veridot Weaknesses vs. Competitors**:
1. No trust hierarchy (vs. PKI, TUF, SPIFFE)
2. No revocation (vs. PKI, SPIFFE)
3. No key attestation (vs. Sigstore Fulcio, SPIFFE)
4. No replay prevention beyond time-bounding (vs. OAuth PKCE, Kerberos)
5. No transparency log (vs. Sigstore, CT)

---

## 8. Structural Gap Analysis

### 8.1 Gap 1: Absent Key Authentication

**Description**: Public keys published to the broker carry no proof that the publishing entity owns the corresponding private key, nor any proof of the publisher's identity.

**Root Cause**: The protocol treats the broker as a trusted key directory without any out-of-band trust anchoring.

**Impact**: Any entity with broker write access can publish arbitrary keys, enabling impersonation.

**Affected Guarantees**: Authenticity, Non-repudiation, Trust Model

**Severity**: Critical

### 8.2 Gap 2: No Key Revocation

**Description**: Once published, a key cannot be invalidated within the protocol.

**Root Cause**: The key topic is append-only compacted log; there is no protocol-level mechanism to mark a key as revoked.

**Impact**: Compromised private keys remain usable indefinitely (bounded only by token expiration, which is under the attacker's control if they have the key).

**Affected Guarantees**: Compromise Containment, Security Posture

**Severity**: Critical

### 8.3 Gap 3: No Replay Protection Within Validity Window

**Description**: Tokens may be submitted to verifiers multiple times within their validity window.

**Root Cause**: The protocol provides no nonce, sequence number, or use-count mechanism.

**Impact**: Duplicate transaction processing, double-spend scenarios in financial contexts, command replay in actuator systems.

**Affected Guarantees**: Idempotency, Freshness

**Severity**: High (context-dependent)

### 8.4 Gap 4: Unbounded KeyStore Growth

**Description**: The verifier's key store grows without bound as new keys are published. Old keys are never evicted.

**Root Cause**: No key expiration, no TTL, no garbage collection mechanism in the protocol.

**Impact**: Memory exhaustion in long-lived deployments with frequent key rotation.

**Affected Guarantees**: Scalability, Operational Sustainability

**Severity**: Medium

### 8.5 Gap 5: No Signer Identity Binding

**Description**: A `kid` (UUID) has no semantic binding to a signer identity. A verifier cannot determine which application, service, or organization produced a signed token without out-of-band context.

**Root Cause**: The protocol uses UUIDs as opaque identifiers without identity metadata.

**Impact**: Cannot implement per-signer policies, cannot audit signing by identity, cannot implement authorization based on signer identity.

**Affected Guarantees**: Auditability, Authorization, Non-repudiation

**Severity**: High

### 8.6 Gap 6: No Algorithm Agility

**Description**: The protocol is implicitly tied to Ed25519 and JWT. There is no negotiation or versioning mechanism for cryptographic algorithm upgrades.

**Root Cause**: No version field in the key publication or token format.

**Impact**: Quantum computing threat cannot be addressed without breaking changes; no path to post-quantum algorithms.

**Affected Guarantees**: Long-term Security, Future-proofing

**Severity**: Medium (long-term)

### 8.7 Gap 7: Clock Dependency Without Synchronization Protocol

**Description**: Token validity relies on wall-clock comparison between signer and verifier, with no protocol-level clock synchronization or skew tolerance.

**Root Cause**: Direct use of `exp` without clock skew tolerance parameter.

**Impact**: In distributed systems with clock skew, tokens may be rejected as expired even when the signer intended them to be valid, or accepted after intended expiration.

**Affected Guarantees**: Availability, Correctness

**Severity**: Medium

### 8.8 Gap 8: No Payload Schema Versioning

**Description**: Payload data is embedded as opaque JSON without schema versioning, making backward-compatible payload evolution difficult.

**Root Cause**: Protocol treats payload as an opaque object.

**Impact**: Desertialization failures when payload schema evolves; no graceful handling of unknown fields.

**Affected Guarantees**: Interoperability, Maintainability

**Severity**: Low

### 8.9 Structural Gap Summary Table

| Gap | Description | Severity | Affected Guarantees |
|-----|-------------|----------|---------------------|
| G1 | No key authentication | Critical | Authenticity, Trust |
| G2 | No key revocation | Critical | Compromise containment |
| G3 | No replay protection | High | Idempotency, Freshness |
| G4 | Unbounded keystore | Medium | Scalability |
| G5 | No signer identity | High | Auditability, Non-repudiation |
| G6 | No algorithm agility | Medium | Long-term security |
| G7 | No clock sync protocol | Medium | Availability, Correctness |
| G8 | No payload schema version | Low | Interoperability |

---

## 9. Protocol Evolution Proposals

### 9.1 PEP-01: Authenticated Key Publication (Addresses G1)

**Description**: Introduce a **self-signed key publication** mechanism. When a Signer publishes a public key, the publication message itself is signed by the corresponding private key. This proves that the publisher holds the private key at time of publication.

**Mechanism**:
```
KeyPublication_v2 {
    kid:        UUID
    pubkey:     base64(ed25519_public_key)
    timestamp:  unix_seconds
    proof:      base64(Ed25519Sign(privKey, kid || pubkey || timestamp))
}
```

**Verification of Key Publication**:
```
FUNCTION VerifyKeyPublication(pub):
    msg = pub.kid || pub.pubkey || pub.timestamp
    return Ed25519Verify(base64Decode(pub.pubkey), msg, base64Decode(pub.proof))
```

**Why Superior**: This eliminates the broker injection attack (T1) without requiring external PKI. The private key holder proves ownership at publication time.

**Comparison to State of Art**: Similar to TOFU with proof-of-possession; simpler than full certificate chain but provides key ownership proof.

**Trade-offs**:
- Additional signature per key publication (negligible overhead)
- Does not solve signer identity (still need G5 solution)
- Verifiers must validate publication proofs (minor complexity increase)

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 8 | Eliminates broker injection; doesn't solve identity |
| Implementation Complexity | 3 | Small change to key publication format |
| Backward Compatibility | 7 | Additive field; old verifiers ignore proof |
| Research Novelty | 4 | Known pattern; proof-of-possession is established |

---

### 9.2 PEP-02: Soft and Hard Key Revocation (Addresses G2)

**Description**: Introduce a **revocation publication** mechanism using a separate revocation topic. A Signer can publish a signed revocation notice for any of its own `kid` values. Verifiers subscribe to the revocation topic and maintain a revocation set.

**Hard Revocation** (immediate):
```
RevocationNotice {
    kid:         UUID
    reason:      COMPROMISED | SUPERSEDED | EXPIRED_POLICY
    revoked_at:  unix_seconds
    proof:       base64(Ed25519Sign(new_privKey_OR_master_key, kid || revoked_at))
}
```

**Soft Revocation** (key expiration):
```
KeyPublication_v2 {
    ...
    key_expires_at: unix_seconds  // key itself expires, not individual tokens
}
```

**Verifier Behavior**:
1. Check revocation set before accepting any token
2. If `kid` is in revocation set, reject with `KeyRevokedException`
3. If `key_expires_at` is set and `currentTime > key_expires_at`, reject

**Problem**: Proof of revocation is complex. The Signer whose key was compromised may not be able to sign the revocation with the compromised key. Resolution: introduce a **separate master revocation key** per signer, published alongside the signing key.

**Why Superior**: Provides compromise containment, currently absent in the protocol. Superior to CRL (no central authority needed) and comparable to key expiry in SPIFFE (automatic rotation with TTL).

**Trade-offs**:
- Introduces master key concept (complexity)
- Verifiers must subscribe to additional topic
- Revocation propagation has latency (broker-dependent)

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 9 | Addresses critical compromise containment gap |
| Implementation Complexity | 6 | Master key concept; additional topic |
| Backward Compatibility | 6 | Non-breaking addition; new fields optional |
| Research Novelty | 5 | CRL/OCSP adaptation for broker-native context |

---

### 9.3 PEP-03: Context-Bound Replay Prevention (Addresses G3)

**Description**: Introduce **context binding** to tokens, making each token valid only for a specific (verifier, operation, nonce) triple. This is a protocol-level mechanism, not application-level.

**Mechanism**:
```
TokenRequest {
    context_id:  UUID        // verifier-generated per-request nonce
    operation:   string      // operation identifier
    expires_in:  seconds     // requested token duration
}

Token_v2 (JWT payload addition) {
    "data":       ...,
    "exp":        ...,
    "iat":        ...,
    "ctx":        "<context_id>",    // optional field
    "ops":        "<operation>"      // optional field
}
```

**Verification**:
- If `ctx` is present in token, verifier MUST match it against the expected context
- Once a `ctx` is successfully verified, it MUST be added to a short-lived used-context set
- Used contexts are garbage-collected after `exp` of the associated token

**Two Modes**:
1. **Context-free mode** (backward compatible): No `ctx` field; time-bounded only
2. **Context-bound mode**: `ctx` present; strict single-use within validity window

**Why Superior**: Enables true replay prevention without requiring per-token network calls. The nonce is carried in the token itself; the used-context set is local to each verifier. Superior to OAuth PKCE (no redirect needed) and Kerberos tickets (no KDC needed).

**Trade-offs**:
- Requires request-response protocol for nonce exchange (adds latency if context_id must be pre-generated)
- Used-context set consumes memory (bounded by active token count)
- Incompatible with broadcast token scenarios

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 9 | True replay prevention without network calls |
| Implementation Complexity | 5 | Used-context set management; GC required |
| Backward Compatibility | 9 | Optional field; backward compatible |
| Research Novelty | 6 | Nonce-in-token is known; broker-native variant is novel |

---

### 9.4 PEP-04: Signer Identity Manifest (Addresses G5)

**Description**: Introduce a **Signer Identity Manifest** — a structured metadata document published alongside the public key, describing the signer's identity, capabilities, and policy.

**Manifest Format**:
```json
{
    "kid":          "<uuid>",
    "identity": {
        "service":   "payment-processor",
        "tenant":    "acme-corp",
        "region":    "us-east-1",
        "version":   "2.3.1"
    },
    "capabilities": ["sign:payment", "sign:receipt"],
    "policy": {
        "max_duration_seconds": 3600,
        "allowed_operations":   ["process", "refund"]
    },
    "published_at": 1234567890,
    "proof":        "<self-signed proof from PEP-01>"
}
```

**Verifier Behavior**:
1. Verifiers can optionally enforce capability checks: a token for operation `X` must come from a signer with capability `sign:X`
2. Policy enforcement: max_duration_seconds limits accepted token lifetimes
3. Identity-based audit logging

**Why Superior**: Transforms Veridot from a pure authenticity protocol to an **authenticated authorization** protocol. No external IdP required. Comparable to SPIFFE SVID but without requiring a SPIRE control plane.

**Trade-offs**:
- Manifest is self-asserted (not attested by external authority in base case)
- Policy enforcement is verifier-local (no centralized policy engine)
- Additional complexity for manifest management

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 7 | Enables policy enforcement; self-asserted identity |
| Implementation Complexity | 5 | Manifest schema + verifier policy engine |
| Backward Compatibility | 8 | Additive; old verifiers ignore manifest |
| Research Novelty | 7 | Novel combination of broker-native identity + capabilities |

---

### 9.5 PEP-05: Key Epoch and Bounded KeyStore (Addresses G4)

**Description**: Introduce **key epochs** to bound KeyStore memory growth. Each key is assigned an epoch number. Verifiers may discard keys from epochs older than a configurable horizon, provided no tokens from those epochs are expected to be valid.

**Mechanism**:
```
KeyPublication_v2 {
    ...
    epoch:       uint64    // monotonically increasing per signer
    epoch_start: unix_seconds
    epoch_end:   unix_seconds   // when this key will be rotated
}
```

**Garbage Collection Rule**:
- A key may be evicted from a verifier's KeyStore when `epoch_end + max_token_duration < currentTime`
- This guarantees that no valid token can reference an evicted key

**Why Superior**: Provides bounded memory growth without requiring protocol-breaking changes. Comparable to certificate expiration in PKI but adapted for broker-native key distribution.

**Trade-offs**:
- Epoch management adds coordination complexity
- Early eviction causes late-arriving token rejections if the token was issued near epoch boundary

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Scalability | 9 | Bounded memory growth |
| Implementation Complexity | 5 | Epoch tracking; GC logic |
| Backward Compatibility | 7 | Additive fields; old verifiers keep all keys |
| Research Novelty | 4 | Certificate expiry adapted for broker context |

---

### 9.6 PEP-06: Algorithm Agility and Version Negotiation (Addresses G6)

**Description**: Introduce a **protocol version field** and **algorithm suite negotiation** in key publications.

**Key Publication with Algorithm Suite**:
```json
{
    "veridot_version": "2",
    "kid":  "<uuid>",
    "alg":  "EdDSA-Ed25519",           // current
    "pubkey": "...",
    "alt_pubkey": {                      // post-quantum alternative
        "alg": "CRYSTALS-Dilithium3",
        "pubkey": "..."
    }
}
```

**Token Version Header**:
```json
{
    "alg": "EdDSA",
    "kid": "<uuid>",
    "ver": "2"
}
```

**Post-Quantum Transition Path**:
1. Signers publish both Ed25519 and Dilithium keys
2. Tokens carry dual signatures during transition period
3. Verifiers accept either signature during hybrid mode
4. After transition: Ed25519 key publication deprecated

**Why Superior**: Enables quantum-resistant upgrade path without flag-day cutover. Comparable to TLS hybrid key exchange during post-quantum transition, but adapted for signing.

**Trade-offs**:
- Dual signatures increase token size during transition
- Complexity of managing multiple algorithm suites
- Verifier must implement multiple signature algorithms

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security (long-term) | 9 | Quantum-safe upgrade path |
| Implementation Complexity | 7 | Multiple algorithms; hybrid mode |
| Backward Compatibility | 6 | Version negotiation required |
| Research Novelty | 6 | Hybrid PQ transition is active research area |

---

### 9.7 PEP-07: Merkle Commitment for Audit Trail (Novel)

**Description**: Introduce a **Merkle commitment** mechanism allowing verifiers to prove to a third party that a specific token was valid at a specific time without revealing the full token contents.

**Mechanism**:
1. Verifiers maintain a **local Merkle tree** of successfully verified tokens (rolling window)
2. The Merkle root is periodically published to a shared audit topic
3. To prove verification without revealing payload: present (token, Merkle path, published root)
4. Third party can verify: (a) token signature, (b) inclusion in root, (c) root was published at claimed time

**Merkle Node Structure**:
```
MerkleNode {
    token_hash:    SHA256(token)
    verified_at:   unix_ms
    verifier_id:   UUID
}
```

**Why Superior**: Enables **verifiable audit trails** without a central transparency log. This is a novel combination of verifier-local Merkle accumulation with broker-published root commitments. Unlike Sigstore/Rekor (which requires a server), this is fully distributed.

**Comparison to Sigstore**: Sigstore requires a central Rekor server. This proposal distributes the audit function across all verifiers using the same broker infrastructure already present in Veridot.

**Trade-offs**:
- Additional CPU and memory for Merkle tree maintenance
- Audit topics add broker load
- Proof verification is complex for third parties

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 8 | Tamper-evident audit trail |
| Auditability | 10 | Cryptographic audit proof |
| Implementation Complexity | 8 | Merkle tree management; root publication |
| Research Novelty | 9 | Distributed verifier audit without central log |

---

### 9.8 PEP-08: Federated Trust Anchors (Novel)

**Description**: Introduce **federated trust anchor chains** enabling cross-organizational trust without a global PKI. Organizations publish **Trust Anchor Certificates** (TACs) — self-signed documents authorizing specific signing key ranges — to a federated discovery network.

**Trust Anchor Certificate**:
```json
{
    "tac_id":       "org-acme-001",
    "organization": "acme-corp",
    "key_pattern":  "urn:acme:*",        // namespace pattern for kid values
    "tac_pubkey":   "...",               // TAC signing key
    "valid_from":   1234567890,
    "valid_until":  1266103890,
    "tac_signature": "..."               // self-signed
}
```

**Signer Key Publication with TAC**:
```
KeyPublication_v3 {
    kid:       "urn:acme:payment:svc-01:2025",   // namespaced kid
    pubkey:    "...",
    tac_id:    "org-acme-001",
    tac_proof: Ed25519Sign(tac_privKey, kid || pubkey)
}
```

**Verifier Trust Resolution**:
1. Maintain a federated TAC registry (separate topic or well-known endpoints)
2. For each token, resolve signer's TAC from `kid` namespace
3. Verify TAC signature on key publication
4. Apply TAC-defined policy

**Why Superior**: Enables **cross-organizational trust** without requiring a global CA. Comparable to web of trust (PGP) but with namespace-scoped delegation and expiring trust anchors. More structured than PGP, less centralized than X.509.

**Trade-offs**:
- TAC registry requires additional infrastructure
- Trust resolution adds latency (first-time per TAC)
- Namespace collision possible without governance

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 8 | Org-controlled trust; expiring TACs |
| Scalability | 7 | TAC registry can become bottleneck |
| Research Novelty | 9 | Novel federated trust for broker-native protocols |
| Implementation Complexity | 8 | TAC registry; namespace resolution |

---

### 9.9 PEP-09: Adaptive Signing (Context-Sensitive Key Selection)

**Description**: Allow Signers to automatically select the appropriate keypair (and associated identity manifest) based on the data payload's context, using a **signing policy engine**.

**Signing Policy**:
```json
{
    "policies": [
        {
            "match":  {"payload_type": "PaymentRequest"},
            "kid":    "urn:acme:payment:primary",
            "max_duration": 60
        },
        {
            "match":  {"payload_type": "*"},
            "kid":    "urn:acme:general:primary",
            "max_duration": 3600
        }
    ]
}
```

**API Extension**:
```java
public interface DataSigner {
    <T> String sign(T data, Duration duration);           // existing
    <T> String sign(T data, SigningContext context);      // new: policy-driven
}
```

**Why Superior**: Enables **least-privilege signing** — different capabilities for different payload types — without application code changes. Superior to manual key selection in complex microservice deployments.

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 8 | Least-privilege signing |
| Implementation Complexity | 5 | Policy engine; key dispatch |
| Backward Compatibility | 10 | Additive API method |
| Research Novelty | 5 | Policy-driven signing is known; broker-native variant novel |

---

## 10. Advanced Extensions

### 10.1 Extension: Distributed Key Consensus (DKC)

**Problem**: In scenarios with multiple signers sharing a logical identity (e.g., replicated signer service), each instance generates independent keypairs, leading to multiple `kid` values for what is semantically one service identity.

**Proposed Solution**: **Distributed Key Consensus** using a variation of the RAFT protocol specialized for key publication.

**Mechanism**:
- Signer replicas participate in a DKC group
- Each group has one **active signer** (leader) at any time
- Only the leader publishes keys and signs tokens
- On leader failure, a new leader is elected; it rotates the key and publishes the new public key
- Verifiers see a smooth transition: old tokens valid until `exp`, new tokens from new leader

**Innovation**: This combines RAFT-style leader election with Veridot's key distribution, providing **single logical identity** across distributed signer replicas without requiring external coordination services.

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Reliability | 9 | Eliminates single-signer SPOF |
| Implementation Complexity | 9 | RAFT implementation; leader election |
| Research Novelty | 8 | RAFT + cryptographic signing orchestration |

---

### 10.2 Extension: Threshold Signing

**Problem**: High-value tokens (e.g., financial authorizations) should require multiple independent signers.

**Proposed Solution**: **Threshold Signature Scheme** (t-of-n) based on Ed25519 threshold signatures (e.g., FROST protocol).

**Mechanism**:
- Group of n Signers participate in key generation (Distributed Key Generation - DKG)
- Any t-of-n Signers can co-sign a token
- The aggregate signature is standard Ed25519 (verifiers don't need to know it was threshold-signed)
- Optional: include `tss_participants` claim in JWT payload for audit

**Why Novel in Context**: Adapts FROST threshold signatures to Veridot's broker-native signing model. Verifiers require no modification (standard Ed25519 verification). The threshold property is transparent to the verification path.

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 10 | Eliminates single key compromise risk |
| Implementation Complexity | 10 | DKG + FROST; extremely complex |
| Research Novelty | 9 | FROST in broker-native context |
| Backward Compatibility | 9 | Transparent to verifiers |

---

### 10.3 Extension: Zero-Knowledge Token Attributes (ZKTA)

**Problem**: A token must reveal the full payload to the verifier. In privacy-sensitive scenarios, a prover may want to demonstrate that payload has certain properties without revealing the payload.

**Proposed Solution**: **Zero-Knowledge Token Attributes** — extend the token format to include ZK proofs of payload predicates.

**Mechanism**:
```json
{
    "data":    "<encrypted-or-committed-payload>",
    "exp":     ...,
    "iat":     ...,
    "zk_proofs": [
        {
            "predicate": "amount > 0 AND amount < 10000",
            "proof":     "<zkSNARK proof>",
            "public_inputs": "..."
        }
    ]
}
```

**Use Cases**:
- Prove age without revealing birthdate
- Prove transaction amount is within range without revealing exact amount
- Prove membership without revealing identity

**Why Novel**: Combines Veridot's signing infrastructure with Groth16 or PLONK zkSNARKs. The Signer generates both the Ed25519 signature and the ZK proof. Verifiers can verify both the signature (data integrity) and the ZK proof (predicate validity).

**Trade-offs**:
- ZK proof generation is computationally expensive (100ms-10s depending on predicate)
- Proof size adds significant token overhead (100s of KB for complex predicates)
- Circuit design per predicate type is a specialized skill

**Scores**:
| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security/Privacy | 10 | Selective disclosure with cryptographic guarantees |
| Implementation Complexity | 10 | zkSNARK circuit design; specialized expertise |
| Research Novelty | 10 | Genuinely novel in broker-native signing context |
| Performance | 3 | ZK proof generation is slow |

---

## 11. Veridot 2.0 Architecture

### 11.1 Design Principles

Veridot 2.0 preserves the core philosophy:
1. **Broker-native**: Key distribution through the message broker already present in most distributed systems
2. **Local verification**: Zero per-verification network calls
3. **Simple API**: sign/verify remains the primary interface
4. **Language-agnostic**: Protocol defined in terms of serialization formats, not language constructs

New principles:
5. **Least-privilege by default**: Identity manifests and capability-based signing
6. **Composable trust**: Federated trust anchors without global PKI
7. **Quantum-resistant path**: Algorithm agility built in from day one
8. **Verifiable audit**: Distributed Merkle audit without central log
9. **Bounded resources**: Key epochs prevent unbounded memory growth

### 11.2 Veridot 2.0 Protocol Layers

```
┌────────────────────────────────────────────────────────────────────────┐
│                         APPLICATION LAYER                               │
│           DataSigner.sign()  │  DataVerifier.verify()                  │
├────────────────────────────────────────────────────────────────────────┤
│                         POLICY LAYER (NEW)                              │
│    Capability Enforcement │ Duration Limits │ Operation Matching        │
├────────────────────────────────────────────────────────────────────────┤
│                         TRUST LAYER (NEW)                               │
│    TAC Resolution │ Key Attestation │ Federated Trust │ Revocation      │
├────────────────────────────────────────────────────────────────────────┤
│                         TOKEN LAYER                                     │
│    JWT v2 │ Algorithm Agility │ Context Binding │ ZK Extension         │
├────────────────────────────────────────────────────────────────────────┤
│                         CRYPTO LAYER                                   │
│    Ed25519 │ Dilithium (PQ) │ Threshold Signing │ ZKTA                │
├────────────────────────────────────────────────────────────────────────┤
│                         KEY MANAGEMENT LAYER                            │
│    Key Publication │ Revocation │ Epoch Management │ DKC               │
├────────────────────────────────────────────────────────────────────────┤
│                         BROKER LAYER                                    │
│    Key Topic │ Revocation Topic │ TAC Topic │ Audit Topic              │
├────────────────────────────────────────────────────────────────────────┤
│                         TRANSPORT LAYER                                 │
│    Kafka Adapter │ Disruptor Adapter │ Custom Adapters                 │
└────────────────────────────────────────────────────────────────────────┘
```

### 11.3 Veridot 2.0 Token Format

**JWT Header v2**:
```json
{
    "alg":  "EdDSA",
    "kid":  "urn:acme:payment:svc-01:2025",
    "ver":  "2",
    "typ":  "VDT+JWT"
}
```

**JWT Payload v2**:
```json
{
    "data":    "<json-payload>",
    "exp":     1234567890,
    "iat":     1234567000,
    "nbf":     1234567000,
    "ctx":     "optional-context-uuid",
    "ops":     "optional-operation-id",
    "cap":     ["payment:process"],
    "epoch":   42,
    "schema":  "1.2.0"
}
```

**JWT Signature v2**: Dual signature support during algorithm transition:
```
sig1 = Ed25519Sign(privKey_ed25519, header+"."+payload)
sig2 = DilithiumSign(privKey_dilithium, header+"."+payload)
token = header+"."+payload+"."+base64(sig1)+"."+base64(sig2)
// During transition: verifiers accept either valid sig
// Post-transition: only sig2 required
```

### 11.4 Veridot 2.0 Key Publication Format

```json
{
    "veridot_version": "2",
    "kid":        "urn:acme:payment:svc-01:2025",
    "alg":        "EdDSA-Ed25519",
    "pubkey":     "<base64-ed25519-pubkey>",
    "alt_alg":    "Dilithium3",
    "alt_pubkey": "<base64-dilithium-pubkey>",
    "epoch":      42,
    "epoch_start": 1234567000,
    "epoch_end":   1266103000,
    "identity": {
        "service":   "payment-processor",
        "tenant":    "acme-corp",
        "region":    "us-east-1",
        "version":   "3.1.0"
    },
    "capabilities": ["sign:payment", "sign:receipt"],
    "policy": {
        "max_duration_seconds": 3600,
        "allowed_operations":   ["process", "refund"]
    },
    "tac_id":    "org-acme-001",
    "tac_proof": "<signature by TAC key>",
    "proof":     "<self-signed proof-of-possession>",
    "published_at": 1234567890
}
```

### 11.5 Veridot 2.0 Broker Topics

```
veridot.keys.v2          // compacted; key publications
veridot.revocation.v2    // compacted; revocation notices  
veridot.tac.v2           // compacted; trust anchor certificates
veridot.audit.v2         // append-only; Merkle root publications
```

### 11.6 Veridot 2.0 API

```java
// Core interfaces (backward compatible)
public interface DataSigner {
    <T> String sign(T data, Duration duration);          // v1 compatible
    <T> String sign(T data, SigningContext context);      // v2: context-bound
    <T> String sign(T data, SigningPolicy policy);        // v2: policy-driven
    void rotate();                                        // v1 compatible
    void revokeKey(String kid, RevocationReason reason); // v2: revocation
}

public interface DataVerifier {
    <T> T verify(String token, Class<T> type);                    // v1 compatible
    <T> T verify(String token, Class<T> type, VerifyContext ctx); // v2: context check
    AuditProof getAuditProof(String token);                       // v2: Merkle proof
}

// New v2 interfaces
public interface TrustAnchorRegistry {
    void registerTAC(TrustAnchorCertificate tac);
    Optional<TrustAnchorCertificate> resolveTAC(String kid);
}

public interface RevocationManager {
    void publishRevocation(RevocationNotice notice);
    boolean isRevoked(String kid);
}
```

### 11.7 Veridot 2.0 Trust Model

```
                    ┌─────────────────────────────────┐
                    │   TRUST ANCHOR CERTIFICATES      │
                    │   (Org-controlled, expiring)     │
                    └────────────────┬────────────────┘
                                     │ authorizes
                    ┌────────────────▼────────────────┐
                    │     SIGNER KEY PUBLICATIONS      │
                    │   (Self-proved + TAC-attested)   │
                    └────────────────┬────────────────┘
                                     │ authenticates
                    ┌────────────────▼────────────────┐
                    │           TOKENS                 │
                    │   (Ed25519/PQ signed, bounded)   │
                    └────────────────┬────────────────┘
                                     │ verified by
                    ┌────────────────▼────────────────┐
                    │    VERIFIER POLICY ENGINE        │
                    │   (Capability + Duration check)  │
                    └─────────────────────────────────┘
```

### 11.8 Veridot 2.0 Security Properties

| Property | v1 | v2 |
|----------|----|----|
| Key authenticity | None | Proof-of-possession + TAC |
| Key revocation | None | Soft + Hard revocation |
| Replay protection | Time-bounded | Context-bound + Time-bounded |
| Signer identity | None | Identity manifest + capability |
| Algorithm agility | None | Hybrid PQ transition |
| Audit trail | None | Distributed Merkle audit |
| KeyStore bounds | Unbounded | Epoch-bounded |
| Forward secrecy | None | Epoch rotation (partial) |
| Cross-org trust | None | Federated TAC |

### 11.9 Veridot 2.0 Scoring

| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 8 | Strong improvements; no formal verification yet |
| Performance | 8 | Marginal overhead from additional fields; local verification preserved |
| Scalability | 9 | Epoch-bounded keystore; horizontal scale preserved |
| Reliability | 8 | DKC for signer HA; revocation propagation lag |
| Resilience | 8 | Broker failure tolerance improved |
| Simplicity | 6 | Increased complexity vs. v1; policy layer adds cognitive load |
| Auditability | 9 | Distributed Merkle audit; identity manifests |
| Testability | 7 | More components to test; but well-defined interfaces |
| Maintainability | 7 | More moving parts; clear versioning |
| Operational Complexity | 6 | Additional topics; TAC management |
| Implementation Complexity | 7 | Significant new code; but modular |
| Backward Compatibility | 8 | v1 tokens remain valid; v2 is additive |
| Research Novelty | 9 | Novel combination of mechanisms |
| Standardization Potential | 8 | Clean enough for standards track |

---

## 12. Migration Roadmap

### 12.1 Phase 0: Stabilize v1 (Months 1-3)

**Goals**: Harden the existing protocol before introducing new features.

1. **Enforce algorithm validation**: Ensure verifiers strictly require `alg: EdDSA`; reject `none`, symmetric algorithms.
2. **Add proof-of-possession to key publications** (PEP-01): Low complexity, high security impact. Backward compatible.
3. **Add clock skew tolerance**: Add configurable `leeway` parameter (default: 30 seconds) for `exp` checks.
4. **Fix deserialization security**: Review and harden Jackson configuration to prevent unsafe deserialization.
5. **Add structured logging**: Implement structured audit logging for all sign and verify operations.

### 12.2 Phase 1: Key Management Foundation (Months 4-8)

1. **Implement key revocation** (PEP-02): Add revocation topic; implement RevocationManager; update Verifier to check revocation.
2. **Implement key epochs** (PEP-05): Add `epoch`, `epoch_start`, `epoch_end` to KeyPublication; implement GC in Verifier.
3. **Implement signer identity manifest** (PEP-04): Add `identity` and `capabilities` to KeyPublication; implement capability checking.
4. **Add namespaced kid format**: Migrate from raw UUID to `urn:<org>:<service>:<instance>:<year>` format.

### 12.3 Phase 2: Trust Model Extension (Months 9-14)

1. **Implement Trust Anchor Certificates** (PEP-08): TAC topic, TAC resolution, trust chain validation.
2. **Implement context binding** (PEP-03): Add `ctx` and `ops` JWT claims; implement used-context GC in Verifier.
3. **Implement distributed Merkle audit** (PEP-07): Verifier Merkle tree; audit topic publication.
4. **Implement signing policy engine** (PEP-09): Policy-driven key selection; adaptive signing API.

### 12.4 Phase 3: Cryptographic Evolution (Months 15-24)

1. **Implement algorithm agility** (PEP-06): Version field; dual-signature transition support.
2. **Implement post-quantum hybrid**: Add Dilithium3 as secondary signature algorithm; hybrid mode.
3. **Implement threshold signing** (Advanced 10.2): FROST-based t-of-n for high-value tokens.
4. **Implement ZKTA** (Advanced 10.3): ZK proof extension for privacy-preserving attribute disclosure.

### 12.5 Migration Compatibility Matrix

| Feature | v1 Signer | v1 Verifier | v2 Signer | v2 Verifier |
|---------|-----------|-------------|-----------|-------------|
| v1 tokens | ✅ | ✅ | ✅ | ✅ |
| v2 tokens | N/A | ❌ (new claims ignored) | ✅ | ✅ |
| PoP key pub | N/A | ✅ (ignored) | ✅ | ✅ required |
| Revocation | N/A | ❌ | ✅ | ✅ |
| TAC | N/A | ❌ | ✅ | ✅ optional |
| Context bind | N/A | ❌ | ✅ | ✅ optional |
| PQ dual sig | N/A | ❌ | ✅ | ✅ optional |

---

## 13. Prioritized Implementation Roadmap

### Priority 1 (Critical Security — Immediate)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 1.1 | Enforce `alg: EdDSA` in verifier | 1 day | Critical security fix |
| 1.2 | Reject JWT `alg: none` | 1 day | Critical security fix |
| 1.3 | Add proof-of-possession to key publication | 3 days | High security improvement |
| 1.4 | Harden Jackson deserialization configuration | 2 days | Critical for RCE prevention |
| 1.5 | Add structured security audit logging | 3 days | Compliance and observability |

### Priority 2 (High Impact — Q1)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 2.1 | Key revocation mechanism | 2 weeks | Critical gap closure |
| 2.2 | Key epoch and KeyStore GC | 1 week | Memory leak prevention |
| 2.3 | Clock skew tolerance parameter | 2 days | Correctness improvement |
| 2.4 | Signer identity manifest | 1 week | Auditability and authorization |
| 2.5 | Protocol version field | 3 days | Future compatibility |

### Priority 3 (Important — Q2)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 3.1 | Context-bound replay prevention | 2 weeks | Replay protection |
| 3.2 | Namespaced kid format | 1 week | Identity semantics |
| 3.3 | Signing policy engine | 2 weeks | Least-privilege signing |
| 3.4 | Integration test suite expansion | 3 weeks | Reliability |
| 3.5 | Prometheus metrics export | 1 week | Observability |

### Priority 4 (Strategic — Q3-Q4)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 4.1 | Trust Anchor Certificate framework | 1 month | Cross-org trust |
| 4.2 | Distributed Merkle audit | 3 weeks | Verifiable audit trail |
| 4.3 | Algorithm agility framework | 3 weeks | PQ transition readiness |
| 4.4 | SPIFFE/SVID integration adapter | 2 weeks | Cloud-native interop |
| 4.5 | Multi-language SDK (Go, Python, Rust) | 2-3 months | Adoption |

### Priority 5 (Research — Year 2+)

| # | Item | Effort | Impact |
|---|------|--------|--------|
| 5.1 | Distributed Key Consensus (DKC) | 2-3 months | HA signing |
| 5.2 | Threshold signing (FROST) | 3-4 months | High-value token security |
| 5.3 | Post-quantum hybrid (Dilithium) | 2 months | Quantum resistance |
| 5.4 | Zero-knowledge token attributes | 4-6 months | Privacy-preserving auth |
| 5.5 | Formal verification (TLA+/ProVerif) | 3-4 months | Security assurance |

---

## 14. Standardization Roadmap

### 14.1 Standardization Strategy

Veridot occupies a gap in the existing standards landscape: no current standard addresses **broker-native, offline-verifiable, distributed signing** as a first-class design goal. The standardization path should proceed incrementally.

### 14.2 Phase 1: Community Standards (Year 1)

1. **Publish formal specification** as a versioned open document (not in code comments)
2. **Register JWT extension claims** with IANA:
   - `vdot_ctx`: Context binding identifier
   - `vdot_ops`: Operation identifier
   - `vdot_cap`: Capability claims
   - `vdot_epoch`: Key epoch
   - `vdot_schema`: Payload schema version
3. **Register media type**: `application/veridot-token` (or `application/vdt+jwt`)
4. **Publish test vectors**: Authoritative test vectors for signature generation and verification

### 14.3 Phase 2: IETF Exploration (Year 2)

1. **Draft IETF Internet-Draft**: Submit as `draft-veridot-distributed-signing-00`
2. **Target Working Groups**:
   - OAUTH WG: Token profile for distributed systems
   - JOSE WG: JWT extension for broker-native key distribution
   - RATS WG: Key attestation integration
3. **Workshop presentation**: IETF SAAG or distributed systems workshop

### 14.4 Phase 3: Formal Standards Track (Year 3+)

1. **IETF RFC**: Following successful Internet-Draft adoption
2. **NIST consideration**: If post-quantum extensions are mature
3. **Cloud Native Computing Foundation (CNCF)**: Consider submission as a sandbox project for cloud-native workload identity extension
4. **OpenID Foundation**: Extension profile for distributed signing with OIDC identity binding

### 14.5 Standardization Prerequisites

Before standards track:
- [ ] Formal security proof (cryptographic reduction) for key publication integrity
- [ ] Independent security audit by recognized firm
- [ ] Multiple independent implementations (Java, Go, Rust minimum)
- [ ] Real-world deployment case studies (security, scale, operational)
- [ ] Formal verification (ProVerif or Tamarin Prover) of core protocol
- [ ] IANA registrations for claims and media type

---

## 15. Open Research Questions

### RQ1: Broker-Native Key Authentication Without External PKI

**Question**: Is it possible to prove key publication authenticity (not just proof-of-possession) using only the broker infrastructure, without any external trust anchor?

**Background**: Proof-of-possession (PEP-01) proves the publisher holds the private key, but not the publisher's identity. Can we derive identity from behavioral signals (e.g., the broker's authenticated connection metadata) and encode it cryptographically without a CA?

**Relevance**: Would enable zero-dependency trust establishment in environments where PKI is unavailable.

**Difficulty**: High — requires a formal model of "identity from behavioral authentication."

---

### RQ2: Optimal Key Revocation Propagation Under Broker Partitions

**Question**: What is the optimal revocation propagation protocol for broker-native key distribution under network partitions, given the CAP theorem constraints?

**Background**: Revocation requires consistency (verifiers must see the revocation), but broker availability under partitions may prevent this. If verifiers choose availability (accept tokens without seeing revocation), security is weakened. If they choose consistency, availability is reduced.

**Relevance**: Core tension in distributed revocation systems; no existing solution directly addresses broker-native context.

**Approach**: Explore gossip-based revocation propagation as an alternative to broker-dependent models.

---

### RQ3: Formal Verification of Veridot Core Protocol

**Question**: Can the Veridot core protocol (key publication → token signing → verification) be formally verified for correctness and security in ProVerif or Tamarin Prover?

**Background**: No formal verification exists. ProVerif and Tamarin can model asymmetric signing, key distribution, and attacker capabilities.

**Expected Results**: Security proofs for authenticity and integrity under the stated trust assumptions; identification of attack traces if any exist.

**Difficulty**: Medium — well-understood models exist for JWT-like protocols.

---

### RQ4: Byzantine Fault Tolerance in Distributed Key Consensus

**Question**: Can the Distributed Key Consensus mechanism (Extension 10.1) be made Byzantine fault tolerant? What is the minimum number of signer replicas required to tolerate f Byzantine replicas while maintaining signing availability?

**Background**: RAFT provides CFT (crash fault tolerance) but not BFT. If a signer replica is compromised, it may sign malicious tokens. Extending to BFT (e.g., via PBFT or HotStuff) for key rotation coordination would require 3f+1 replicas.

**Relevance**: High-security deployments where signer compromise is a realistic threat model.

---

### RQ5: Privacy-Preserving Signer Identity in Federated Trust

**Question**: Can a Signer prove membership in a Trust Anchor's namespace without revealing which specific Signer issued a token? (Linkable/unlinkable ring signatures in the broker-native context.)

**Background**: In privacy-sensitive deployments (healthcare, legal), it may be desirable to prove that a token was issued by "some authorized signer in the medical record department" without revealing which specific service signed it.

**Approach**: Ring signatures, group signatures, or BBS+ credential schemes.

**Difficulty**: High — efficient group signatures for distributed systems are an active research area.

---

### RQ6: Optimal Key Epoch Duration for Security-Memory Trade-off

**Question**: What is the optimal key epoch duration that minimizes memory consumption in Verifier KeyStores while maintaining acceptable security against key compromise propagation?

**Background**: Shorter epochs → more frequent key rotation → more keys in the broker → larger keystore (before GC) → faster compromise recovery. Longer epochs → fewer keys → smaller keystore → slower compromise recovery.

**Formalization**: Model as an optimization problem: minimize E[memory] subject to E[compromise_exposure] < threshold.

---

### RQ7: Cross-Protocol Compatibility with SPIFFE/SVID

**Question**: Can Veridot keys be bound to SPIFFE Verifiable Identity Documents (SVIDs) to enable cloud-native workload identity integration without requiring a SPIRE control plane?

**Background**: SPIFFE provides workload identity in cloud-native environments. If Veridot keys could be attested by SPIFFE SVIDs, it would enable integration with Kubernetes, Istio, and other cloud-native platforms without separate identity infrastructure.

**Approach**: SVID certificate contains Veridot `kid` in SAN (Subject Alternative Name); key publication includes SVID proof.

---

### RQ8: Threshold Verification for Consensus-Dependent Token Acceptance

**Question**: Is it possible to design a **threshold verification** mechanism where a token is only accepted if t-of-n independent verifiers independently validate it, without requiring verifier coordination?

**Background**: In high-security contexts, a single verifier accepting a token may be insufficient. If verifiers could publish their verification results (signed) to a shared topic, downstream systems could require t-of-n verifier confirmations before acting on a token.

**This is the verification-side analog of threshold signing**: security multiplied not just at signing time but at verification time.

---

### RQ9: Quantum-Safe Migration Without Flag Day

**Question**: What is the minimal protocol change required to migrate from Ed25519 to a post-quantum algorithm without a coordinated flag-day cutover across all Signers and Verifiers in a large deployment?

**Background**: PEP-06 proposes dual-signature hybrid mode. But what is the minimum coordination required? Can verifiers autonomously determine which algorithm to use based solely on the token header and their local configuration?

**Relevance**: Critical for large-scale industrial deployments where coordinated upgrades are infeasible.

---

### RQ10: Veridot as a Substrate for Decentralized Attestation

**Question**: Can Veridot's broker-native key distribution be generalized to support hardware attestation (TPM, SGX, TrustZone), where the "signer" is a hardware security module and the key is attested by the hardware manufacturer?

**Background**: Remote attestation currently requires complex protocols (RATS, TLS-Attested TLS). If a hardware module's attestation certificate could be published to a Veridot key topic, verifiers could verify hardware attestation using the same infrastructure already used for software-level signing.

---

## 16. Scoring Summary

### 16.1 Veridot v1 (Current Protocol) Evaluation Scores

| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 4/10 | Strong crypto primitives; weak trust infrastructure; no revocation |
| Performance | 7/10 | Excellent steady-state; cold-start latency penalty |
| Scalability | 7/10 | Excellent horizontal scale; unbounded keystore |
| Reliability | 6/10 | Broker dependency; no signer HA |
| Resilience | 6/10 | Key cache provides broker failure tolerance; no revocation for compromise |
| Simplicity | 9/10 | Minimal API; elegant core design |
| Auditability | 3/10 | No structured logging; no audit trail; no identity |
| Testability | 7/10 | Clean interfaces; in-process adapter for testing |
| Maintainability | 7/10 | Small codebase; clear separation |
| Operational Complexity | 8/10 | Low ops burden; broker is pre-existing infrastructure |
| Implementation Complexity | 9/10 | Very simple; minimal dependencies |
| Backward Compatibility | N/A | Initial version |
| Research Novelty | 7/10 | Novel combination of broker-native key dist + JWT signing |
| Standardization Potential | 5/10 | Unique niche; needs formal spec and security proof |

### 16.2 Veridot 2.0 (Proposed) Evaluation Scores

| Dimension | Score | Justification |
|-----------|-------|---------------|
| Security | 8/10 | PoP, revocation, replay protection, TAC, PQ path |
| Performance | 8/10 | Marginal overhead; local verification preserved |
| Scalability | 9/10 | Epoch-bounded keystore; horizontal scale preserved |
| Reliability | 8/10 | DKC for signer HA; revocation propagation lag remains |
| Resilience | 8/10 | Revocation + epoch + federated trust |
| Simplicity | 6/10 | Increased complexity; justified by security improvements |
| Auditability | 9/10 | Identity manifests; Merkle audit; structured logging |
| Testability | 7/10 | More components; but well-defined interfaces |
| Maintainability | 7/10 | More moving parts; clear versioning mitigates |
| Operational Complexity | 6/10 | Additional topics; TAC management overhead |
| Implementation Complexity | 7/10 | Significant new code; modular design |
| Backward Compatibility | 8/10 | v1 tokens remain valid; additive extensions |
| Research Novelty | 9/10 | Novel combination across multiple dimensions |
| Standardization Potential | 8/10 | Clean spec; multiple implementations needed |

### 16.3 Protocol Extension Scores Summary Table

| Extension | Security | Perf | Scale | Reliability | Resilience | Simplicity | Auditability | Testability | Maintainability | OpComplex | ImplComplex | BackCompat | Novelty | Standard |
|-----------|----------|------|-------|-------------|------------|------------|--------------|-------------|-----------------|-----------|-------------|------------|---------|----------|
| PEP-01 (Key Auth PoP) | 8 | 9 | 10 | 9 | 8 | 8 | 7 | 9 | 9 | 9 | 9 | 7 | 4 | 7 |
| PEP-02 (Revocation) | 9 | 7 | 8 | 8 | 9 | 6 | 9 | 7 | 7 | 6 | 5 | 6 | 5 | 7 |
| PEP-03 (Replay Prevent) | 9 | 8 | 8 | 8 | 8 | 7 | 8 | 8 | 7 | 7 | 6 | 9 | 6 | 7 |
| PEP-04 (Identity Manifest) | 7 | 9 | 9 | 9 | 8 | 7 | 9 | 8 | 8 | 7 | 6 | 8 | 7 | 7 |
| PEP-05 (Key Epoch) | 7 | 8 | 9 | 8 | 8 | 7 | 7 | 8 | 8 | 7 | 6 | 7 | 4 | 6 |
| PEP-06 (Algo Agility) | 9 | 6 | 8 | 8 | 9 | 5 | 7 | 6 | 7 | 5 | 4 | 6 | 6 | 8 |
| PEP-07 (Merkle Audit) | 8 | 7 | 7 | 8 | 8 | 4 | 10 | 6 | 6 | 4 | 3 | 7 | 9 | 7 |
| PEP-08 (Federated TAC) | 8 | 7 | 7 | 7 | 8 | 4 | 8 | 5 | 5 | 3 | 3 | 7 | 9 | 7 |
| PEP-09 (Adaptive Signing) | 8 | 8 | 9 | 8 | 8 | 6 | 8 | 7 | 7 | 6 | 6 | 10 | 5 | 6 |
| Ext: DKC | 8 | 7 | 8 | 9 | 9 | 3 | 7 | 4 | 5 | 3 | 2 | 8 | 8 | 6 |
| Ext: Threshold Sign | 10 | 4 | 6 | 8 | 9 | 2 | 8 | 3 | 4 | 2 | 1 | 9 | 9 | 7 |
| Ext: ZKTA | 10 | 2 | 5 | 7 | 8 | 1 | 9 | 2 | 3 | 2 | 1 | 8 | 10 | 6 |

---

## Conclusion

Veridot represents a genuinely innovative approach to distributed message authenticity: by leveraging the message broker already present in distributed systems as a key distribution channel, it achieves offline-capable, local-verification distributed signing with a minimal API surface. The core cryptographic choices — Ed25519 and JWT — are sound and modern.

However, the protocol as currently implemented has critical structural gaps that prevent its use in security-sensitive production environments: the absence of key authentication, the absence of key revocation, and the absence of a formal trust hierarchy leave the trust model dependent entirely on broker access control — a fragile and under-specified guarantee.

The Veridot 2.0 architecture presented in this paper addresses all identified structural weaknesses while preserving the protocol's defining philosophy: broker-native, locally-verified, operationally simple distributed signing. The extensions are designed to be adopted incrementally, with each phase delivering independently valuable security improvements.

The most critical near-term action is implementing proof-of-possession for key publications (PEP-01) and basic key revocation (PEP-02). These two changes alone would elevate Veridot from an interesting prototype to a credible production protocol, and they are achievable with minimal implementation complexity and full backward compatibility.

The research agenda outlined in Section 15 presents genuine open problems — particularly formal verification, Byzantine-fault-tolerant key consensus, and privacy-preserving signer identity — that are worthy of academic investigation and could yield fundamental contributions to the distributed systems security literature.

Veridot's core insight — that the message broker is the right place to distribute signing keys in event-driven architectures — deserves formalization, rigorous evaluation, and community standardization. This paper provides the foundation for that process.

---

*End of Veridot Protocol Research Evaluation and Evolution Proposal*

---

**Document Metadata**

| Field | Value |
|-------|-------|
| Version | 1.0.0 |
| Status | Research Draft |
| Source | Reverse-engineered from cyfko/veridot repository |
| Methodology | Executable source code analysis only |
| Date | 2025 |
| Classification | Open Research |