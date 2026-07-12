---
title: ADR 008 - Binary TLV Envelope
description: Decision to use a custom Binary TLV format instead of JSON/CBOR.
---

# ADR 008: Binary TLV Envelope Format

## Context
Veridot V5 distributes signed objects (`SIGNED_DATA`, `CAPABILITY`, etc.). We need a deterministic serialization format to compute signatures correctly. Formats like JSON, CBOR, and Protocol Buffers either have multiple valid encodings or require complex parsers, making canonicalization difficult and brittle.

## Decision
We adopt a custom binary envelope format with a fixed-offset header and a Tag-Length-Value (TLV) payload.
- Canonical signing bytes are simply the raw byte span `[0 .. sigAlg_offset - 1]`.
- Tags are `u8`, lengths are `u16` big-endian, values are raw bytes.

## Consequences
- O(n) parsing with zero external codec dependencies.
- Perfect deterministic canonicalization (identical data always produces identical bytes).
- Extremely compact wire size.
