---
title: TAAS Architecture
description: Deep-dive into the Trust Authority and Attestation Service (TAAS) — a Raft-replicated distributed authority for public key distribution and attestation in Veridot.
keywords: [TAAS, Trust Authority, Attestation, Raft, consensus, public key, veridot]
sidebar_position: 8
---

# TAAS Architecture

The **Trust Authority & Attestation Service (TAAS)** is Veridot's distributed, strongly-consistent registry for public key distribution and instance attestation. It solves a fundamental problem: how do verifier services securely obtain the public keys of ephemeral compute instances?

TAAS uses Raft consensus to replicate public key entries across a cluster, ensuring high availability and tamper resistance.

## High-Level Architecture

```mermaid
graph TB

    %% Premium Theme
    linkStyle default stroke:#888888,stroke-width:2px;
    classDef default fill:#424242,stroke:#616161,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef service fill:#4527a0,stroke:#5e35b1,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef broker fill:#004d40,stroke:#00695c,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef db fill:#bf360c,stroke:#d84315,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef taas fill:#01579b,stroke:#0277bd,stroke-width:1px,color:#fff,rx:5px,ry:5px;

    subgraph "TAAS Cluster (Raft Consensus)"
        L["Node 1<br/><b>LEADER</b>"]
        A["Pluggable Attestor<br/>(TPM / K8s / GCP)"]
        F1["Node 2<br/>FOLLOWER"]
        F2["Node 3<br/>FOLLOWER"]
        L -.->|"1. Verify Proof"| A
        L -- "2. AppendEntries" --> F1
        L -- "2. AppendEntries" --> F2
    end

    subgraph "Issuer Instances"
        S1["Instance A<br/>(Register)"]
        S2["Instance B<br/>(Register)"]
    end

    subgraph "Verifier Services"
        V1["Verifier A<br/>(CachingTrustRoot)"]
        V2["Verifier B<br/>(CachingTrustRoot)"]
    end

    S1 -- "POST /v2/trust-entries" --> L
    S2 -- "POST /v2/trust-entries" --> L
    V1 -- "GET /v2/trust-entries/..." --> F1
    V2 -- "GET /v2/trust-entries/..." --> F2
    class S2,S1 service;
    class V1,V2 taas;

```

## Attestation-First Registration

Every identity participating in the protocol MUST be backed by an attestation proof. During registration:

1. An instance generates an asymmetric keypair and computes its subject `CN@hash`.
2. It obtains an **attestation proof** binding its public key to its runtime environment (e.g., TPM quote).
3. It registers at the TAAS via `POST /v2/trust-entries` with its trust entry and proof.
4. The TAAS verifies the proof. Upon success, it stores the public key via Raft consensus.

## TrustEntry Record

A `TrustEntry` is the canonical record of a registered identity in the TAAS. It contains:
- `schemaVersion` (MUST be 2 for V5)
- `subject` (Format: `CN@hash`)
- `publicKeyEncoded`
- `algorithm`
- `isRoot` and `isInstanceScoped`
- `attestationPlugin` and `attestationRef`

## Data Flow: Register → Replicate → Serve

```mermaid
flowchart LR

    %% Premium Theme
    linkStyle default stroke:#888888,stroke-width:2px;
    classDef default fill:#424242,stroke:#616161,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef service fill:#4527a0,stroke:#5e35b1,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef broker fill:#004d40,stroke:#00695c,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef db fill:#bf360c,stroke:#d84315,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef taas fill:#01579b,stroke:#0277bd,stroke-width:1px,color:#fff,rx:5px,ry:5px;

    subgraph "1. Register"
A["Instance"] -->|"POST TrustEntry + Proof"| B["TAAS Leader"]
    end

    subgraph "2. Consensus"
        B -->|"Verify Proof"| Att["Pluggable Attestor (TpmQuoteAttestor, etc.)"]
        Att -->|"On Success, Write"| C["Raft Log (TaasRocksDbStore)"]
        C -->|"AppendEntries"| D["Follower"]
        D -->|"ACK"| C
    end

    subgraph "3. Serve Reads"
E["Verifier Service"] -->|"GET /v2/trust-entries"| D
    end
    class A,E service;

```

## See Also

- [CachingTrustRoot Architecture](./caching-trustroot.md) — Client-side caching layer
