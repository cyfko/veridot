---
title: veridot-trustroots Overview
description: Overview of the veridot-trustroots modules and the Trust Authority & Attestation Service (TAAS) for V5.
keywords: [veridot-trustroots, TAAS, TrustRoot, attestation, veridot, V5]
sidebar_position: 1
---

# Trust Authority & Attestation Service (TAAS)

The `veridot-trustroots` repository houses the implementations for the **Trust Authority & Attestation Service (TAAS)**, the definitive source of cryptographic trust in Veridot Protocol V5.

## Attestation-First Trust in V5

In V5, identity is strictly bound to the instance lifecycle, and the `KEY_EPOCH` concept has been completely removed. Instead, the protocol relies on an "Attestation-First" principle:

- Every instance generates **exactly one keypair**.
- The instance constructs a **Trust Entry** and provides an **attestation proof** (e.g., TPM quote, Kubernetes SAT).
- It registers with the TAAS.
- The TAAS cluster reaches Raft consensus, verifies the proof, and stores the public key against the subject identity format `CN@hash(pk)`.
- Verification nodes query the TAAS via the `TrustRoot` interface to resolve public keys dynamically.

## Module Structure

The project is split into several modules:

- **[taas-core](./core.md)**: Shared data models, Raft consensus abstractions, and the Trust Entry state machine.
- **[taas-api](./api.md)**: The standard Java definitions and REST interfaces for the TAAS.
- **[taas-client](./taas-client.md)**: The client SDK providing the `TaasTrustRootProvider` implementation for `veridot-core`.
- **[taas-server](./taas-server.md)**: The Raft-replicated TAAS daemon executable.
- **[spring-autoconfiguration](./spring-autoconfiguration.md)**: Spring Boot starters for easy integration.

## Design Goals

1. **Broker-Untrusted**: The TAAS guarantees that even if the transport broker is fully compromised, forged identities cannot be introduced.
2. **Post-Quantum Ready**: Designed to handle variable length signatures and future FIPS 204 keys alongside classical algorithms like ED25519.
3. **High Availability**: The TAAS operates as a fault-tolerant cluster (via Raft), ensuring constant availability of public keys for offline verification workloads.
