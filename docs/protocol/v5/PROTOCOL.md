# Veridot Protocol V5 — Overview

> **Full specification**: [`PROTOCOL_V5.md`](../../../PROTOCOL_V5.md)
>
> This document is a concise overview for developers. For normative details,
> wire format byte offsets, and processing rules, refer to the full spec.

---

## What Changed in V5

Protocol V5 is a **breaking rewrite** of V4. There is no backward compatibility layer.

### Wire Format (§3)

| Change | V4 | V5 |
|--------|----|----|
| Protocol version | `0x04` | `0x05` |
| Flags field | `u8` (1 byte) | `u16 BE` (2 bytes) |
| Header overhead | N bytes | N+1 bytes |
| All offsets after flags | — | Shifted +1 byte |

The V5 flags register provides 16 bits (12 reserved for future use):

| Bit | Name | Description |
|-----|------|-------------|
| 0 | `COMPACT_SIG` | Fixed-length signature (Ed25519-based) |
| 1 | `HYBRID_SIG` | Classical + PQ composite signature |
| 2 | `DETACHED_PAYLOAD` | Reserved — payload stored externally |
| 3 | `COMPRESSED` | Reserved — compressed payload |
| 4–15 | *(reserved)* | Must be zero |

### Identity Model (§5)

V5 introduces **instance-scoped identity**: each running instance generates one keypair, computes a deterministic subject, and registers at the TAAS.

**Subject format**: `CN@base64url(SHA-256(publicKey.getEncoded()))[0:32]`

- **CN**: Human-readable common name (e.g., `orders-service`)
- **Hash**: 32 base64url characters (192 bits) of the SHA-256 of the X.509-encoded public key
- **Example**: `orders-service@xR7kL9mN2pQ4sT6uV8wY0zA3bC5dE7fG`

Key principles:
- **One key per instance** — no key rotation within a running instance
- **Deterministic** — same key always produces the same subject
- **Globally unique** — hash provides collision resistance

### Entry Types (§4)

| Code | Name | V5 Status |
|------|------|-----------|
| `0x01` | _(reserved)_ | KEY_EPOCH **eliminated** — rejected with V5002 |
| `0x02` | `CAPABILITY` | Unchanged + subjectPattern tag |
| `0x03` | `CONFIG` | +maxInstanceLifetime, +attestationPlugin tags |
| `0x04` | `LIVENESS` | Unchanged |
| `0x05` | `FENCE` | +anchoredAt tag |
| `0x06` | `SNAPSHOT_MARKER` | Unchanged |
| `0x07` | `SECURE_PAYLOAD` | Unchanged |
| `0x08` | `SIGNED_DATA` | **New** — native mode signed payload |
| `0x09` | `AUDIT_ANCHOR` | **New** — audit trail anchoring |
| `0x0A` | `TRUST_REVOCATION` | **New** — trust entry revocation |

### Distribution Modes (§7)

| Mode | Token | Storage | Description |
|------|-------|---------|-------------|
| **DIRECT** | JWT string | None | Self-contained JWT with `kid=subject` |
| **NATIVE** | `8:<scope>:<key>` | `SIGNED_DATA` in broker | No JWT overhead |
| **PRIVATE** | `7:<scope>:<key>` | `SECURE_PAYLOAD` in broker | E2EE with recipients |

> **INDIRECT mode is removed.** DIRECT and NATIVE together cover all use cases.

---

## TAAS — Trust Authority & Attestation Service (§15)

TAAS replaces the old TAD (Trust Authority & Directory). It is a **Raft-replicated cluster** that:

1. **Stores** public keys and trust entries
2. **Verifies attestation proofs** at registration time (K8s PSAT, GCP IIT, TPM, or `none` for dev)
3. **Serves** as the backing implementation of `TrustRoot` for distributed deployments
4. **Computes** periodic state digests for transparency verification

### Registration Flow

```
Instance → POST /v2/trust-entries {entry, attestationProof}
TAAS → Raft consensus + attestation verification
TAAS → 201 {subject, version}
```

### Attestation SPI

Pluggable attestation verifiers:
- `k8s` — Kubernetes projected service account token (PSAT)
- `gcp` — Google Cloud Instance Identity Token (IIT)
- `tpm` — TPM 2.0 attestation key quote
- `none` — Dev/test only (requires `VDOT_ATTESTATION_SKIP=true`)

---

## Post-Quantum Support (§6)

V5 adds three new signature algorithms:

| Code | Algorithm | Type | JWT `alg` |
|------|-----------|------|-----------|
| `0x05` | Ed25519+ML-DSA-65 | Hybrid | `EdDSA+ML-DSA-65` |
| `0x06` | ECDSA-P256+ML-DSA-65 | Hybrid | `ES256+ML-DSA-65` |
| `0x07` | ML-DSA-65 | Standalone PQ | `ML-DSA-65` |

Code `0x08` is reserved for FROST threshold signatures (future RFC).

Hybrid signatures provide defense-in-depth: if either the classical or PQ scheme is broken, the other still protects the envelope.

---

## State Transparency (§18)

TAAS periodically computes a **Sparse Merkle Tree** digest of all trust entries. Instances verify this digest to detect:

- **Broker omissions** — entries missing from local view vs. TAAS digest
- **Liveness gaps** — missing heartbeats from known peers
- **Capability version mismatches** — stale authorization state

Error codes: `V5701`–`V5704`.

---

## Error Code Categories

| Range | Category |
|-------|----------|
| V50xx | Envelope errors |
| V51xx | Trust errors |
| V52xx | Token lifecycle |
| V53xx | Watermark/Capacity |
| V54xx | Capability |
| V55xx | Encryption |
| V56xx | PQ Encryption |
| V57xx | State Transparency |
| V58xx | Infrastructure |

---

## Quick Reference

- **Full spec**: [`PROTOCOL_V5.md`](../../../PROTOCOL_V5.md)
- **Java implementation**: [`java/`](../../../java/)
- **Java core reference**: [`java/veridot-core/README.md`](../../../java/veridot-core/README.md)
