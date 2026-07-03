---
title: "ADR-007: FENCE Tokens for Capacity Mutations"
description: "Architecture Decision Record introducing FENCE entries to prevent race conditions during session quota changes."
keywords: [ADR, FENCE, concurrency, capacity]
---

# ADR-007: FENCE Tokens for Capacity Mutations

* **Status**: Accepted
* **Date**: 2026-06-28

## Context

When multiple instances of a signing service concurrently mutate the session registry (e.g., evicting sessions to stay under `maxSessions` quota), they might read stale states from the broker and perform conflicting actions, exceeding limits or causing unnecessary churn.

## Decision

We introduce **FENCE entries** in Protocol V4:
- A processor must publish a signed `FENCE` entry with a strictly-increasing counter to acquire ownership of a scope's capacity management.
- Verifier nodes reject any session mutation from an issuer whose fence counter is lower than the active fence watermark for that scope.
- Conflicting updates trigger a `V4301 FENCE_TOKEN_STALE` rejection, forcing the losing instance to re-sync.

## Consequences

- Prevents concurrency races and split-brain decisions.
- Enforces strict consistency on capacity mutations.
