---
title: ADR 006 - TrustRoot Architecture
description: Decision to use a two-tier CachingTrustRoot.
---

# ADR 006: TrustRoot Architecture

## Context
Resolving an issuer's identity (`CN@hash`) to a public key must not introduce network latency into the sub-millisecond verification hot path.

## Decision
We implement a two-tier `CachingTrustRoot` (L1 in-memory, L2 persistent RocksDB). The cache allows resolution with zero network calls and provides a "stale window" to handle temporary TAAS outages gracefully.

## Consequences
- Verifiers can operate offline.
- Sub-microsecond key resolution.
