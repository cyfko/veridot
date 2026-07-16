---
title: Security Model
description: Threat model, fail-closed semantics, timing-safe verification, and residual risk disclosure for the Veridot protocol.
keywords: [security, threat model, fail-closed, timing-safe, ML-DSA, TrustRoot, broker trust]
sidebar_position: 2
---

# Security Model

Veridot's security model is designed around a single principle: **the broker is transport only, never an authority** (Protocol V5 §1.3.3). All trust decisions are made locally by each processor using cryptographic verification against the `TrustRoot`.

## Broker Trust Model

The broker stores and relays bytes. It is never granted authority over the meaning of those bytes. If an attacker injects a forged `SIGNED_DATA` without the long-term private key material corresponding to a TrustRoot-resolvable `issuer`, conforming verifiers will reject it immediately.

## Fail-Closed Semantics

Veridot enforces fail-closed behavior in every failure scenario.

### TrustRoot Unavailable → Reject
A processor MUST NOT fall back to accepting entries without trust resolution. `TrustRoot` unavailability produces the same outcome as a definitive rejection.

### Missing LIVENESS → Reject
A session is valid **if and only if** a fresh `LIVENESS(ACTIVE)` entry exists with the highest observed `version` that passes all validation and `now < validUntil`. Absence of a fresh `ACTIVE` attestation produces rejection.

## Allowed Signature Algorithms & Post-Quantum Readiness

Veridot V5 supports the following signature algorithms:
- `0x01` ED25519 (Default)
- `0x02` RSA_PSS_SHA256 (Default)
- `0x03` ECDSA_P256_SHA256 (Supported)
- `0x04` ECDSA_P384_SHA384 (Supported)
- `0x05` RSA_PSS_SHA384 (Supported)
- `0x06` RSA_PSS_SHA512 (Supported)
- `0x07` ML_DSA_65 (Supported - Post-Quantum)

The protocol natively accommodates ML-DSA-65 (NIST FIPS 204) for post-quantum security in `NATIVE` and `PRIVATE` modes. 
*Note: ML_DSA_65 MUST NOT be used in `DIRECT` (JWT) mode due to lack of standardized JWT headers.*

## Single Key Per Instance

In cloud-native environments, it is required that an instance generates exactly one asymmetric keypair during its lifetime (Single Key Per Instance). This SKPI pattern structurally bounds the compromise radius: compromising one instance's key compromises exactly that instance, which is resolved by instance replacement instead of key rotation.

## Envelope Signing Scope

The `signature` field in every V5 envelope covers **every byte** preceding `sigAlg` in the encoded envelope, in wire order. No field is excluded.

## Residual Risks

| Residual Risk | Mitigation |
|---|---|
| **Compromise of Instance Private Key** | Binding identity to a single key (CN@hash) limits blast radius. Revoke instance by publishing a `LIVENESS(REVOKED)` from a higher-capability actor or revoking trust in TAAS via `TRUST_REVOCATION`. |
| **Clock drift beyond tolerance** | Use NTP synchronization. |

## Next Steps

- [Trust Hierarchy](./trust-hierarchy.md) — root identities, capability delegation, bootstrap
- [Distributed Consistency](./distributed-consistency.md) — monotonic versions
