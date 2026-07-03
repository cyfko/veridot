---
title: "ADR-005: Monotonic Version Invariant"
description: "Architecture Decision Record replacing timestamp-based ordering with a strictly-increasing version counter."
keywords: [ADR, versioning, consistency, clock drift]
---

# ADR-005: Monotonic Version Invariant

* **Status**: Accepted
* **Date**: 2026-06-28

## Context

Veridot V3 used wall-clock timestamps for entry conflict resolution. However, in distributed systems, clock drift is inevitable. An attacker could exploit clock drift to replay older states or block updates by publishing an entry with a far-future timestamp.

## Decision

We introduce a **monotonic version counter** as the sole basis for ordering entries:
- Every entry carries a 64-bit Big-Endian unsigned integer `version`.
- Verifiers store the highest version they have processed as a local watermark.
- Incoming entries are processed **only** if their `version` is strictly greater than the stored watermark.
- Timestamps are relegated to advisory roles only (e.g. debugging, metrics).

## Consequences

- Immune to system clock drift or timestamp manipulation.
- Replays of stale versions are instantly rejected.
