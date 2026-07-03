---
title: Architecture Decision Records
description: Index of Architecture Decision Records (ADRs) documenting key design decisions in the Veridot protocol.
keywords: [ADR, architecture decision records, design decisions, rationale]
sidebar_position: 9
---

# Architecture Decision Records

Architecture Decision Records (ADRs) capture the significant design decisions made during the development of Veridot. Each ADR documents the context, decision, and consequences of a specific architectural choice.

## ADR Index

| # | Title | Status | Date | Summary |
|:---:|---|:---:|:---:|---|
| ADR-001 | [TrustAnchor: Broker Is Transport Only](/veridot/docs/architecture/adr/adr-001-trust-anchor) | **Superseded by ADR-006** | 2026-06-27 | Introduced the `TrustAnchor` sealed interface to prevent broker-injection attacks. Any node with broker write access but without the long-term private key cannot produce accepted entries. |
| ADR-002 | [RSA-3072 Ephemeral Key Size](/veridot/docs/architecture/adr/adr-002-rsa-3072) | **Accepted** | 2026-06-27 | Increased ephemeral RSA key size from 2048 to 3072 bits to align with NIST post-2030 recommendations, avoiding a future breaking change. |
| ADR-003 | [Signed Revocation Tombstones](/veridot/docs/architecture/adr/adr-003-tombstone-signed) | **Superseded by ADR-007** | 2026-06-27 | Required long-term RSA signature on every `__REVOKE__` message to prevent unauthorized revocation injection. Combined with latest-timestamp-wins to make replay harmless. |
| ADR-004 | [Positive-Proof Liveness Model](/veridot/docs/architecture/adr/adr-004-liveness-positive-proof) | **Accepted** | 2026-06-28 | Replaced the V3 "absence of revocation = valid" model with positive-proof `LIVENESS` attestations. A session is valid only when a fresh, signed `ACTIVE` entry exists. Default is rejection. |
| ADR-005 | [Monotonic Version Invariant](/veridot/docs/architecture/adr/adr-005-monotonic-version) | **Accepted** | 2026-06-28 | Adopted a strictly-increasing 64-bit version counter per EntryId as the sole ordering mechanism, replacing timestamp-based conflict resolution. Independent of wall-clock time. |
| ADR-006 | [TrustRoot Sealed Interface (V4)](/veridot/docs/architecture/adr/adr-006-trustroot-sealed) | **Accepted** | 2026-06-28 | Evolved `TrustAnchor` into `TrustRoot` — a sealed interface that handles identity resolution only. Scope authorization moved to structural `CAPABILITY` entries, eliminating the `isAuthorizedForScope` callback. |
| ADR-007 | [FENCE Tokens for Capacity Mutations](/veridot/docs/architecture/adr/adr-007-fence-tokens) | **Accepted** | 2026-06-28 | Introduced `FENCE` entries with strictly-increasing counters to totally order capacity-affecting mutations across concurrent processors, replacing the V3 eventual-consistency approach. |
| ADR-008 | [Binary TLV Wire Format (V4)](/veridot/docs/architecture/adr/adr-008-binary-tlv) | **Accepted** | 2026-06-28 | Replaced the V3 text-based format (`3:gid:sid|key:val`) with a self-describing binary TLV envelope. Eliminates Base64 overhead, enables unambiguous length-field validation, and reduces message size. |

## Status Definitions

| Status | Meaning |
|---|---|
| **Accepted** | Decision is active and guides current implementation |
| **Superseded** | Decision has been replaced by a later ADR (noted in the link) |
| **Deprecated** | Decision is being phased out but not yet fully replaced |
| **Proposed** | Decision is under review and not yet finalized |

## Contributing ADRs

When adding a new ADR:

1. Use the next sequential number (e.g., `adr-009-*.md`)
2. Follow the template structure: **Context → Decision → Consequences**
3. Reference the relevant protocol specification sections
4. Update this index page
5. If the ADR supersedes a previous one, update the older ADR's status

## Related Pages

- [Architecture Overview](../overview.md) — system-level view of how these decisions manifest
- [Protocol Evolution](../protocol-evolution.md) — timeline of protocol changes that motivated these ADRs
- [Security Model](../security-model.md) — security-focused consequences of these decisions
