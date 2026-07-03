---
title: "ADR-006: TrustRoot Sealed Interface"
description: "Architecture Decision Record changing the TrustAnchor interface into the sealed TrustRoot interface."
keywords: [ADR, TrustRoot, sealed interface, capability]
---

# ADR-006: TrustRoot Sealed Interface

* **Status**: Accepted
* **Date**: 2026-06-28

## Context

In Veridot V3, the `TrustAnchor` callback was responsible for both *identity resolution* (fetching the public key) and *scope authorization* (deciding if the issuer is allowed to publish for a scope). This mixed concerns and forced synchronous network lookups to the KMS during scope checks on the hot path.

## Decision

We replace `TrustAnchor` with a sealed `TrustRoot` interface:
- Permits only `PublicKeyTrustRoot` and `DelegatedTrustRoot`.
- The `TrustRoot` is **restricted to identity resolution** only.
- Scope authorization is offloaded to structural `CAPABILITY` envelopes published in the broker.
- Verifiers build a local capability tree and perform scope pattern matching in-memory.

## Consequences

- Clean separation of concerns.
- Hot path verification performs zero external KMS calls, relying purely on local, cached capability records.
