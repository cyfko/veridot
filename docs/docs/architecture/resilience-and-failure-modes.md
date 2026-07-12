---
title: Resilience & Failure Modes
description: Operational playbook and failure modes for TAAS, Brokers, and Cache.
sidebar_position: 8
---

# Resilience & Failure Modes

Veridot V5 is designed for mission-critical, highly available microservice architectures. As a DevSecOps engineer, you must understand how the system behaves when core infrastructure components fail.

Veridot strictly isolates the **Bootstrap Path** (TAAS) from the **Hot Paths** (Broker and Local Cache). This isolation is the foundation of its resilience.

## 1. TAAS Cluster Outage
*The Trust Authority & Attestation Service goes completely offline.*

- **Signing Impact (Minimal):** Instances that have *already* booted and registered with TAAS can continue signing tokens indefinitely (or until their token `validity` expires). 
- **Verification Impact (None):** Verification nodes rely entirely on their local `TrustRoot` cache. They do not contact TAAS during the verification hot path.
- **Bootstrapping Impact (High):** *New* instances scaling up (e.g., Kubernetes HPA spinning up a new pod) will fail to register. They cannot sign envelopes until TAAS recovers.
- **Revocation Impact (High):** You cannot manually revoke an identity at the TAAS level, though local capability revocations via the broker may still work.

**Playbook:** Deploy TAAS as a multi-AZ Raft cluster. If the entire cluster is lost, restore the Raft state from backup. Existing workloads will not drop traffic during the outage.

## 2. Message Broker Outage
*Kafka, RabbitMQ, or PostgreSQL goes offline or partitions.*

- **Verification Impact (Stale State):** The verification engine will continue verifying tokens using its last known state. It will not receive new `LIVENESS` or `SIGNED_DATA` entries. If a token is revoked *during* the outage, the verifier won't know until the broker recovers (Fail-Open on stale cache).
- **Signing Impact (High):** In `NATIVE` or `PRIVATE` modes, the signer cannot publish the payload to the broker. Signing will fail (Fail-Closed). In `DIRECT` mode, signing succeeds but `LIVENESS` cannot be published.

**Playbook:** Configure your broker for high availability. Veridot is designed to gracefully handle transient broker disconnects by relying on its internal local cache, but a prolonged broker outage stops the flow of new native data.

## 3. Local Cache / Verifier Restart
*A consumer node crashes and loses its local in-memory state.*

- **Impact (Rehydration Delay):** When the node restarts, its `TrustRoot` and Liveness caches are empty. It must replay the broker topic or query TAAS to rebuild its trust state before it can verify tokens.
- **Playbook:** Use persistent local caching (e.g., RocksDB or Redis) if rapid restart without rehydration latency is critical.
