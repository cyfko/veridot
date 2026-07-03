---
title: "ADR-001: TrustAnchor — Broker Is Transport Only"
description: "Architecture Decision Record introducing the TrustAnchor interface to prevent broker-injection attack vectors."
keywords: [ADR, TrustAnchor, security, broker trust]
---

# ADR-001: TrustAnchor — Broker Is Transport Only

* **Status**: Superseded by [ADR-006](./adr-006-trustroot-sealed.md)
* **Date**: 2026-06-27

## Context

In Veridot V2, verifier instances retrieved cryptographic metadata directly from the broker (Kafka or SQL database). However, this created a critical vulnerability: if an attacker gained write access to the broker (e.g., via compromised Kafka ACLs or SQL injection), they could inject forged public keys or configurations, compromising token integrity.

## Decision

We introduce the `TrustAnchor` sealed interface to establish an out-of-band root of trust:
- The broker is treated as **untrusted transport**.
- Every key announcement must be cryptographically signed by the issuer's long-term key.
- Verifiers resolve the issuer's long-term public key through the `TrustAnchor` (which connects to a secure KMS/HSM or local directory).
- If the signature cannot be validated using the resolved public key, the entry is rejected.

:::note Historical Context
This ADR was written before the Trust Authority Directory (TAD) was designed. In the current architecture, the recommended production backend for TrustRoot is the TAD cluster with `CachingTrustRoot`. See [TAD Architecture](../tad-architecture.md).
:::

## Consequences

- Compressing or compromising the broker no longer allows forging tokens.
- Verifiers must configure a `TrustAnchor` (static or delegated).
- Ephemeral keys are validated against the long-term identity.
