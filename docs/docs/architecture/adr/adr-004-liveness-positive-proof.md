---
title: "ADR-004: Positive-Proof Liveness Model"
description: "Architecture Decision Record changing the session validation design to a positive-proof liveness model."
keywords: [ADR, liveness, security, default-deny]
---

# ADR-004: Positive-Proof Liveness Model

* **Status**: Accepted
* **Date**: 2026-06-28

## Context

Veridot V3 assumed a session was active unless a revocation entry was found in the cache (default-allow). If a verifier failed to receive a revocation due to a network partition, database lag, or broker drop, it would continue to accept revoked tokens indefinitely, violating security requirements.

## Decision

We adopt a **positive-proof liveness model** (default-deny) in Protocol V4:
- A session is considered active **only** if a valid `LIVENESS` entry with status `ACTIVE` exists and its temporal validity window is in the future.
- Ephemeral keys require a matching, unexpired `LIVENESS(ACTIVE)` attestation.
- In the absence of metadata or if the liveness entry is expired, verification default-denies.

## Consequences

- Network partitions or delivery failures fail-closed.
- Signers must periodically publish fresh `LIVENESS(ACTIVE)` heartbeats to renew sessions before they expire.
