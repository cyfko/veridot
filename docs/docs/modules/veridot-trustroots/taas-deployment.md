---
title: TAAS Deployment Guide
description: Best practices for deploying the TAAS cluster in a production environment for Veridot V5.
keywords: [deployment, TAAS, Raft, veridot, V5]
sidebar_position: 6
---

# TAAS Deployment Guide

Deploying the Trust Authority & Attestation Service (TAAS) cluster correctly is critical to the security of Veridot Protocol V5. As the root of trust, its availability and integrity dictate the health of the entire system.

## Cluster Sizing

Because TAAS uses Raft consensus, the cluster must be deployed with an **odd number of nodes** to tolerate failures:

- **3 Nodes**: Tolerates 1 node failure. Recommended for most production environments.
- **5 Nodes**: Tolerates 2 node failures. Recommended for mission-critical, globally distributed deployments.
- **7+ Nodes**: Not recommended due to increased consensus latency.

## High Availability and Placement

To ensure fault tolerance, TAAS nodes must be distributed across failure domains:
- In AWS, place each node in a different Availability Zone (AZ).
- In Kubernetes, use `podAntiAffinity` to ensure TAAS pods are scheduled on different physical nodes.

## Persistence

The Raft log requires durable storage. Using fast, local NVMe SSDs is highly recommended to minimize `fsync` latency, which directly impacts the speed at which new instances can register.

```yaml
# Kubernetes example
volumeClaimTemplates:
  - metadata:
      name: taas-data
    spec:
      accessModes: [ "ReadWriteOnce" ]
      storageClassName: "local-nvme"
      resources:
        requests:
          storage: 10Gi
```

## Security Posture

1. **Network Isolation**: The TAAS API port (8080) should be accessible by all instances in your infrastructure. The Raft peer port (8081) MUST be strictly firewalled to only allow communication between TAAS nodes.
2. **mTLS**: Peer communication between TAAS nodes should be secured via mTLS to prevent unauthorized nodes from joining the cluster.
