---
title: Protocol V3 (Archived)
description: Overview of Veridot Protocol V3 — the text-based predecessor to V4, now archived and superseded.
keywords: [veridot, protocol, v3, archived, legacy, text format, Base64url]
sidebar_position: 1
---

# Protocol V3 (Archived)

:::warning Superseded
Protocol V3 has been **superseded by [Protocol V4](../v4/index.md)**. V3 is retained for reference only. All new deployments MUST use V4.
:::

## Overview

Veridot Protocol V3 was the original text-based message format for distributing cryptographic verification metadata across nodes. It was created on 2026-04-10 and served as the foundation for the Veridot distributed token verification system before being replaced by the binary V4 format.

### Key Characteristics

| Aspect | V3 |
|---|---|
| **Wire format** | Text: `version:groupId:sequenceId\|metadata` |
| **Value encoding** | Base64url (RFC 4648 §5) without padding |
| **Message types** | 3: Normal, Configuration, Revocation |
| **Revocation model** | Tombstone messages (`__REVOKE__` reserved sequence) |
| **Authorization** | TrustAnchor + `sig` field on all message types |
| **State resolution** | Latest-timestamp-wins conflict resolution |
| **Capacity management** | Eventually consistent (race-prone under concurrent processors) |
| **Protocol version field** | ASCII `3` as first character |

### Message Format

```
3:<groupId>:<sequenceId>|<key>:<base64url-value>,<key>:<base64url-value>,...
```

**Example** (JWT verification metadata):
```
3:user123:session001|alg:cnNh,pk:TUlJQ...,ts:MTcwNjcxMjAwMA,ttl:MzYwMA,sid:YXV0aC1zdmM,sig:U0hBMjU2d2l0aFJTQQ
```

### Reserved Sequences

| Sequence | Purpose |
|---|---|
| `__CONFIG__` | Configuration messages |
| `__REVOKE__` | Revocation tombstones |
| `__ALL__` | Universal target for config/revocation |

### Error Codes (V3)

| Code | Name |
|---|---|
| `V3001` | `INVALID_SYNTAX` |
| `V3002` | `INVALID_VERSION` |
| `V3003` | `INVALID_IDENTIFIER` |
| `V3004` | `INVALID_METADATA` |
| `V3005` | `MISSING_REQUIRED_PROPERTY` |
| `V3006` | `INVALID_TIMESTAMP` |
| `V3007` | `INVALID_SIGNATURE` |
| `V3008` | `CONFIGURATION_CONFLICT` |
| `V3009` | `SESSION_CAPACITY_EXCEEDED` |
| `V3010` | `REVOCATION_FAILED` |
| `V3011` | `TRUST_ANCHOR_UNAVAILABLE` |
| `V3012` | `SIGNATURE_REJECTED` |

## Why V4 Replaced V3

V4 addresses several fundamental limitations of V3:

| Limitation in V3 | Solution in V4 |
|---|---|
| Text-based format with Base64url overhead | Binary TLV envelope — compact and unambiguous |
| 3 message types (normal, config, revocation) | 7 entry types with dedicated semantics |
| Tombstone revocation (absence = valid) | Positive liveness attestation (absence = rejected) |
| Timestamp-based conflict resolution | Monotonic version ordering |
| Eventually consistent capacity management | Fence-token serialized mutations |
| No structural authorization model | Cryptographic capability chain with delegation |
| No E2EE payload support | SECURE_PAYLOAD with hybrid encryption |

## Full Specification

The complete V3 specification is available in the repository:

📄 [`PROTOCOL_V3.md`](https://github.com/cyfko/veridot/blob/main/PROTOCOL_V3.md)

## Migration

For guidance on migrating from V3 to V4, see the [Protocol V4 overview](../v4/index.md#evolution-from-v3).

:::tip Coexistence
Multiple protocol versions MAY coexist on the same broker. V3 messages start with ASCII `3:`, while V4 entries start with the binary magic `0x56 0x44`. Version detection is automatic.
:::
