---
title: ADR 004 - Positive Proof of Liveness
description: Decision to require explicit LIVENESS(ACTIVE) entries for session validity.
---

# ADR 004: Positive Proof of Liveness

## Context
In a distributed system, distinguishing between a healthy session and one that has been silently lost (due to network partition or instance death) is difficult. Fail-open semantics (assuming a session is valid unless explicitly revoked) is a security risk.

## Decision
We enforce a **positive proof of liveness**. A session is considered valid if and only if a fresh, signed `LIVENESS` entry with status `ACTIVE` exists and its `validUntil` is in the future.

## Consequences
- Fail-closed semantics: network partitions or instance crashes naturally lead to session expiration and rejection.
- Requires signers to periodically publish `LIVENESS` renewals.
