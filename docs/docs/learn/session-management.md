---
title: "Chapter 6: Living Sessions — Liveness, Revocation & Quotas"
description: "How Veridot V5 ensures sessions are provably alive, instantly revocable, and capacity-bounded."
sidebar_position: 6
pagination_prev: learn/capabilities
pagination_next: learn/production
---

# Chapter 6: Living Sessions — Liveness, Revocation & Quotas

In V5, identity is ephemeral, but the state of a verification session is highly dynamic. 

A session in Veridot is only considered active if a fresh `LIVENESS` entry asserting an `ACTIVE` status is present in the broker.

## Positive-Proof Liveness

Instead of assuming a token is valid until it expires or hits a blocklist, Veridot requires a continuous assertion of liveness. 
The instance that issued the session regularly publishes `LIVENESS(ACTIVE)` to the broker. 

If the verification cache does not hold an unexpired `ACTIVE` entry, the session is treated as invalid. There is no distinction between "absent" and "invalid".

## Monotonic Revocation

If an instance is shutting down, or if a user logs out, the instance publishes a `LIVENESS(REVOKED)` entry.

Crucially, every Veridot entry includes a **monotonic version** number. The `REVOKED` entry will have a version number strictly greater than the last `ACTIVE` entry. 

Because the protocol strictly enforces version monotonicity:
- A malicious broker cannot replay an old `ACTIVE` entry to "un-revoke" a session.
- The `REVOKED` state is final and permanent.

## Capacity Management

What if you want to limit a user to exactly 3 concurrent sessions?
Veridot V5 handles this via **Fence Tokens**. These are signed, monotonically increasing counters scoped to a single `(scope)`. 

When a new session is created, a fence token is incremented. If the count exceeds the quota, older sessions are fenced out, enforcing strict capacity bounds in a distributed, broker-untrusted environment.
