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

## Local Deployment with Docker Compose

For local testing and development, you can easily spin up a 3-node TAAS cluster using the provided `docker-compose.yml`. We offer two ways to build the Docker image, giving developers and DevOps engineers the choice that best fits their constraints.

### 1. Build the Docker Image

Navigate to the TAAS server module directory:
```bash
cd java/veridot-trustroots/veridot-trustroots-taas-server
```

**Option A (Recommended): Spring Boot Cloud Native Buildpacks**
This approach leverages Spring Boot to build a highly optimized, layered OCI image natively without needing a Dockerfile. It is faster and more secure for Java applications.

```bash
mvn spring-boot:build-image
```
*This command creates an image named `veridot/taas-server:5.0.0-SNAPSHOT`.*

**Option B (Alternative): Classical Dockerfile via Docker Compose**
If your environment restricts the Buildpack API (e.g., Docker Desktop API issues), Docker Compose can build the image directly from the provided `Dockerfile`.

First, package the fat JAR:
```bash
mvn clean package -DskipTests
```
*(Docker Compose will automatically build the image in the next step).*

### 2. Start the Cluster

Once your image is ready (Option A) or your JAR is packaged (Option B), you can launch the 3-node cluster. 

By default, the cluster will start with standard ports. Simply run:

```bash
docker compose up --build -d
```
*(The `--build` flag ensures Docker Compose builds the image automatically if you chose Option B).*

If successful, this will launch 3 nodes with the following default ports:
- `taas-node-1` (Ports: HTTP 8080, Raft 8081)
- `taas-node-2` (Ports: HTTP 8082, Raft 8083)
- `taas-node-3` (Ports: HTTP 8084, Raft 8085)

The nodes will automatically discover each other using the `VERIDOT_TAAS_SERVER_INITIAL_PEERS` configuration to form the consensus Quorum and elect a Raft leader. Persistent storage for the Raft logs and state machine is mapped to Docker volumes automatically.

### 3. Connecting to the Cluster (Client Perspective)

It is crucial to understand that `VERIDOT_TAAS_SERVER_INITIAL_PEERS` is an **internal infrastructure configuration** required only for the servers to form the Raft quorum upon their first boot. 

**Developers and client applications do NOT need to know the IP addresses of all nodes.**

To connect a client application to the TAAS cluster in a production environment:
1. Place a Load Balancer, API Gateway, or Kubernetes Ingress in front of the TAAS HTTP API ports (e.g., ports `8080`, `8082`, `8084`).
2. Provide developers with a **single, unified URL** (e.g., `https://taas.internal.company.com`).
3. The Load Balancer routes the client request to any available node.
4. If the chosen node is a Raft Follower, it will automatically handle the request or redirect it to the active Raft Leader transparently.

#### Advanced: Customizing Ports
The `docker-compose.yml` is highly flexible. If the default ports conflict with other applications on your machine, you can override them via environment variables before launching:

```bash
NODE1_HTTP_PORT=9090 NODE1_RAFT_PORT=9091 docker compose up --build -d
```
