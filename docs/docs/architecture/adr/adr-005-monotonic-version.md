---
title: ADR 005 - Monotonic Versions
description: Decision to enforce strictly increasing versions for all entry types.
---

# ADR 005: Monotonic Versions for Idempotency

## Context
Brokers may deliver messages out of order or re-deliver them. Furthermore, attackers with broker access might attempt to replay old, valid states (rollback attack).

## Decision
Every `EntryId` must include a strictly increasing 64-bit `version`. Verifiers maintain a local high-watermark. An incoming entry is only accepted if its version is strictly greater than the recorded watermark.

## Consequences
- Natural idempotency against duplicate deliveries.
- Structural immunity to rollback/replay attacks.
- Enables safe snapshot reconciliation.
