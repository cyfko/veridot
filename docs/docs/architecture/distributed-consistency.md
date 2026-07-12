---
title: Distributed Consistency
description: Monotonic version invariants, idempotent application, non-rollback guarantees, snapshot reconciliation, and fencing for capacity mutations.
keywords: [consistency, monotonic, version, watermark, fencing, reconciliation, snapshot, idempotent]
sidebar_position: 4
---

# Distributed Consistency

Veridot Protocol V5 achieves strong consistency guarantees without requiring a strongly consistent broker. The protocol's state model is built on invariants—monotonic versions, idempotent application, non-rollback watermarks, and periodic reconciliation—plus a fencing mechanism.

## Invariant 1: Monotonic Version

For every `EntryId` (the tuple `(scope, entryType, key)`), a conforming processor maintains the highest `version` it has accepted. An incoming entry is accepted **only if** its `version` is strictly greater than the currently recorded value.

- The initial recorded version for an unseen `EntryId` is `0`
- The minimum valid `version` for any entry is `1`
- This applies uniformly across all entry types (`SIGNED_DATA`, `LIVENESS`, `CAPABILITY`, `CONFIG`, `FENCE`, etc.)

## Invariant 2: Idempotent Application

Applying an already-accepted entry a second time has **no additional effect** beyond the first application.

## Invariant 3: Non-Rollback

The highest recorded `version` for an `EntryId` **never decreases**. This holds independent of broker behavior:

| Scenario | Broker state | Processor watermark | Outcome |
|---|---|:---:|---|
| Broker overwrite by attacker | Entry v=3 replaces v=5 | 5 | ❌ Rejected (v=3 < watermark 5) |
| Broker data loss | Entry deleted | 5 | ❌ No entry to read; session not valid |

## Reconciliation via Snapshot (SNAPSHOT_MARKER)

A processor periodically retrieves a full snapshot of each scope and reconciles its local watermarks.

A `SNAPSHOT_MARKER` (0x06) entry marks a reconciliation boundary in a scope. It is a singleton with an empty key and an empty payload. It indicates that all entries created before this marker's timestamp have been reconciled and MAY be compacted.

## Fencing for Capacity Mutations

To prevent two concurrent processors from modifying a scope unsafely, Veridot uses **FENCE (0x05)** entries.

- A `FENCE` entry provides concurrency fencing within a scope. It is a singleton and has an empty payload and key.
- When a `FENCE` entry is present in a scope, only the instance whose subject matches the `issuer` field of the envelope MAY write to that scope.
- It expires after `fenceTimeoutSeconds`.

## Consistency Properties Summary

| Property | Guarantee |
|---|---|
| **Monotonic version** | State only moves forward; stale/duplicate entries silently rejected |
| **Idempotent application** | Re-applying an entry is a no-op |
| **Non-rollback** | Watermark never decreases |
| **Strong ordering** | Fence singleton provide mutual exclusion for mutations |
