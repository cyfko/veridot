---
title: "Chapter 2: How Veridot Works"
description: "The V5 architecture: Identity binding, TAAS, and NATIVE token distribution."
sidebar_position: 2
pagination_prev: learn/the-problem
pagination_next: learn/first-integration
---

# How Veridot Works

In Chapter 1, we saw the authentication trilemma. Veridot Protocol V5 shatters this trilemma by decoupling identity attestation from transport and verification.

There are no shared secrets. There are no centralized verification bottlenecks. And there are no cumbersome "Key Epochs" to manage.

## Single Key Per Instance

In Veridot V5, every compute instance (a Kubernetes pod, a VM, a serverless function) generates exactly **one ephemeral asymmetric keypair** during its lifetime. 

For ephemeral workloads (like containers), the instance generates this keypair in memory and never rotates it locally. (For long-lived systems, Veridot supports explicit Key Rotation via TAAS). If the instance is compromised or restarted, the old key dies with it, and a new identity is registered.

## Trust Authority & Attestation Service (TAAS)

How do we trust an ephemeral key? Enter the **TAAS**.

Before an instance can sign anything, it must register with the TAAS. It submits its public key along with an **attestation proof** (e.g., a TPM quote or a Kubernetes service account token). The TAAS cluster reaches consensus via Raft, validates the proof, and grants the instance a unique `TrustIdentity` containing the key, the algorithm, and an `isRoot` flag.

## The NATIVE Distribution Mode

When an instance wants to issue a token:
1. It publishes a `LIVENESS(ACTIVE)` entry to the message broker.
2. It signs the payload, publishes a `SIGNED_DATA` entry to the broker.
3. It returns a tiny reference token to the client in the **NATIVE** format: `8:<scope>:<key>`.

## Sub-millisecond Verification

When a downstream service receives this `8:<scope>:<key>` token:
1. It parses the token to know what scope and key to look for.
2. It looks up the associated `SIGNED_DATA` and `LIVENESS` entries from its local, sub-millisecond cache of the broker.
3. It resolves the signer's identity (`TrustIdentity`) via a local TrustRoot cache (populated by the TAAS).
4. It verifies the cryptographic signature and checks that the liveness state is exactly `ACTIVE`.

Because everything is cached asynchronously, the verification takes less than a millisecond, with **no synchronous network calls**, while still providing **instant revocation**.
