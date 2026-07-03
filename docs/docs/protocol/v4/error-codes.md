---
title: Error Codes
description: "Veridot Protocol V4 Appendix B — Complete reference of all error codes (V4001–V4401) with names and descriptions."
keywords: [veridot, protocol, v4, error codes, V4001, V4101, V4201, V4301, V4401, validation, troubleshooting]
sidebar_position: 14
---

# Error Codes

This page provides the complete reference of all Veridot Protocol V4 error codes. Every rejection by a conforming processor MUST be logged with the corresponding error code and the EntryId involved.

:::info Specification reference
This page corresponds to **Appendix B** of the Veridot Protocol V4 specification.
:::

## Error Code Ranges

| Range | Category | Description |
|---|---|---|
| `V4001`–`V4007` | Envelope errors | Structural violations in the binary envelope |
| `V4101`–`V4104` | Trust & authorization errors | TrustRoot resolution, signature, and capability failures |
| `V4201`–`V4205` | State & validation errors | Version ordering, liveness, temporal, and cryptographic issues |
| `V4301`–`V4302` | Capacity errors | Fence token and session capacity violations |
| `V4401` | Transport errors | Broker communication failures |

## Envelope Errors

These errors indicate structural violations in the binary [envelope](./wire-format.md).

| Code | Name | Description | Specification |
|---|---|---|---|
| `V4001` | `INVALID_ENVELOPE` | Magic (`0x56 0x44`) or protocol version (`0x04`) mismatch. The processor MUST NOT attempt to parse the remainder of the envelope. | [§3.2](./wire-format.md#field-constraints) |
| `V4002` | `UNREGISTERED_ENTRY_TYPE` | `entryType` is not present in the [entry type registry](./entry-types.md). Codes `0x08`–`0xFF` are reserved and MUST trigger this error. | [§4](./entry-types.md) |
| `V4003` | `INVALID_IDENTIFIER_LENGTH` | `scopeLen`, `keyLen`, or `issuerLen` is outside its permitted bounds. `scopeLen`: 1–4096, `keyLen`: 0–4096, `issuerLen`: 1–4096. | [§3.2](./wire-format.md#field-constraints) |
| `V4004` | `INVALID_PAYLOAD_LENGTH` | `payloadLen` is outside its permitted bounds (0–65536). | [§3.2](./wire-format.md#field-constraints) |
| `V4005` | `RESERVED_FLAG_SET` | A reserved bit in `flags` (bits 1–7) was set, **or** `flags` bit 0 (`COMPACT_SIG`) is inconsistent with `sigAlg`. Bit 0 MUST be `1` iff `sigAlg = 0x04` (Ed25519). | [§3.1](./wire-format.md#envelope-structure) |
| `V4006` | `INVALID_SCOPE_GRAMMAR` | `scope` does not match the required grammar: `"group:" identifier`, `"site:" identifier`, or `"global"`. | [§3.5](./wire-format.md#identifier-constraints) |
| `V4007` | `MALFORMED_PAYLOAD` | Payload TLV does not conform to the entry type's field table. Triggers include: tag `0x00` encountered, required field missing, or duplicate tag. | [§4.1](./entry-types.md#tlv-payload-encoding) |

## Trust & Authorization Errors

These errors relate to TrustRoot resolution, signature verification, and [capability](./capability.md) chain validation.

| Code | Name | Description | Specification |
|---|---|---|---|
| `V4101` | `TRUST_RESOLUTION_FAILED` | `issuer` could not be resolved through the TrustRoot, **or** `signature` verification failed over the [canonical bytes](./wire-format.md#canonical-signing-bytes). | [§5.4](./key-epoch.md#verification-process) step 4 |
| `V4102` | `CAPABILITY_NOT_FOUND` | No valid `CAPABILITY` entry authorizes the `issuer` for the target `scope`. | [§6.4](./capability.md#verification-process) |
| `V4103` | `CAPABILITY_EXPIRED` | A `CAPABILITY` entry was found but `now ≥ validUntil`. | [§6.4](./capability.md#verification-process) |
| `V4104` | `DELEGATION_DEPTH_EXCEEDED` | The capability delegation chain exceeds `maxDelegationDepth`. | [§6.4](./capability.md#delegation-chain) |

## State & Validation Errors

These errors relate to monotonic version ordering, liveness attestation, temporal validity, and cryptographic operations.

| Code | Name | Description | Specification |
|---|---|---|---|
| `V4201` | `STALE_VERSION` | Incoming entry's `version` is not strictly greater than the recorded watermark for that EntryId, **or** `version = 0`. The minimum valid version is `1`. | §11.1 |
| `V4202` | `LIVENESS_NOT_ESTABLISHED` | No fresh, valid `ACTIVE` `LIVENESS` entry is available for the target session `(scope, key)`. This covers: no entry found, entry expired, entry failed trust validation, or status is `REVOKED`. | [§8.3](./liveness.md#default-deny-semantics) |
| `V4203` | `KEY_EPOCH_EXPIRED` | `now` is outside the `[validFrom, validUntil)` window for the referenced `KEY_EPOCH`. The 5-minute clock-drift tolerance applies to `validFrom`. | [§5.3](./key-epoch.md#temporal-validity) |
| `V4204` | `SIGALG_KEY_MISMATCH` | `sigAlg` value is unknown, **or** is inconsistent with the key type resolved for `issuer` via the TrustRoot. | §14.1 |
| `V4205` | `DECRYPTION_FAILED` | Cryptographic decryption of `encryptedKey` or `data` failed in a `SECURE_PAYLOAD` entry. | §12.4 |

## Capacity Errors

These errors relate to [fence token](./entry-types.md#fence-0x05) ordering and session capacity management.

| Code | Name | Description | Specification |
|---|---|---|---|
| `V4301` | `FENCE_TOKEN_STALE` | `fenceCounter` is not strictly greater than the recorded watermark for the scope. A capacity-affecting mutation without a valid fence token MUST be rejected. | §9.4 |
| `V4302` | `CAPACITY_EXCEEDED` | Session limit (`max`) reached under eviction policy `pol = REJECT` (`0x04`). The processor MUST refuse to create the new session. | §10.2 |

## Transport Errors

| Code | Name | Description | Specification |
|---|---|---|---|
| `V4401` | `TRANSPORT_UNAVAILABLE` | Broker read or write failure. Treated as **rejection** per [§8.3](./liveness.md#default-deny-semantics) / §14.4 for verification outcomes. MUST be logged separately from definitive rejections per §13.4. | §13.4, §14.4 |

:::warning Transport ≠ pass
A transport error MUST NOT receive different treatment from a definitive rejection in the verification outcome. The processor fails **closed**: broker unavailability = session not valid.
:::

## Error Code Quick Reference

| Code | Name |
|---|---|
| `V4001` | `INVALID_ENVELOPE` |
| `V4002` | `UNREGISTERED_ENTRY_TYPE` |
| `V4003` | `INVALID_IDENTIFIER_LENGTH` |
| `V4004` | `INVALID_PAYLOAD_LENGTH` |
| `V4005` | `RESERVED_FLAG_SET` |
| `V4006` | `INVALID_SCOPE_GRAMMAR` |
| `V4007` | `MALFORMED_PAYLOAD` |
| `V4101` | `TRUST_RESOLUTION_FAILED` |
| `V4102` | `CAPABILITY_NOT_FOUND` |
| `V4103` | `CAPABILITY_EXPIRED` |
| `V4104` | `DELEGATION_DEPTH_EXCEEDED` |
| `V4201` | `STALE_VERSION` |
| `V4202` | `LIVENESS_NOT_ESTABLISHED` |
| `V4203` | `KEY_EPOCH_EXPIRED` |
| `V4204` | `SIGALG_KEY_MISMATCH` |
| `V4205` | `DECRYPTION_FAILED` |
| `V4301` | `FENCE_TOKEN_STALE` |
| `V4302` | `CAPACITY_EXCEEDED` |
| `V4401` | `TRANSPORT_UNAVAILABLE` |

## See Also

- [Wire Format](./wire-format.md) — envelope structure (V4001–V4007)
- [Entry Types](./entry-types.md) — entry type registry (V4002, V4007)
- [Key Epoch](./key-epoch.md) — verification process (V4101, V4203)
- [Capability](./capability.md) — authorization errors (V4102–V4104)
- [Liveness](./liveness.md) — default-deny semantics (V4202)
