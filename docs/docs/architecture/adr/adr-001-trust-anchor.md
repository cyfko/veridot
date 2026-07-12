---
title: ADR 001 - TAAS as Trust Anchor
description: Decision to use Trust Authority and Attestation Service (TAAS) for distributing public keys.
---

# ADR 001: TAAS as Trust Anchor

## Context
Veridot operates in a broker-untrusted environment. Verifiers need a reliable way to resolve the long-term identity (`issuer`) to a public key to verify signatures without sharing secrets. Cloud KMS systems violate the "Single Key Per Instance" and offline-verification requirements.

## Decision
We introduce the **Trust Authority & Attestation Service (TAAS)**. Instances generate their own keys and register their public keys at TAAS by providing a verifiable attestation proof (e.g., TPM quote). TAAS replicates these entries via Raft consensus. Verifiers cache these keys locally (CachingTrustRoot).

## Consequences
- Single key per instance constraint is achievable.
- Total decoupling of trust from the transport broker.
- Sub-millisecond verification via local caching of TAAS data.
