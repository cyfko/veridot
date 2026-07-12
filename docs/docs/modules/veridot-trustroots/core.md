---
title: taas-core
description: Internals of the taas-core module, covering Trust Entries, attestation parsing, and Raft consensus bindings for Veridot V5.
keywords: [taas-core, Trust Entry, attestation, raft, veridot, V5]
sidebar_position: 2
---

# taas-core

`taas-core` implements the fundamental domain models and business logic for the Trust Authority & Attestation Service (TAAS) in Protocol V5.

## Domain Model

The central domain model in `taas-core` is the **Trust Entry**, which binds a cryptographic identity to its verified public key.

### Identity Format
In Protocol V5, the subject identifier must match:
`CN@hash(pk)`

Where `CN` is the Common Name of the service/instance and `hash(pk)` is the SHA-256 hash of the instance's public key (first 32 hex characters). This ensures globally unique, deterministic identification.

### State Machine

When an instance boots up, it undergoes the following lifecycle handled by `taas-core`:

1. **PROPOSED**: The instance submits its public key and attestation proof.
2. **VERIFIED**: The `AttestationPlugin` SPI in `taas-core` checks the proof (e.g., querying the AWS Nitro Enclave API or verifying a Kubernetes service account token).
3. **COMMITTED**: The Raft consensus module commits the state to the replicated log. The instance can now sign objects using Protocol V5.

## Core Interfaces

### AttestationPlugin (SPI)

```java
public interface AttestationPlugin {
    String name();
    AttestationResult verify(byte[] proof, AttestationContext ctx);
}
```

Pluggable verifiers allow for different platform attestation documents (TPM 2.0, SPIFFE/SPIRE, Kubernetes, cloud-specific tokens). They are dynamically loaded via Java's `ServiceLoader`. If verification fails, a `V5101` error is raised.

### RaftStateMachine

`taas-core` defines the interfaces that plug into the underlying Raft library (e.g., Apache Ratis or HashiCorp Raft) to replicate Trust Entries across the TAAS cluster, ensuring high availability and partition tolerance.
