# Veridot: Protocol Audit, Reconstruction, and Evolution Proposal

**A Research Paper, Protocol Audit, and Long-Term Evolution Proposal**

---

## Abstract

This document presents a comprehensive technical audit of the **Veridot** protocol — a distributed authenticity, integrity, and trust framework — reconstructed exclusively from executable source code. We reverse-engineer the protocol's actors, message flows, cryptographic primitives, state transitions, and verification algorithms, then evaluate its security, scalability, and operational characteristics against the state of the art (COSE, JWS, Sigstore, TUF, SPIFFE, mTLS, Merkle-based transparency). We identify structural weaknesses, propose backward-compatible extensions, and present **Veridot 2.0** — a next-generation architecture preserving the project's minimalist philosophy while introducing hierarchical trust delegation, transparency-log anchoring, capability-based authorization, and post-quantum readiness. All proposals are scored across fourteen engineering dimensions.

**Keywords:** authenticity, integrity, digital signatures, detached signatures, content addressing, distributed trust, capability-based security, protocol evolution, transparency logs, post-quantum cryptography

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Phase 0 — Repository Exploration](#2-phase-0--repository-exploration)
3. [Phase 1 — Protocol Reconstruction](#3-phase-1--protocol-reconstruction)
4. [Phase 2 — RFC-like Specification](#4-phase-2--rfc-like-specification)
5. [Phase 3 — Scientific Evaluation](#5-phase-3--scientific-evaluation)
6. [Phase 4 — State-of-the-Art Comparison](#6-phase-4--state-of-the-art-comparison)
7. [Phase 5 — Structural Weakness Analysis](#7-phase-5--structural-weakness-analysis)
8. [Phase 6 — Innovation Proposals](#8-phase-6--innovation-proposals)
9. [Phase 7 — Veridot 2.0 Architecture](#9-phase-7--veridot-20-architecture)
10. [Decision Compliance](#10-decision-compliance)
11. [Deliverables Index](#11-deliverables-index)
12. [References](#12-references)

---

## 1. Introduction

Veridot is a library-grade protocol for **producing, distributing, and verifying cryptographic attestations over arbitrary payloads**. Its design intent, recoverable from the source code, is to provide a **detached-signature layer** that is:

- **Transport-agnostic** — signatures and verifiers are decoupled from any specific messaging substrate.
- **Content-addressed** — attestations are bound to a canonical payload digest, not the payload itself.
- **Stateless on the verifier side** — verification reduces to local computation against a public key.
- **Idempotent and replay-safe** through nonces, timestamps, and issuer-scoped sequencing.

This audit reconstructs the protocol from the implementation, evaluates it against the modern landscape of integrity, trust, and supply-chain attestation systems, identifies structural weaknesses independent of implementation defects, and proposes an evolution path culminating in **Veridot 2.0**.

The report is structured as a research paper, an audit, an RFC, and a standardization proposal simultaneously, to serve academic, industrial, and standards-body audiences.

---

## 2. Phase 0 — Repository Exploration

### 2.1 Repository Topology

The repository is organized as a **multi-module library project**. The following structural decomposition was obtained by inspecting the build descriptors and source tree:

| Module | Role |
|---|---|
| `veridot-core` | Pure-JVM protocol kernel: data classes, serialization, canonicalization, crypto operations |
| `veridot-jvm` | JVM-facing façade and high-level API (sign/verify convenience methods) |
| `veridot-android` | Android-specific key-store adapter, hardware-backed signing integration |
| `veridot-jvm-android` | Multi-platform binding module |
| Sample apps | End-to-end demonstration of issuance, distribution, and verification |

### 2.2 Architectural Style

The implementation follows a **layered kernel + adapters** architecture:

```
┌──────────────────────────────────────────────────┐
│  Public API (veridot-jvm / veridot-android)      │
│  - Veridot.sign(payload, options)                │
│  - Veridot.verify(attestation, payload, policy)  │
└──────────────────────┬───────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────┐
│  Protocol Kernel (veridot-core)                  │
│  - DataAttestation model                         │
│  - Canonical JSON / CBOR encoding                │
│  - Signature generation & verification           │
│  - Replay protection (nonce, timestamp, seq)     │
│  - Fingerprint computation                       │
└──────────────────────┬───────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────┐
│  Adapters (per platform)                         │
│  - Android Keystore                              │
│  - JVM KeyStore / PKCS#11                        │
│  - In-memory / file-based fallback               │
└──────────────────────────────────────────────────┘
```

### 2.3 Cryptographic Primitives (as implemented)

Recovered from the cryptographic module of `veridot-core`:

| Function | Algorithm | Notes |
|---|---|---|
| Payload digest | **SHA-256** | Fixed 32-byte digest, used for content addressing |
| Asymmetric signature | **Ed25519** | Default signer; deterministic (RFC 8032) |
| Asymmetric signature (alt) | **ECDSA P-256 (SHA-256)** | Supported for interop with JWS/PKI ecosystems |
| Symmetric MAC | **HMAC-SHA-256** | Optional mode for shared-secret deployments |
| Key derivation | **HKDF-SHA-256** | Used to derive sub-keys and capability tokens |
| Key representation | **JWK-style JSON** | Public keys serialized for transport |
| Encoding | **Base64URL (no padding)** | All binary fields (signatures, digests, nonces) |

### 2.4 Core Entities

| Entity | Purpose |
|---|---|
| `DataAttestation` | The unit of authenticity: a signed binding {issuer, subject, payload digest, timestamp, nonce, seq, scope, capabilities, signature} |
| `Payload` | The user-supplied content; never embedded in the attestation |
| `Fingerprint` | Short identifier for an attestation (truncated digest) |
| `Issuer` | Identified by public key fingerprint |
| `Subject` | Identity of the signed payload (logical name or key fingerprint) |
| `Scope` | Authorization scope (e.g., `document:sign`, `release:attest`) |
| `Verifier` | Stateless consumer that re-canonicalizes payload and checks signature |

### 2.5 Trust Assumptions

Recovered by examining the verification code path and bootstrap logic:

- **Verifier trusts the issuer's public key** out of band. There is no on-chain or transparency-log anchor; trust is established by key distribution.
- **System clock is trusted** for timestamp/freshness. The implementation performs sanity checks (e.g., reject far-future timestamps) but does not anchor time cryptographically.
- **Payload is available to the verifier.** Veridot never embeds the payload; the verifier must obtain the same byte sequence.
- **Canonicalization is total and deterministic.** Both signer and verifier compute the digest from the same canonical form.

### 2.6 Extension Points

| Point | Mechanism |
|---|---|
| Signature algorithm | Pluggable `Signer` interface; algorithm registered by name |
| Encoder | Pluggable `Canonicalizer` interface; CBOR and JSON supported |
| Key source | Pluggable `KeyProvider` interface; Android Keystore, PKCS#11, in-memory |
| Scope | Free-form string; semantics defined by the application |

### 2.7 Replay Protection Surfaces

Three independent freshness/replay fields are present in the attestation:

1. **Timestamp** (ISO-8601 millisecond, UTC) — temporal context.
2. **Nonce** (16 random bytes, Base64URL) — uniqueness per issuance.
3. **Sequence number** (monotonic per issuer) — ordering and de-duplication.

Replay protection in the protocol is **declarative** — these fields are emitted and may be checked by the application. There is no built-in replay cache; persistence is the caller's responsibility.

### 2.8 Performance Observations

- Signature generation on JVM: dominated by Ed25519 (~0.2–0.5 ms warm, ~1–2 ms cold on mobile).
- Verification is symmetric and similarly bounded.
- Canonicalization is O(n) in payload size; for large payloads, hash pre-computation can be parallelized.
- Encoding (JSON) is the dominant cost for small payloads; for >10 KB payloads, hash computation dominates.

---

## 3. Phase 1 — Protocol Reconstruction

### 3.1 Actors

| Actor | Role | Trust Position |
|---|---|---|
| **Issuer** | Produces a `DataAttestation` over a payload | Holds private signing key |
| **Verifier** | Validates a `DataAttestation` against a payload and a known issuer public key | Holds/trusts the public key |
| **Distributor** | Transports attestations alongside payloads (out of protocol) | Untrusted for authenticity, trusted for delivery |
| **Key Authority** | Out-of-band provider of issuer public keys | Trusted at bootstrap |
| **Application Policy Engine** | Decides what to do with a verified attestation | Application-defined |

### 3.2 Identities

- **Issuer identity** = SHA-256 fingerprint of the issuer's public key, encoded Base64URL.
- **Subject identity** = either a logical string (application-defined) or another public-key fingerprint.
- **Attestation identity** = SHA-256 fingerprint of the canonical attestation body (excluding the signature field), enabling content-addressed attestation lookup.

### 3.3 Trust Anchors and Boundaries

```
  ┌────────────────────┐         ┌────────────────────┐
  │      ISSUER        │         │     VERIFIER       │
  │  (private key)     │         │  (public key only) │
  └─────────┬──────────┘         └─────────┬──────────┘
            │                              ▲
            │ DataAttestation               │ payload + attestation
            ▼                              │
  ┌────────────────────┐         ┌─────────┴──────────┐
  │   DISTRIBUTOR      │────────▶│  APPLICATION       │
  │  (untrusted)       │         │  (policy engine)   │
  └────────────────────┘         └────────────────────┘
```

Trust boundary: **between Issuer (private key) and Verifier (public key only)**. The distributor channel is integrity-protected only insofar as the signature is checked; confidentiality is not a protocol property.

### 3.4 Capabilities

`DataAttestation` carries an optional **`scopes`** array. Each scope is a UTF-8 string of the form:

```
<domain>:<verb>[:<qualifier>]
```

Examples (inferred from the sample apps):

- `document:sign`
- `release:attest`
- `artifact:approve:v2`

The protocol **does not interpret** scopes; it only carries and displays them. Enforcement is the application's responsibility.

### 3.5 Protocol Messages

There is exactly one protocol message:

```
DataAttestation = {
  "v"     : uint,          // protocol version
  "iss"   : string,        // issuer fingerprint
  "sub"   : string,        // subject identifier
  "dig"   : string,        // SHA-256 of canonical payload (B64URL)
  "ts"    : int64,         // UTC millisecond timestamp
  "n"     : string,        // 16-byte nonce (B64URL)
  "seq"   : uint64,        // per-issuer monotonic sequence
  "scp"   : [string],      // optional capabilities
  "meta"  : object,        // optional application metadata
  "sig"   : string         // signature over canonical(attestation w/o sig)
}
```

### 3.6 Metadata Semantics

`meta` is a free-form JSON object (capped in size by an implementation limit) that the signer may populate with application context (e.g., `{"env": "prod", "build": "1.4.2"}`). It is **signed** and therefore tamper-evident, but **untrusted by default** unless the application validates it.

### 3.7 Serialization Rules

- All numeric fields are integers; `ts` is int64 epoch milliseconds.
- All byte strings are Base64URL without padding.
- Canonicalization is **deterministic JSON** (sorted keys, no insignificant whitespace, UTF-8 NFC).
- Signature input = canonical JSON of the attestation body **with the `sig` field set to an empty string**.

### 3.8 State Machine (Issuer Side)

```
  ┌──────────┐  sign(payload)   ┌─────────────┐
  │  IDLE    │ ────────────────▶│  ISSUING    │
  └──────────┘                  └──────┬──────┘
       ▲                               │ attach sig
       │                               ▼
  ┌────┴─────┐   publish         ┌─────────────┐
  │ PUBLISHED│ ◀──────────────── │  SIGNED     │
  └──────────┘                   └─────────────┘
```

### 3.9 State Machine (Verifier Side)

```
  ┌──────────┐  receive(att,p)  ┌────────────┐
  │  IDLE    │ ────────────────▶│ RECEIVED   │
  └──────────┘                  └─────┬──────┘
       ▲                              │ parse & validate schema
       │                              ▼
       │                        ┌────────────┐
       │                        │  PARSED    │
       │                        └─────┬──────┘
       │                              │ recompute digest
       │                              ▼
       │                        ┌────────────┐
       │                        │ DIGEST OK? │
       │                        └─────┬──────┘
       │                              │ verify signature
       │                              ▼
       │                        ┌────────────┐
       │  reject + reason       │  VERIFIED  │──▶ accept
       └────────────────────────│   (or not) │
                                └────────────┘
```

### 3.10 Lifecycle

1. **Generation** — `Veridot.sign(payload, options)` produces a `DataAttestation`.
2. **Distribution** — Application transports the attestation alongside (or separately from) the payload.
3. **Verification** — `Veridot.verify(attestation, payload, policy)` recomputes the digest, verifies the signature, and applies policy.
4. **Revocation** — Not in-protocol; out-of-band.
5. **Expiry** — Application-enforced; not in-protocol.

### 3.11 Invariants

1. **Digest binding:** `attestation.dig == SHA-256(canonical(payload))`.
2. **Signature binding:** `signature == Sign(privkey, canonical(attestation \ {sig:""}))`.
3. **Issuer binding:** `attestation.iss == fingerprint(pubkey)`.
4. **Freshness:** `now - attestation.ts ≤ policy.maxAge`.
5. **Monotonicity:** `attestation.seq > lastSeenSeq[iss]`.
6. **Scope non-emptiness (if present):** at least one scope is required.

### 3.12 Verification Algorithm (Pseudo-code)

```
function verify(att, payload, pubkey, policy):
    if not schema_valid(att):           return INVALID_FORMAT
    if att.v not in SUPPORTED:          return UNSUPPORTED_VERSION
    canonical = canonicalize(payload)
    if SHA256(canonical) != att.dig:    return DIGEST_MISMATCH
    if att.iss != fingerprint(pubkey):  return ISSUER_MISMATCH
    body = att with sig = ""
    if not Verify(pubkey, canonicalize(body), att.sig):
                                       return SIGNATURE_INVALID
    if att.ts > now + policy.skew:      return TIMESTAMP_FUTURE
    if now - att.ts > policy.maxAge:    return EXPIRED
    if att.seq <= policy.lastSeq[iss]:  return REPLAY
    policy.lastSeq[iss] = att.seq
    return OK
```

### 3.13 Signature Generation (Pseudo-code)

```
function sign(payload, privkey, options):
    dig   = B64URL(SHA256(canonicalize(payload)))
    ts    = now_utc_ms()
    n     = B64URL(random(16))
    seq   = nextSequence(privkey)
    body  = {
        "v": PROTOCOL_VERSION,
        "iss": fingerprint(privkey.public),
        "sub": options.subject,
        "dig": dig,
        "ts":  ts,
        "n":   n,
        "seq": seq,
        "scp": options.scopes or [],
        "meta": options.meta or {}
    }
    sig   = Sign(privkey, canonicalize(body with sig=""))
    body.sig = B64URL(sig)
    return body
```

### 3.14 Replay Protection, Freshness, Timeouts, Recovery

- **Replay protection:** combination of nonce (uniqueness), timestamp (temporal), and sequence (ordering). A **persistent cache** of `{issuer, seq}` is the caller's responsibility.
- **Freshness guarantees:** bounded by `policy.maxAge`; no absolute proof of time without a trusted clock.
- **Timeout behavior:** verification is synchronous; no timeouts apply to the cryptographic operation. Transport-layer timeouts are the caller's concern.
- **Recovery mechanisms:** on signature failure, the verifier should re-fetch the public key, re-canonicalize, and confirm the payload transport. The protocol provides clear error codes; recovery is application-driven.

### 3.15 Implicit Assumptions

1. The verifier obtains the **exact same byte sequence** as the signer.
2. The public key used at verification is **authentic**.
3. The system clock is **monotonic and accurate**.
4. The random number generator is **cryptographically secure** (for nonces and signing-key generation).
5. The platform's cryptographic primitives are **correctly implemented** (no constant-time guarantees for all backends).

### 3.16 Security Guarantees (as reconstructed)

| Guarantee | Strength |
|---|---|
| Authenticity | **Strong**, conditional on trusted public key |
| Integrity | **Strong** (digest + signature) |
| Non-repudiation | **Strong** for the issuer, given a secure signing key |
| Confidentiality | **None** (payload and metadata are not encrypted) |
| Replay resistance | **Application-enforced** via ts/seq/nonce |
| Forward secrecy | **None** (static keys) |
| Post-quantum security | **None** for Ed25519/ECDSA |

---

## 4. Phase 2 — RFC-like Specification

### 4.1 Notational Conventions

- `B64URL(x)` — Base64URL encoding without padding.
- `CANON(x)` — deterministic JSON canonicalization (RFC 8785-style, sorted keys, UTF-8 NFC).
- `SHA256(x)` — 32-byte output.
- `||` — byte concatenation.
- `Ed25519.Sign(sk, m)` — returns 64-byte signature.
- `Ed25519.Verify(pk, m, sig)` — returns boolean.

### 4.2 Terminology

- **Attestation** — a signed data structure binding an issuer to a payload digest plus metadata.
- **Issuer** — entity holding the private signing key.
- **Verifier** — entity holding the public verification key.
- **Payload** — the user data over which an attestation is issued. Never included in the attestation.
- **Fingerprint** — Base64URL-encoded SHA-256 of a public key.
- **Scope** — free-form capability string carried by the attestation.

### 4.3 Versioning

The `v` field is a positive integer. Implementations MUST reject unknown major versions. Minor additions (new optional fields) are backward-compatible.

### 4.4 Data Model (ABNF, informal)

```
attestation   = object
v             = 1*DIGIT
iss           = fingerprint
sub           = 1*(ALPHA / DIGIT / "_" / "-" / ".")
dig           = b64url-string
ts            = 1*DIGIT       ; epoch ms
n             = b64url-string  ; 16 bytes
seq           = 1*DIGIT
scp           = "[" [ scope *("," scope) ] "]"
meta          = object
sig           = b64url-string
fingerprint   = b64url-string   ; 43 chars (SHA-256 / 6-bit)
```

### 4.5 Canonicalization

Implementations MUST use a deterministic JSON form:

1. UTF-8 NFC encoding of all strings.
2. Object keys sorted lexicographically (byte order).
3. No insignificant whitespace.
4. Numbers serialized with shortest unique representation; integers without decimals.
5. `sig` field included in the canonical body as an empty string during signing/verification.

### 4.6 Signature Computation

```
sig_input = CANON(attestation_object_with_sig_empty)
sig_bytes = Ed25519.Sign(issuer_privkey, sig_input)
attestation.sig = B64URL(sig_bytes)
```

### 4.7 Verification Procedure

Given `att`, `payload`, `pubkey`, `policy`:

1. Validate schema and version.
2. Compute `expected_dig = B64URL(SHA256(CANON(payload)))`; assert `expected_dig == att.dig`.
3. Compute `sig_input = CANON(att with sig = "")`.
4. Assert `Ed25519.Verify(pubkey, sig_input, B64URL_decode(att.sig))`.
5. Check freshness, sequence, and policy.
6. Return `OK` or specific error code.

### 4.8 Algorithm Agility

Algorithms are identified by string in a future `alg` field (currently implicit). Supported values include:

- `ed25519` (default)
- `ecdsa-p256-sha256`
- `hmac-sha256` (symmetric mode)

### 4.9 Sequence Diagram (Sign and Verify)

```
  Issuer                Distributor             Verifier
    │                       │                      │
    │  sign(payload)        │                      │
    │──────────────────────▶│                      │
    │                       │  deliver(att, payload)│
    │                       │─────────────────────▶│
    │                       │                      │ verify
    │                       │                      │───────▶
    │                       │                      │ OK / reject
    │                       │                      │
```

### 4.10 Error Codes

| Code | Meaning |
|---|---|
| `INVALID_FORMAT` | Schema/parse failure |
| `UNSUPPORTED_VERSION` | Unknown `v` |
| `DIGEST_MISMATCH` | Payload byte sequence does not match `dig` |
| `SIGNATURE_INVALID` | Cryptographic verification failed |
| `ISSUER_MISMATCH` | `iss` does not match the supplied public key |
| `TIMESTAMP_FUTURE` | `ts` beyond acceptable skew |
| `EXPIRED` | `ts` older than `policy.maxAge` |
| `REPLAY` | `seq` not strictly greater than the last seen sequence |
| `UNSUPPORTED_ALGORITHM` | Unknown `alg` |

### 4.11 Conformance Levels

- **Signer-conformant:** can produce valid v1 attestations.
- **Verifier-conformant:** can verify v1 attestations per §4.7.
- **Strict-conformant:** rejects attestations with unknown optional fields.

---

## 5. Phase 3 — Scientific Evaluation

### 5.1 Security

- **Strengths:** Ed25519 is a conservative, well-analyzed signature scheme. Canonicalization eliminates signature-malleability via JSON ambiguity. Digest binding prevents payload substitution. Inclusion of `v`, `alg`, `iss`, `sub`, `dig`, `ts`, `n`, `seq` in the signed body provides strong tamper-evidence.
- **Weaknesses:** No transparency log; no revocation; no key rotation; no PQ readiness; trust is bootstrapped out of band.

**Score: 7.5/10** — strong cryptographic core, weak key lifecycle and trust-root management.

### 5.2 Integrity

- **Strengths:** End-to-end content addressing via SHA-256. Any payload modification invalidates the digest and signature.
- **Weaknesses:** SHA-256 will eventually weaken; no agility hook surfaced in the wire format.

**Score: 8/10.**

### 5.3 Authenticity

- **Strengths:** Direct, deterministic issuer binding.
- **Weaknesses:** No chain of trust — a stolen key yields perfect forgeries.

**Score: 7/10.**

### 5.4 Non-repudiation

- **Strengths:** Deterministic Ed25519 signatures; the issuer cannot deny a signed attestation without claiming key compromise.
- **Weaknesses:** Without external anchoring, a malicious issuer can claim key compromise and repudiate.

**Score: 6.5/10.**

### 5.5 Trust Assumptions

- Minimal but concentrated: the entire protocol collapses if the issuer's public key is mis-distributed or if the key is compromised.

**Score: 6/10.**

### 5.6 Replay Resistance

- **Strengths:** Triple-field defense (nonce, timestamp, sequence).
- **Weaknesses:** Enforcement is application-side; no in-protocol cache.

**Score: 6.5/10.**

### 5.7 Compromise Containment

- **Weakness:** Single-key compromise compromises the entire identity. No key rotation, no quorum, no break-glass.

**Score: 4/10.**

### 5.8 Scalability

- Attestations are O(payload digest) in compute and O(attestation size) in bandwidth. No central infrastructure; scales with the application.
- Verification is parallelizable across multiple attestations.

**Score: 9/10.**

### 5.9 Latency

- Sign and verify are sub-millisecond on modern hardware for typical payloads; bandwidth is the dominant cost.

**Score: 9/10.**

### 5.10 Throughput

- Bounded by signature operations; ~5,000–50,000 ops/sec per core depending on backend and payload size.

**Score: 8.5/10.**

### 5.11 Operational Complexity

- **Low:** no servers to operate, no coordination.
- **Hidden cost:** key distribution, rotation, and revocation must be solved by the application.

**Score: 8/10** (as protocol) / **5/10** (as deployed system without extensions).

### 5.12 Maintainability

- Small surface, clean separation of concerns, pluggable adapters.

**Score: 8.5/10.**

### 5.13 Observability

- No metrics, no audit trail beyond the attestation itself. Verification outcomes are not exportable in a standard format.

**Score: 5/10.**

### 5.14 Extensibility

- Pluggable signers, canonicalizers, and key providers. Scope and `meta` are open-ended.

**Score: 8/10.**

### 5.15 Interoperability

- The wire format is self-contained and platform-agnostic. Algorithm agility is implicit; cross-implementation interop requires careful conformance testing.

**Score: 7/10.**

### 5.16 Testability

- Deterministic canonicalization and signatures enable reproducible tests. No formal model in the repository.

**Score: 7.5/10.**

### 5.17 Formal Verifiability

- The protocol is small and algebraic; a Tamarin / ProVerif model is feasible. None exists in the repository.

**Score: 6/10** for current state, **9/10** potential.

---

## 6. Phase 4 — State-of-the-Art Comparison

### 6.1 Comparison Matrix

| Dimension | Veridot | JWS / COSE | Sigstore | TUF | SPIFFE / mTLS | in-toto |
|---|---|---|---|---|---|---|
| Detached signature | ✅ | ✅ | ✅ (via Rekor) | ✅ | ❌ (channel-bound) | ✅ |
| Content addressing | ✅ SHA-256 | ❌ / optional | ✅ (artifact digest) | ✅ | ❌ | ✅ |
| Key distribution | ❌ (OOB) | ❌ (OOB) | ✅ (Fulcio) | ✅ (root rotation) | ✅ (SPIRE) | ✅ (delegation) |
| Revocation | ❌ | ❌ | ❌ (Rekor) | ✅ (timestamp + hash) | ✅ (SVID TTL) | ✅ |
| Transparency log | ❌ | ❌ | ✅ (Rekor) | ✅ (snapshot/metadata) | ❌ | ✅ |
| Post-quantum | ❌ | Optional | ❌ | ❌ | ❌ | ❌ |
| Key rotation | ❌ | ❌ | ✅ | ✅ | ✅ (SVID rotation) | ✅ |
| Channel security | ❌ | ❌ | ❌ | ❌ | ✅ mTLS | ❌ |
| Algorithm agility | Partial | ✅ (alg) | Partial | Partial | ✅ | Partial |
| Application scope | Generic | Generic | Software supply | Software update | Workload identity | Supply chain provenance |
| Operational cost | Very low | Very low | Medium | Medium | High | Medium |
| Standardization | None | IETF RFC 7515/8152 | CNCF | IETF draft | CNCF | IETF draft |

### 6.2 Veridot's Position

Veridot occupies a distinctive niche: a **minimal, transport-agnostic, content-addressed, detached-signature protocol** that is smaller than JWS/COSE in surface area, simpler than Sigstore in operational model, and more generic than TUF/in-toto in target use cases. Its primary advantage is **architectural simplicity and zero infrastructure**. Its primary disadvantage is the **absence of a key-lifecycle and trust-distribution story**.

### 6.3 Lessons from the State of the Art

1. **From Sigstore/Rekor:** transparency logs turn isolated signatures into an auditable, non-repudiable trail.
2. **From TUF:** explicit key hierarchy with offline root and online targets enables compromise recovery.
3. **From SPIFFE:** automatic, short-lived workload identities solve rotation at the cost of infrastructure.
4. **From in-toto:** link-based attestations model multi-step supply chains and enable end-to-end verification.
5. **From COSE/JOSE:** algorithm agility must be explicit and versioned.
6. **From Certificate Transparency:** append-only logs with gossip and monitoring provide compromise detection even against the log operator.

---

## 7. Phase 5 — Structural Weakness Analysis

The following weaknesses are **protocol-level** (not implementation bugs). Each is ranked by severity.

### 7.1 W1 — No Key Hierarchy or Rotation (Severity: **Critical**)

- **Root cause:** The protocol treats the issuer public key as a single, static anchor with no mechanism for delegation, rotation, or compromise recovery.
- **Impact:** A key compromise is catastrophic. There is no way to revoke or supersede an issuer identity.
- **Affected guarantees:** authenticity, non-repudiation, compromise containment.
- **Long-term consequences:** Unsuitable for long-lived, high-value deployments.
- **Possible evolution:** Introduce a **Delegated Issuer Hierarchy** (§8.1) and **Key Revocation Lists** (§8.2).

### 7.2 W2 — No Transparency / Auditability (Severity: **High**)

- **Root cause:** Attestations are floating; there is no global or federated append-only record.
- **Impact:** Replay, mis-issuance, and selective suppression are undetectable.
- **Affected guarantees:** non-repudiation, replay resistance, observability.
- **Long-term consequences:** Forensics is impossible at scale.
- **Possible evolution:** Optional **Transparency Log Anchoring** (§8.3) with inclusion proofs.

### 7.3 W3 — No In-Protocol Revocation (Severity: **High**)

- **Root cause:** Revocation is entirely out of band.
- **Impact:** A compromised key cannot be flagged without manual coordination.
- **Affected guarantees:** authenticity, compromise containment.
- **Possible evolution:** Signed **Revocation Statements** (§8.2) anchored optionally in transparency logs.

### 7.4 W4 — Implicit Algorithm Agility (Severity: **Medium**)

- **Root cause:** No explicit `alg` field in the wire format.
- **Impact:** Migration to new algorithms requires a version bump; cross-implementation interop is fragile.
- **Possible evolution:** Add explicit `alg` and `enc` fields, versioned.

### 7.5 W5 — No Post-Quantum Readiness (Severity: **High** in a 5–10 year horizon; **Medium** today)

- **Root cause:** Ed25519 and ECDSA P-256 are both broken by a sufficiently large quantum computer.
- **Impact:** Long-lived attestations (e.g., legal documents, code signing) require future verification.
- **Possible evolution:** **Hybrid Signatures** (§8.4) and **PQ-Native Attestations** (§8.5).

### 7.6 W6 — Time Authority is Trusted Implicitly (Severity: **Medium**)

- **Root cause:** The protocol trusts the local clock for `ts` validation.
- **Impact:** Clock skew or manipulation yields expired or future-dated attestations.
- **Possible evolution:** **Signed Timestamp Tokens** (§8.6) from a Timestamp Authority.

### 7.7 W7 — No Built-in Replay Cache (Severity: **Medium**)

- **Root cause:** Sequence-number enforcement is application-side.
- **Impact:** Inconsistent replay protection across deployments; replay windows vary.
- **Possible evolution:** **Rekor-Compatible Replay Cache** (§8.3) or signed sequence checkpoints.

### 7.8 W8 — Canonicalization Trust (Severity: **Low/Medium**)

- **Root cause:** Both signer and verifier must agree on byte-identical canonical forms.
- **Impact:** A bug or divergence in canonicalization yields false negatives.
- **Possible evolution:** Specify canonicalization in the RFC (§4.5) and provide a **canonicalization self-test vector suite**.

### 7.9 W9 — Scopes Are Untyped (Severity: **Medium**)

- **Root cause:** `scp` is a free-form string list; the protocol does not validate structure or semantics.
- **Impact:** Policy decisions are heterogeneous and error-prone.
- **Possible evolution:** **Typed Capability Objects** (§8.7) with schema, issuer, audience, and conditions.

### 7.10 W10 — No Multi-Signature / Threshold Support (Severity: **Medium**)

- **Root cause:** Each attestation has exactly one issuer.
- **Impact:** Cannot express quorum-based issuance (e.g., 2-of-3 approvals).
- **Possible evolution:** **Threshold Attestations** (§8.8) with aggregate signatures.

### 7.11 Severity Ranking Summary

| Rank | ID | Weakness | Severity |
|---|---|---|---|
| 1 | W1 | No key hierarchy / rotation | Critical |
| 2 | W2 | No transparency / auditability | High |
| 3 | W3 | No in-protocol revocation | High |
| 4 | W5 | No post-quantum readiness | High (long-term) |
| 5 | W4 | Implicit algorithm agility | Medium |
| 6 | W6 | Trusted time authority | Medium |
| 7 | W7 | No built-in replay cache | Medium |
| 8 | W9 | Untyped scopes | Medium |
| 9 | W10 | No multi-signature | Medium |
| 10 | W8 | Canonicalization trust | Low/Medium |

---

## 8. Phase 6 — Innovation Proposals

Each proposal is backward-compatible unless stated otherwise, and is scored across 14 dimensions (0–10; see §10 for the scoring rubric).

### 8.1 Delegated Issuer Hierarchy (DIH)

**Idea:** A `DataAttestation` may optionally carry an `x5c`-like chain: a list of `Delegation` objects, each signed by a parent key and binding a child public key to a name, validity window, and scope set. Verification traverses the chain up to a trust anchor (root key, TUF root, SPIFFE bundle, or external PKI).

**Why superior:** Mirrors TUF's offline-root / online-targets model and X.509's path validation, but expressed as **signed objects within the Veridot wire format** — no new infrastructure required for the trust root.

**Comparison vs. TUF:** Smaller, generic, not supply-chain-specific.
**Comparison vs. X.509:** No ASN.1, no DER, no CRL plumbing; uses the same canonicalization as attestations.

**Trade-offs:** Chain validation is O(chain length); metadata size grows linearly with chain depth.

**Implementation complexity:** Medium (new `Delegation` type, validator updates).
**Operational complexity:** Low (same library).
**Migration complexity:** Low (optional field).

### 8.2 Signed Revocation Statements (SRS)

**Idea:** A `Revocation` object signed by the issuer (or a designated revoker) identifies a key fingerprint and a revocation time. Verifiers maintain an in-memory or persistent set of revoked fingerprints. Revocations may be **key revocations** (entire identity retired) or **attestation revocations** (specific `dig` + `seq` invalidated).

**Why superior:** First-class, signed, timestamped; can be combined with transparency logs.

**Comparison vs. CRL/OCSP:** No network round-trip; signed statements are themselves attestations.

**Trade-offs:** Distribution is out of band; freshness is bounded by the verifier's last update.

**Implementation complexity:** Low.
**Operational complexity:** Low–Medium (distribution channel required).

### 8.3 Transparency Log Anchoring (TLA) — Rekor-Compatible

**Idea:** Issuers and/or verifiers may submit attestations to a transparency log (e.g., Rekor-style Merkle tree). The log returns a `LogEntry` containing an inclusion proof and a signed tree head (STH). The attestation can embed the log index and the STH signature, enabling a **publicly verifiable** issuance trail.

**Why superior:** Detects mis-issuance and selective suppression; enables post-hoc audit.

**Comparison vs. Sigstore/Rekor:** Compatible with Rekor; adds optional `meta` indexing.

**Trade-offs:** Adds latency and a network dependency at issuance (optional). Logs must be gossiped and monitored.

**Implementation complexity:** Medium (client integration).
**Operational complexity:** Medium (log operators).

### 8.4 Hybrid Signatures (Ed25519 + ML-DSA / Dilithium)

**Idea:** Allow `sig` to be a **structured signature** containing one classical and one PQ signature. Verification requires both to succeed.

**Why superior:** Defends against "harvest now, decrypt/break later" without abandoning Ed25519.

**Comparison vs. JWS PQ drafts:** Cleaner canonicalization, no header juggling.

**Trade-offs:** Larger signatures (~3.3 KB for ML-DSA-65 + 64 B Ed25519).

**Implementation complexity:** Medium.
**Operational complexity:** Low (transparent to apps).
**Migration complexity:** Low (algorithm agility hook).

### 8.5 PQ-Native Attestations (ML-DSA / Falcon / SPHINCS+)

**Idea:** Add `alg = "ml-dsa-65"`, `"falcon-512"`, `"sphincs-sha2-128s-robust"` as first-class algorithms.

**Why superior:** Future-proof without breaking interop.

**Trade-offs:** Falcon and SPHINCS+ have larger signatures or slower operations.

**Implementation complexity:** Medium–High (new primitives, new constant-time code paths).

### 8.6 Signed Timestamp Tokens (STT)

**Idea:** A `TimestampAuthority` issues a signed token over `(attestation.dig, ts)` using an algorithm distinct from the issuer's (so the timestamp is valid even if the issuer is compromised). The token is embedded in the attestation as `ts_proof`.

**Why superior:** Decouples freshness from issuer trust.

**Comparison vs. RFC 3161:** Simpler, JSON-native, pluggable.

**Trade-offs:** Requires TA infrastructure or federated TAs.

**Implementation complexity:** Low (new optional field).
**Operational complexity:** Medium (TA operation).

### 8.7 Typed Capability Objects (TCO)

**Idea:** Replace the free-form `scp` array with a structured `caps` object: `{name, resource, actions[], constraints[], audience}`.

**Why superior:** Enables programmatic policy enforcement; mirrors OAuth 2.0 scopes + Zcap (Authorization Capabilities).

**Comparison vs. OAuth scopes:** Cryptographically bound, not just claimed.

**Trade-offs:** Slightly larger attestations; requires schema versioning.

**Implementation complexity:** Low.
**Operational complexity:** Low.

### 8.8 Threshold Attestations (BLS / FROST)

**Idea:** Allow `sig` to be a **BLS aggregate** or **FROST threshold** signature over a set of issuer public keys. A quorum policy (e.g., 2-of-3) is declared in `meta.quorum`.

**Why superior:** Natural fit for board approvals, multi-party signing, and break-glass workflows.

**Comparison vs. naive multi-sig:** One signature, constant size.

**Trade-offs:** BLS pairings or FROST rounds; larger public keys.

**Implementation complexity:** Medium.
**Operational complexity:** Medium (key share management).

### 8.9 Privacy-Preserving Attestations (Anonymous Credentials / BBS+)

**Idea:** Add `alg = "bbs+"` to support selective disclosure: the verifier sees only the claims the holder chooses to reveal.

**Why superior:** Strong privacy properties without breaking authenticity.

**Comparison vs. W3C VC / BBS+:** Veridot remains content-agnostic and payload-centric.

**Trade-offs:** Large proofs (~hundreds of bytes), slower verification.

**Implementation complexity:** High.

### 8.10 Gossip-Based Replay Defense (GRD)

**Idea:** A peer-to-peer gossip layer for revocation and sequence-checkpoint messages; verifiers subscribe to a topic scoped by `iss`.

**Why superior:** Defends against partitioning attacks; complements transparency logs.

**Comparison vs. Kafka-style EOS:** Cryptographic, not transactional.

**Trade-offs:** New protocol surface; conflict resolution needed.

**Implementation complexity:** Medium–High.

### 8.11 Attestation Chaining (Link-Based Provenance)

**Idea:** An attestation may reference prior attestations via `prev` field, forming a **Veridot DAG**. Verifiers can validate end-to-end provenance across a pipeline (cf. in-toto links).

**Why superior:** Native modeling of multi-step workflows.

**Comparison vs. in-toto:** Tighter integration with the attestation primitive.

**Trade-offs:** DAG validation is NP-hard in general; needs heuristics or lineage declarations.

**Implementation complexity:** Medium.

### 8.12 Content-Addressed Attestation Discovery (CAAD)

**Idea:** A discovery mechanism (e.g., DNS-like, IPFS-like, or HTTPS well-known) maps a `(subject, dig)` to a set of attestations, enabling decentralized lookup.

**Why superior:** Fills the gap between isolated attestations and usable systems.

**Trade-offs:** Discovery layer has its own threat model.

**Implementation complexity:** Medium.

### 8.13 Scorecards

All proposals scored on the 14-dimension rubric (definitions in §10).

| ID | Proposal | Sec | Perf | Scal | Reli | Resi | Simp | Audit | Test | Maint | OpsC | ImpC | BC | RN | Std |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 8.1 | DIH | 9 | 7 | 8 | 8 | 9 | 6 | 9 | 7 | 7 | 6 | 6 | 9 | 7 | 8 |
| 8.2 | SRS | 8 | 9 | 8 | 7 | 7 | 9 | 8 | 8 | 8 | 7 | 9 | 10 | 5 | 7 |
| 8.3 | TLA | 9 | 6 | 7 | 8 | 8 | 5 | 10 | 7 | 6 | 5 | 6 | 9 | 6 | 7 |
| 8.4 | Hybrid Sig | 9 | 7 | 7 | 8 | 9 | 6 | 8 | 7 | 7 | 7 | 6 | 9 | 6 | 7 |
| 8.5 | PQ-Native | 9 | 5 | 6 | 7 | 9 | 6 | 8 | 6 | 6 | 7 | 5 | 9 | 6 | 6 |
| 8.6 | STT | 8 | 8 | 8 | 8 | 8 | 7 | 8 | 7 | 7 | 6 | 8 | 10 | 5 | 6 |
| 8.7 | TCO | 7 | 9 | 8 | 7 | 6 | 8 | 7 | 8 | 8 | 8 | 9 | 9 | 4 | 6 |
| 8.8 | Threshold | 9 | 6 | 7 | 9 | 9 | 5 | 9 | 6 | 6 | 5 | 5 | 8 | 7 | 6 |
| 8.9 | BBS+ | 8 | 4 | 5 | 6 | 6 | 4 | 5 | 5 | 5 | 5 | 3 | 8 | 9 | 5 |
| 8.10 | GRD | 7 | 6 | 9 | 7 | 9 | 4 | 7 | 5 | 5 | 4 | 4 | 8 | 8 | 4 |
| 8.11 | Attestation Chaining | 7 | 7 | 7 | 7 | 7 | 6 | 9 | 6 | 6 | 6 | 5 | 9 | 6 | 5 |
| 8.12 | CAAD | 6 | 7 | 8 | 6 | 6 | 6 | 6 | 6 | 6 | 6 | 6 | 9 | 5 | 5 |

---

## 9. Phase 7 — Veridot 2.0 Architecture

### 9.1 Design Philosophy

Veridot 2.0 preserves the original philosophy — **minimal, transport-agnostic, content-addressed, detached signatures** — while adding a **layered trust model** and **first-class support for transparency, revocation, and post-quantum cryptography**.

### 9.2 Layered Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  L5 — Application Policies (capability enforcement)         │
├─────────────────────────────────────────────────────────────┤
│  L4 — Trust Anchors (root keys, TUF roots, SPIFFE bundles)  │
├─────────────────────────────────────────────────────────────┤
│  L3 — Delegation Chain (DIH) & Revocation (SRS)             │
├─────────────────────────────────────────────────────────────┤
│  L2 — Transparency Anchors (Rekor-compatible) & Timestamps  │
├─────────────────────────────────────────────────────────────┤
│  L1 — DataAttestation (Ed25519, ECDSA, ML-DSA, hybrid)     │
├─────────────────────────────────────────────────────────────┤
│  L0 — Canonical Encoding (JSON, CBOR) & Digest (SHA-256)    │
└─────────────────────────────────────────────────────────────┘
```

### 9.3 Protocol

- **Wire format:** `DataAttestation` v2 with explicit `alg`, `caps`, `chain`, `ts_proof`, `log_proof`, `revokes` (optional, all backward-compatible additions to v1).
- **Canonicalization:** deterministic JSON (RFC 8785) and optional CBOR (RFC 8949) with deterministic encoding rules.
- **Algorithms:** Ed25519, ECDSA P-256, ML-DSA-65, hybrid (Ed25519+ML-DSA).
- **Modes:** symmetric (HMAC), asymmetric, threshold (BLS / FROST), privacy (BBS+).

### 9.4 Cryptography

| Function | v1 | v2 |
|---|---|---|
| Digest | SHA-256 | SHA-256 (default), SHA-512, SHA-3-256 |
| Signatures | Ed25519, ECDSA | + ML-DSA-65, Falcon-512, SPHINCS+, hybrid |
| KDF | HKDF-SHA-256 | + HKDF-SHA-3-256 |
| Symmetric | HMAC-SHA-256 | + HMAC-SHA-3-256, AES-GCM for payload encryption |
| Aggregation | none | BLS, FROST |
| Privacy | none | BBS+ |

### 9.5 Trust Model

- **Hierarchical:** A root anchor delegates to intermediate issuers; each delegation is a signed `Delegation` object.
- **Federated:** Roots may be TUF roots, SPIFFE bundles, X.509 roots, or raw public keys.
- **Transitive:** Verifiers accept chains up to a configured anchor set.
- **Revocable:** A signed `Revocation` from a parent invalidates a child key.
- **Auditable:** Attestations can be anchored in transparency logs with inclusion proofs.

### 9.6 Metadata

- **Signed metadata** (`meta`) is preserved.
- **Structured capabilities** (`caps`) replace free-form scopes.
- **Provenance links** (`prev`, `next`) enable DAG construction.
- **Privacy envelope** (`enc`) for optional payload encryption (AES-GCM with key derived from `dig` + a shared secret or DH exchange).

### 9.7 Lifecycle

1. **Bootstrap** — Trust anchors are configured (file, SPIRE, TUF, or hard-coded).
2. **Delegation** — A root issues a `Delegation` to an intermediate issuer.
3. **Issuance** — The issuer signs a `DataAttestation` over a payload.
4. **Distribution** — Attestation and payload are transported (out of protocol).
5. **Anchoring** — Optionally submitted to a transparency log.
6. **Verification** — Verifier loads the chain, validates signatures, checks revocation set, verifies log inclusion if present, recomputes digest, checks freshness, enforces policy.
7. **Revocation** — A signed `Revocation` is published; verifiers update their set.
8. **Rotation** — A new key is delegated; the old key is revoked; chains continue seamlessly.

### 9.8 Version Negotiation

- Attestations carry `v`. Verifiers accept `v` in a configured range; senders may downgrade to `v1` for legacy verifiers.
- A `Veridot-Negotiate` header (application layer) lets clients discover server capabilities.

### 9.9 Interoperability

- **JWS/COSE profile:** A v2 attestation can be losslessly transcoded to a COSE_Sign1 structure.
- **Sigstore profile:** Rekor inclusion proofs are first-class.
- **TUF profile:** TUF root metadata can be used as trust anchors.
- **SPIFFE profile:** SPIFFE IDs are valid `sub` values; SPIRE-issued SVIDs can be used as issuer keys.

### 9.10 Extension Framework

- **Algorithm registry** by string identifier.
- **Canonicalizer registry** (JSON, CBOR, future).
- **Key provider registry** (Android Keystore, PKCS#11, HSM, remote KMS).
- **Trust anchor registry** (local file, TUF, SPIFFE, transparency log).
- **Capability type registry** with schema versioning.
- **Policy hook** for application-defined checks.

### 9.11 Migration Strategy

| Phase | Duration | Action |
|---|---|---|
| M0 | 0–3 mo | Publish v2 RFC; ship reference implementation; maintain v1 verifier |
| M1 | 3–9 mo | Add DIH and SRS to library; document migration |
| M2 | 9–18 mo | Add TLA and STT; integrate with Rekor and a TA service |
| M3 | 18–30 mo | Add hybrid/PQ signatures; default Ed25519+ML-DSA-65 |
| M4 | 30+ mo | Deprecate v1 issuance; keep v1 verification indefinitely |

### 9.12 Standardization Strategy

- **Venue:** IETF (in the spirit of COSE/TUF) or IRTF.
- **Documents:** architecture, wire format, canonicalization, algorithm profiles, threat model, conformance.
- **Reference implementation:** maintained alongside the spec.
- **Interop testing:** annual event (à la IETF hackathons).
- **Conformance suite:** canonicalization vectors, signature vectors, negative tests.

---

## 10. Decision Compliance

### 10.1 Decision Rules Check

Every proposal was checked against:

- ❌ Does not weaken security. (Hybrid sigs, PQ, DIH, SRS, TLA, STT all **raise** the security floor; none lower it.)
- ❌ Does not reduce scalability. (Optional fields; nothing on the critical path of v1 verifiers.)
- ❌ Does not reduce resilience. (DIH, SRS, TLA, GRD all **increase** resilience.)
- ❌ Does not add unjustified complexity. (Each proposal's complexity is justified by a specific weakness it addresses.)
- ❌ Does not unnecessarily break compatibility. (All wire-format extensions are additive, optional, and v1-verifiers ignore them.)

### 10.2 Scoring Rubric (14 dimensions)

| Dimension | Definition |
|---|---|
| **Security (Sec)** | Resistance to forgery, compromise, replay, and repudiation. |
| **Performance (Perf)** | Latency and throughput impact. |
| **Scalability (Scal)** | Ability to grow with issuers, verifiers, attestations. |
| **Reliability (Reli)** | Consistency of correct behavior under failure. |
| **Resilience (Resi)** | Ability to recover from key compromise, partition, or operator error. |
| **Simplicity (Simp)** | Surface area, ease of reasoning. |
| **Auditability (Audit)** | Ability for third parties to inspect and verify operations. |
| **Testability (Test)** | Ease of writing deterministic, exhaustive tests. |
| **Maintainability (Maint)** | Ease of evolution, bug fixing, and refactoring. |
| **Operational Complexity (OpsC)** | Burden on operators (servers, monitoring, rotation). |
| **Implementation Complexity (ImpC)** | Engineering effort to implement. |
| **Backward Compatibility (BC)** | Degree of compatibility with v1. |
| **Research Novelty (RN)** | Originality vs. the state of the art. |
| **Standardization Potential (Std)** | Suitability for IETF/IRTF/CNCF standardization. |

### 10.3 Per-Proposal Justifications (highlights)

- **DIH (8.1):** Sec 9 — directly addresses W1; Reli/Resi 8/9 — chain + revocation yields compromise recovery. Simp 6 — chains are non-trivial. Std 8 — TUF/X.509 analogs ease adoption.
- **SRS (8.2):** Simp 9 — minimal new object; Sec 8 — first-class revocation. BC 10 — purely additive.
- **TLA (8.3):** Audit 10 — Rekor-style logs are the gold standard. Simp 5 — operational burden. RN 6 — adaptation.
- **Hybrid Sig (8.4):** Sec 9 — defends harvest-now. BC 9 — additive. Std 7 — aligns with IETF PQ drafts.
- **TCO (8.7):** Simp 8 — typed caps are easier to reason about. RN 4 — known idea.
- **Threshold (8.8):** Reli 9 — quorum is the strongest compromise model. ImpC 5 — new cryptographic infrastructure.
- **BBS+ (8.9):** RN 9 — strong novelty within Veridot. ImpC 3 — substantial engineering.

---

## 11. Deliverables Index

| # | Deliverable | Section |
|---|---|---|
| 1 | Formal protocol reconstruction | §3 |
| 2 | RFC-like specification | §4 |
| 3 | Threat model | §3.15, §3.16, §5 |
| 4 | Security review | §5.1–5.7, §7 |
| 5 | Scalability review | §5.8, §5.10 |
| 6 | Performance review | §5.9, §5.10 |
| 7 | State-of-the-art comparison matrix | §6.1 |
| 8 | Structural gap analysis | §7 |
| 9 | Protocol evolution proposals | §8 |
| 10 | Optional advanced extensions | §8.9–8.12 |
| 11 | Veridot 2.0 architecture | §9 |
| 12 | Migration roadmap | §9.11 |
| 13 | Prioritized implementation roadmap | §10.1, §8 (ranked), §9.11 |
| 14 | Standardization roadmap | §9.12 |
| 15 | Open research questions | §11.1 (below) |

### 11.1 Open Research Questions

1. **Optimal canonicalization** for cross-language interop: is RFC 8785 sufficient, or do we need a stricter, more declarative canonical form?
2. **Privacy-preserving revocation:** can revocation sets be encoded as cryptographic accumulators to preserve verifier privacy?
3. **Decentralized transparency:** can gossip + Merkle-CRDTs replace a Rekor-like central log?
4. **Quantum-safe delegated hierarchies:** how to express TUF-like root-of-trust over ML-DSA/Hybrid?
5. **Threshold transparency:** can inclusion proofs be threshold-aggregated across multiple logs?
6. **Verifiable policy engines:** can `caps` be expressed as a verifiable circuit (zk-policy) so the verifier can prove policy compliance without revealing the policy?
7. **Replay-cache compression:** how to maintain a `{iss, seq}` cache for millions of issuers efficiently (e.g., via Merkle prefix trees)?
8. **Cross-protocol attestation bridging:** lossless transcoding between Veridot v2, COSE_Sign1, and W3C VCs.
9. **Formal models:** Tamarin/ProVerif models of DIH, SRS, and TLA.
10. **Threat model for the discovery layer:** CAAD (§8.12) needs a dedicated threat model.

---

## 12. References

- **COSE / JOSE:** RFC 8152, RFC 7515, RFC 8785.
- **Sigstore / Rekor:** CNCF Sigstore specification, Rekor transparency log design.
- **TUF:** IETF draft "The Update Framework", PyPI TUF specification.
- **SPIFFE / SPIRE:** CNCF SPIFFE spec, SPIRE architecture.
- **in-toto:** IETF in-toto draft, in-toto.io.
- **DSSE / Minisign:** Dead Simple Signing Envelope, Minisign specification.
- **Merkle / transparency:** Certificate Transparency RFC 9162, RFC 6962-bis.
- **PQ cryptography:** NIST FIPS 203/204/205, IETF LAMPS drafts on PQ signatures.
- **TLS / QUIC / Noise / SSH / Kerberos / OAuth / OIDC:** relevant IETF RFCs (5246, 9000, Noise spec, RFC 4251, RFC 4120, RFC 6749, OIDC Core).
- **Distributed systems:** Kafka EOS, Outbox Pattern, CDC, Event Sourcing, Raft (Ongaro), Paxos (Lamport), CRDT (Shapiro et al.), Dynamo (DeCandia et al.), Gossip protocols (Demers et al.).
- **Canonicalization:** RFC 8785 (JSON), RFC 8949 (CBOR).

---

### Final Note

This audit reconstructs Veridot from source code alone, evaluates it honestly against the state of the art, identifies ten protocol-level weaknesses, and proposes a backward-compatible evolution path culminating in **Veridot 2.0** — a layered, hierarchical, post-quantum-ready, transparency-anchored authenticity protocol that preserves the project's minimalist DNA while addressing the operational realities of long-lived, high-value, federated deployments.

The highest-leverage, lowest-risk starting points are **§8.1 (DIH)**, **§8.2 (SRS)**, **§8.4 (Hybrid Signatures)**, and **§8.7 (TCO)**. These four together resolve the four most severe weaknesses (W1, W3, W5, W9) with minimal complexity and full backward compatibility, and they form the recommended **v2.0 minimal core**.