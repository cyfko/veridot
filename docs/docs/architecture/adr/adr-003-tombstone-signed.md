---
title: ADR 003 - Revocation via Signed LIVENESS
description: Decision to handle revocation through LIVENESS(REVOKED) entries instead of broker tombstones.
---

# ADR 003: Revocation via Signed LIVENESS

## Context
When an instance is compromised or shutting down, its authorization must be revoked. Relying on the broker to delete entries (tombstones) violates the "broker-untrusted" principle, as a compromised broker could resurrect deleted data.

## Decision
Revocation is executed by publishing a explicitly signed `LIVENESS` entry with `status = REVOKED` and an incremented version. The monotonic version invariant guarantees that this state cannot be rolled back. 
Alternatively, an authoritative party can issue a `TRUST_REVOCATION (0x0A)` entry.

## Consequences
- Revocation is permanent, verifiable, and cryptographic.
- A hostile broker cannot undo a revocation.
