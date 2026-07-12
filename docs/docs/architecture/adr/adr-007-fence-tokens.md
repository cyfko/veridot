---
title: ADR 007 - Fence Tokens
description: Decision to use FENCE entries for distributed concurrency.
---

# ADR 007: Concurrency Fencing Tokens

## Context
Multiple concurrent instances may try to admit sessions to a capacity-constrained scope. Without strong broker consistency, this creates a race condition.

## Decision
We introduce `FENCE` (0x05) singleton entries. When a `FENCE` entry is present in a scope, only the instance whose subject matches the `issuer` field of the envelope may write to that scope.

## Consequences
- Prevents split-brain capacity mutations.
- Works securely even on eventually-consistent brokers.
