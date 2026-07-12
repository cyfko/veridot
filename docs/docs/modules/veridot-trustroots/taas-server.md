---
title: taas-server
description: Overview of the TAAS Server executable, Raft consensus, and operational properties in Veridot V5.
keywords: [taas-server, Raft, server, veridot, V5]
sidebar_position: 4
---

# taas-server

`taas-server` is the standalone executable daemon that forms a single node in the Trust Authority & Attestation Service (TAAS) cluster.

## Architecture

The TAAS Server is fundamentally a Replicated State Machine (RSM) powered by the Raft consensus algorithm. In Protocol V5, the TAAS cluster is the absolute source of truth for cryptographic identity.

```mermaid
graph TD

    %% Premium Theme
    linkStyle default stroke:#888888,stroke-width:2px;
    classDef default fill:#424242,stroke:#616161,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef service fill:#4527a0,stroke:#5e35b1,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef broker fill:#004d40,stroke:#00695c,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef db fill:#bf360c,stroke:#d84315,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef taas fill:#01579b,stroke:#0277bd,stroke-width:1px,color:#fff,rx:5px,ry:5px;

    subgraph "TAAS Cluster (Raft)"
        Leader[TAAS Leader]
        Attestor["Pluggable Attestation Module"]
        Follower1[TAAS Follower 1]
        Follower2[TAAS Follower 2]
        
        Leader -.->|"1. Verify Proof"| Attestor
        Leader -.->|"2. AppendEntries"| Follower1
        Leader -.->|"2. AppendEntries"| Follower2
    end
    
    Instance[Compute Instance] -->|POST /v2/trust-entries| Leader
    Verifier[Verification Node] -->|GET /v2/trust-entries/{subject}| Follower1
    class Instance service;
    class Follower2,Leader,Follower1 taas;

```

## Consensus and Persistence

When an instance registers its attestation proof, the request is routed to the **Leader**. The leader validates the proof (e.g., verifying a TPM signature), and if valid, proposes the new `TrustEntry` to the Raft log. Once a majority of nodes (quorum) acknowledge the entry, it is committed to the state machine.

Because the state is highly cacheable and monotonic, read requests for public keys (`GET /v2/trust-entries/{subject}`) can be served by any follower, ensuring massive read scalability.

## Configuration

The server is configured via YAML or environment variables, defining its bind address, peers, and enabled attestation modules:

```yaml
taas:
  node-id: "node-1"
  bind: "0.0.0.0:8080"
  raft:
    peers:
      - "node-1:8081"
      - "node-2:8081"
      - "node-3:8081"
    data-dir: "/var/lib/taas/raft"
  attestation:
    modules:
      - "kubernetes"
      - "tpm"
```
