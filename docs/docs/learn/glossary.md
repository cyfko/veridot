---
title: Glossary
description: Core terminology used throughout the Veridot V5 documentation.
sidebar_position: 10
---

# Veridot Glossary

Welcome to the Veridot ecosystem! If you are new to the protocol, you will encounter several specific terms. Here is a quick reference to help you navigate the documentation.

### TAAS (Trust Authority & Attestation Service)
The registration component of Veridot. Before an instance can sign tokens, it must prove its identity to the TAAS cluster by submitting an attestation proof (e.g., from a TPM, Kubernetes, or AWS Nitro Enclave). TAAS is the only entity that manages the long-term trust hierarchy.

### Broker-Untrusted
A security design paradigm where the central message broker (e.g., Kafka, PostgreSQL) is strictly used as a dumb pipe for data delivery. The broker has no ability to forge, alter, or validate tokens. Even if the broker is fully compromised by an attacker, the integrity and authenticity of the tokens remain mathematically guaranteed.

### Single Key Per Instance (SKPI)
An operational pattern highly recommended for ephemeral workloads (like Kubernetes). The instance generates one keypair upon startup and never rotates it locally. If the instance dies, the key is abandoned. Note: For long-lived bare-metal servers, Veridot V5 *does* support explicit Key Rotation via TAAS.

### Attestor SPI
The Service Provider Interface used by instances to generate a hardware or environment-level proof of their identity. Implementations include `K8sAttestor` for Kubernetes Service Accounts, `GcpAttestor` for Google Cloud, and `NoneAttestor` strictly for local testing.

### `CN@hash(pk)`
The canonical identity format in V5. It combines a Common Name (CN) identifying the service (e.g., `shipping-svc`) with the first 32 bytes of the Base64Url-encoded SHA-256 hash of its public key. Example: `shipping-svc@aB3x9...`

### Distribution Modes
How Veridot delivers tokens to consumers:
- **DIRECT**: The payload and signature are packaged as a classic JWT and returned directly to the HTTP caller.
- **NATIVE**: The payload is stored directly inside the broker as a `SIGNED_DATA` entry. The caller only receives a tiny reference token (e.g., `8:orders:key123`). This is the recommended mode for massive scale.
- **PRIVATE**: Similar to NATIVE, but the payload is End-to-End Encrypted (`SECURE_PAYLOAD`) before being stored in the broker.

### Fence Token
A cryptographic marker used to prevent race conditions in distributed environments without requiring a centralized locking mechanism. It ensures strictly monotonic updates.
