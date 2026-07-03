---
title: "ADR-008: Binary TLV Wire Format"
description: "Architecture Decision Record replacing text-based payloads with a self-describing binary TLV format."
keywords: [ADR, binary, wire format, TLV, serialization]
---

# ADR-008: Binary TLV Wire Format

* **Status**: Accepted
* **Date**: 2026-06-28

## Context

Veridot V3 used a text-based format (`version:groupId:sequenceId|key:value`) for broker envelopes. This resulted in significant Base64 encoding/decoding overhead, string parsing vulnerabilities, and bloated message sizes.

## Decision

We migrate to a **canonical binary TLV (Type-Length-Value) wire format** in Protocol V4:
- Fields are strictly laid out with big-endian integer sizes (u8, u16, u32, u64).
- Payloads are nested using TLV blocks.
- Offsets and bounds are validated structurally before any cryptographic verification.

## Consequences

- Dramatically reduced memory allocations during serialization.
- Hardened against payload parsing injection vectors.
- Envelope size reduced by 30-40% on average.
