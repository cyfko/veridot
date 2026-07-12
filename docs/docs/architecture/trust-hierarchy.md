---
title: Trust Hierarchy
description: Cryptographic trust chain from Root keys through capability delegation to instance identities.
keywords: [trust hierarchy, TrustRoot, capability, delegation, root identity, KMS]
sidebar_position: 3
---

# Trust Hierarchy

Veridot uses a hierarchical cryptographic trust model where all authority derives from root identities registered in the `TrustRoot`. 

## Trust Chain

```mermaid
graph TD

    %% Premium Theme
    linkStyle default stroke:#888888,stroke-width:2px;
    classDef default fill:#424242,stroke:#616161,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef service fill:#4527a0,stroke:#5e35b1,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef broker fill:#004d40,stroke:#00695c,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef db fill:#bf360c,stroke:#d84315,stroke-width:1px,color:#fff,rx:5px,ry:5px;
    classDef taas fill:#01579b,stroke:#0277bd,stroke-width:1px,color:#fff,rx:5px,ry:5px;

    KMS["🔐 Root Identity Key"]
    TR["TrustRoot / TAAS"]
    CAP["CAPABILITY Entry<br/>Authorizes pattern for scope(s)"]
    SID["Instance Identity<br/>(CN@hash)"]
    ENV["SIGNED_DATA or LIVENESS"]

    KMS -->|"Bootstraps"| TR
    KMS -->|"Signs"| CAP
    CAP -->|"Authorizes"| SID
    SID -->|"Registers via Attestation"| TR
    SID -->|"Signs"| ENV
    class SID service;
    class TR,KMS taas;

```

## Root Identity

An identity directly and successfully resolvable in the `TrustRoot` with `isRoot=true` is called a **trust anchor** or root identity. Root identities have special privileges:

- **Unconditionally authorized** to publish `CAPABILITY` entries for any scope, without itself holding a prior `CAPABILITY`
- **Treated as having delegation depth 0**

### Trust Bootstrap
In a new deployment, the TAAS cluster is initialized with a bootstrap keypair (the root trust anchor). This key publishes the initial `CAPABILITY` entries authorizing service classes (e.g., using subject pattern `orders-service@*`).

## Capability Delegation

Non-root instances derive their authorization from `CAPABILITY (0x02)` entries. 

### CAPABILITY Entry Structure

- `subjectSid` OR `subjectPattern` (e.g. `orders-service@*`)
- `permissions` (list of strings)
- `delegationDepth`
- `notBefore`, `notAfter`

### Subject Pattern Matching

In an instance-native model, each instance has a unique subject (`CN@hash`). Creating one CAPABILITY entry per instance is operationally expensive. `subjectPattern` with wildcard `"orders-service@*"` authorizes all instances of a service class with a single entry.

## Revocation

Instead of key rotation, V5 uses explicit revocation. An instance that is shutting down or compromised publishes a `LIVENESS(REVOKED)` entry. Alternatively, an authoritative party can issue a `TRUST_REVOCATION (0x0A)` entry broadcasting the revocation of the previously trusted identity.

## Next Steps

- [Security Model](./security-model.md)
