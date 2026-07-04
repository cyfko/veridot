---
title: "Chapter 4: Going Distributed — The TAD"
description: "Distribute long-term public keys across services using the Trust Authority Directory, a Raft-replicated cluster."
sidebar_position: 4
pagination_prev: learn/first-integration
pagination_next: learn/capabilities
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Going Distributed — The TAD

The TrustRoot in [Chapter 3](./first-integration.md) was a lambda capturing a local variable:

```java
PublicKeyTrustRoot trustRoot = issuer -> {
    if ("order-service".equals(issuer)) {
        return new TrustIdentity(longTermKeyPair.getPublic(), true);
    }
    // ...
};
```

This works when signer and verifier share the same JVM. But in production, `shipping-service` runs on a different server — possibly in a different data center. It can't access `longTermKeyPair`. It needs a way to answer: *"What is `order-service`'s long-term public key?"*

This is the **key distribution problem**.

---

## Why Not Just Share the Public Key File?

You could copy `order-service`'s public key into a file on `shipping-service`'s server. But static files break down quickly:

| Problem | What goes wrong |
|---------|----------------|
| **Key rotation** | You rotate `order-service`'s key — now every verifier has a stale file. Rolling restarts required. |
| **New services** | `admin-service` joins the mesh. You manually copy keys to N servers. |
| **Multi-node consistency** | Two replicas of `shipping-service` have different key files after a failed deploy. One verifies; one rejects. |
| **Revocation** | A key is compromised. How do you invalidate it everywhere, instantly? |

You need a **live, replicated key directory** that handles all of this automatically.

---

## The TAD: A Raft-Replicated Key Directory

The **Trust Authority Directory (TAD)** is a small cluster (typically 3 or 5 nodes) that stores and replicates long-term public keys using the Raft consensus protocol. It is part of the `veridot-trustroots` module family.

```
  order-service (signer)
  ───────────────────────
  ┌─────────────────────┐
  │ TadPublisherClient  │
  └─────────┬───────────┘
            │ POST /v1/trust-entries
            ▼
  ┌──────────────────────────────────────────┐
  │            TAD Cluster (Raft)            │
  │                                          │
  │   ┌────────┐  ┌────────────┐  ┌────────┐ │
  │   │ Node 1 │◀─│ Node 2     │─▶│ Node 3 │ │
  │   │        │  │ (Leader)   │  │        │ │
  │   └────────┘  └────────────┘  └────────┘ │
  │            Raft replication               │
  └────────────────────┬─────────────────────┘
                       │ GET /v1/trust-entries/{subject}
                       ▼
  shipping-service (verifier)
  ───────────────────────────
  ┌──────────────────────┐
  │ TadTrustRootProvider │
  └──────────┬───────────┘
             ▼
  ┌──────────────────────┐
  │   CachingTrustRoot   │
  └──────────┬───────────┘
             ▼
  ┌──────────────────────┐
  │ GenericSignerVerifier │
  └──────────────────────┘
```

**Writes** (publishing a new key) go through Raft consensus — the leader replicates to a quorum before acknowledging. **Reads** are served locally from any node.

The critical insight: **the TAD is a control-plane component**. It is never on the verification hot path. Verifiers cache keys locally and only contact the TAD for initial bootstrapping and periodic refresh.

---

## Step 1: Start a TAD Cluster

A minimal TAD cluster needs 3 nodes for Raft consensus. Here's a Docker Compose configuration:

```yaml
# docker-compose.tad.yml
services:
  tad-node-1:
    image: ghcr.io/cyfko/veridot-tad-server:4.0.1
    ports:
      - "8443:8443"
    environment:
      VERIDOT_TAD_SERVER_NODE_ID: "tad-node-1:19443"
      VERIDOT_TAD_SERVER_INITIAL_PEERS: "tad-node-1:19443,tad-node-2:19443,tad-node-3:19443"
      VERIDOT_TAD_SERVER_STORAGE_DIRECTORY: "/data/tad"
    volumes:
      - tad-data-1:/data/tad

  tad-node-2:
    image: ghcr.io/cyfko/veridot-tad-server:4.0.1
    ports:
      - "8444:8443"
    environment:
      VERIDOT_TAD_SERVER_NODE_ID: "tad-node-2:19443"
      VERIDOT_TAD_SERVER_INITIAL_PEERS: "tad-node-1:19443,tad-node-2:19443,tad-node-3:19443"
      VERIDOT_TAD_SERVER_STORAGE_DIRECTORY: "/data/tad"
    volumes:
      - tad-data-2:/data/tad

  tad-node-3:
    image: ghcr.io/cyfko/veridot-tad-server:4.0.1
    ports:
      - "8445:8443"
    environment:
      VERIDOT_TAD_SERVER_NODE_ID: "tad-node-3:19443"
      VERIDOT_TAD_SERVER_INITIAL_PEERS: "tad-node-1:19443,tad-node-2:19443,tad-node-3:19443"
      VERIDOT_TAD_SERVER_STORAGE_DIRECTORY: "/data/tad"
    volumes:
      - tad-data-3:/data/tad

volumes:
  tad-data-1:
  tad-data-2:
  tad-data-3:
```

```bash
docker compose -f docker-compose.tad.yml up -d
```

Wait a few seconds for Raft leader election to complete. The cluster is ready when any node responds to `GET /v1/trust-entries/{subject}`.

---

## Step 2: Publish Your Public Key (order-service)

On the signer side, `order-service` generates its long-term key pair and publishes the public key to the TAD.

First, add the TAD client dependency:

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-trustroots-tad-client</artifactId>
    <version>4.0.1</version>
</dependency>
```

Then publish the key:

```java
import io.github.cyfko.veridot.trustroots.api.KeyAlgorithm;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.tad.client.TadPublisherClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

// 1. Generate the long-term key pair
KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
KeyPair longTermKeyPair = kpg.generateKeyPair();

// 2. Encode the public key as Base64 URL-safe
String publicKeyEncoded = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(longTermKeyPair.getPublic().getEncoded());

// 3. Compute the fingerprint (SHA-256 of the public key bytes)
byte[] fingerprintBytes = MessageDigest.getInstance("SHA-256")
    .digest(longTermKeyPair.getPublic().getEncoded());
String fingerprint = HexFormat.of().formatHex(fingerprintBytes);

// 4. Build the TrustEntry
Instant now = Instant.now();
TrustEntry entry = TrustEntry.builder()
    .subject("order-service")
    .publicKeyEncoded(publicKeyEncoded)
    .algorithm(KeyAlgorithm.ED25519)
    .notBefore(now)
    .notAfter(now.plus(Duration.ofDays(365)))
    .version(1)
    .fingerprint(fingerprint)
    .isRoot(true)
    .publishedAt(now)
    .issuerSignature("self-signed")        // simplified — see production guide
    .build();

// 5. Publish to the TAD cluster
List<String> tadUrls = List.of(
    "http://localhost:8443",
    "http://localhost:8444",
    "http://localhost:8445"
);

var publisher = new TadPublisherClient(tadUrls, null, Duration.ofSeconds(5));
publisher.publish(entry);

System.out.println("✅ Public key published to TAD as 'order-service'");
```

The `TadPublisherClient` automatically fails over to the next node if one is unreachable. The Raft leader replicates the entry to all nodes before acknowledging.

---

## Step 3: Configure the Verifier (shipping-service)

On the verifier side, `shipping-service` creates a `CachingTrustRoot` backed by a `TadTrustRootProvider`. This is the recommended production stack:

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-trustroots-tad-client</artifactId>
    <version>4.0.1</version>
</dependency>
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-trustroots-core</artifactId>
    <version>4.0.1</version>
</dependency>
```

```java
import io.github.cyfko.veridot.trustroots.tad.client.TadTrustRootProvider;
import io.github.cyfko.veridot.trustroots.core.CachingTrustRoot;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

// 1. Create the TAD provider (fetches keys from the TAD cluster)
var tadProvider = new TadTrustRootProvider(
    List.of("http://localhost:8443", "http://localhost:8444", "http://localhost:8445"),
    null,                          // SSLContext — null for plain HTTP in dev
    Duration.ofSeconds(5)          // request timeout
);

// 2. Wrap it in CachingTrustRoot (L1 memory + L2 RocksDB)
var trustRoot = CachingTrustRoot.builder()
    .provider(tadProvider)
    .l2Directory(Path.of("/var/lib/shipping-service/trust-cache"))
    .l1MaxSize(10_000)
    .refreshThreshold(Duration.ofHours(1))
    .staleWindow(Duration.ofMinutes(5))
    .fullSyncInterval(Duration.ofHours(6))
    .resolveWaitTimeout(Duration.ofSeconds(5))
    .build();

// 3. Initialize (bootstraps from L2 cache or TAD)
trustRoot.initialize();
```

Now use this `trustRoot` in the `GenericSignerVerifier`:

```java
import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;

// shipping-service only verifies — it doesn't need a long-term private key
// But GenericSignerVerifier requires one for the constructor, so shipping-service
// generates its own identity for any signing it might do
var shippingVerifier = new GenericSignerVerifier(
    broker,
    trustRoot,                           // CachingTrustRoot → resolves "order-service"
    "shipping-service",
    shippingKeyPair.getPrivate(),
    Algorithm.ED25519
);
```

---

## Step 4: Verify Across Services

Now `shipping-service` can verify tokens from `order-service`, even though they run on different servers:

```java
import io.github.cyfko.veridot.core.VerifiedData;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;

// Token received from order-service (via HTTP, message queue, etc.)
String token = receiveTokenFromOrderService();

// Verify it!
VerifiedData<OrderPayload> result = shippingVerifier.verify(
    token,
    BasicConfigurer.deserializer(OrderPayload.class)
);

System.out.println("✅ Order verified: " + result.data().orderId());
System.out.println("   Destination:    " + result.data().destination());
```

Here's what happens when `shipping-service` calls `verify()`:

1. Parse the JWT → extract issuer = `"order-service"`
2. Call `trustRoot.resolve("order-service")`:
   - **L1 hit** (~100ns) → returns the cached public key immediately
   - **L1 miss** → fall back to L2 (RocksDB, ~10μs)
   - **L2 miss** → async refresh from TAD in background
3. Verify the protocol envelope with the resolved long-term public key
4. Extract the ephemeral public key, verify the JWT
5. Return `VerifiedData<OrderPayload>`

**No synchronous network call to the TAD during verification.** The hot path is entirely local.

---

## What If the TAD Goes Down?

This is where the architecture shines. The TAD is **only on the control plane** — it distributes keys, but verification never calls it synchronously:

```
┌──────────────────────────────┐
│    Control Plane (async)     │
│                              │
│      ┌──────────────┐        │
│      │  TAD Cluster  │       │
│      └──────┬───────┘        │
└─────────────┼────────────────┘
              │ periodic sync
              │ (every 6h)
              ▼
┌──────────────────────────────┐
│    Data Plane (hot path)     │
│                              │
│  ┌────────────────────────┐  │
│  │ L2 RocksDB Cache ~10μs │  │
│  └───────────┬────────────┘  │
│              │ promote       │
│              │ on miss       │
│              ▼               │
│  ┌────────────────────────┐  │
│  │ L1 Memory Cache ~100ns │  │
│  └───────────┬────────────┘  │
│              │ resolve()     │
│              ▼               │
│      ┌────────────┐          │
│      │  verify()  │          │
│      └────────────┘          │
└──────────────────────────────┘
```

If the entire TAD cluster is down:

- **L1 cache** continues serving keys from memory — zero impact
- **L2 cache** (RocksDB on disk) survives process restarts — keys persist across deploys
- **Stale window** (default 5 minutes) allows keys slightly past expiration to remain valid during outages
- **Background refresh** automatically recovers when the TAD comes back online

The TAD could be down for hours and verification continues uninterrupted, as long as keys were cached before the outage.

---

## Spring Boot Shortcut

If you're using Spring Boot, all of the above configuration collapses to a few lines in `application.yml`:

```yaml
veridot:
  trustroots:
    provider-type: tad
    tad-cluster-urls:
      - http://tad-node-1:8443
      - http://tad-node-2:8443
      - http://tad-node-3:8443
    l2-directory: /var/lib/myapp/trust-cache
    l1-max-size: 10000
    refresh-threshold: 1h
    stale-window: 5m
    full-sync-interval: 6h
    connect-timeout: 3s
```

Add the Spring starter dependency:

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-trustroots-spring</artifactId>
    <version>4.0.1</version>
</dependency>
```

Spring auto-configuration creates a `TadTrustRootProvider` and wraps it in a `CachingTrustRoot` bean — which is automatically injected wherever a `TrustRoot` is needed. No manual wiring required.

---

## Key Rotation

When `order-service` needs to rotate its long-term key (scheduled rotation, security policy, etc.), use the `TadPublisherClient.rotate()` method:

```java
// Generate a new key pair
KeyPair newKeyPair = kpg.generateKeyPair();

// Build an updated TrustEntry with version 2
TrustEntry rotatedEntry = TrustEntry.builder()
    .subject("order-service")
    .publicKeyEncoded(Base64.getUrlEncoder().withoutPadding()
        .encodeToString(newKeyPair.getPublic().getEncoded()))
    .algorithm(KeyAlgorithm.ED25519)
    .notBefore(Instant.now())
    .notAfter(Instant.now().plus(Duration.ofDays(365)))
    .version(2)                          // incremented version
    .fingerprint(computeFingerprint(newKeyPair.getPublic()))
    .issuerSignature("self-signed")
    .publishedAt(Instant.now())
    .isRoot(true)
    .build();

// Rotate via PUT /v1/trust-entries/{subject}
publisher.rotate("order-service", rotatedEntry);
```

The TAD replicates the new key via Raft. Verifiers' `CachingTrustRoot` instances pick it up on their next background sync — or immediately when the stale window triggers a refresh.

---

## Recap: The Production Stack

Here's the recommended production architecture:

| Component | Role | Module |
|-----------|------|--------|
| **TAD Cluster** (3+ nodes) | Stores and replicates long-term public keys via Raft | `veridot-trustroots-tad-server` |
| **TadPublisherClient** | Publishes/rotates keys (signer side) | `veridot-trustroots-tad-client` |
| **TadTrustRootProvider** | Fetches keys from TAD (verifier side) | `veridot-trustroots-tad-client` |
| **CachingTrustRoot** | L1 memory + L2 RocksDB cache around the provider | `veridot-trustroots-core` |
| **GenericSignerVerifier** | Signs, verifies, revokes | `veridot-core` |
| **Broker** (Kafka or SQL) | Distributes protocol entries (KEY_EPOCH, LIVENESS) | `veridot-kafka` or `veridot-databases` |

---

:::tip[What's next?]
Two services now communicate securely without shared secrets and without a single point of failure. But what if you want to add `admin-service` — a third service that can only sign tokens for certain scopes?

That's the domain of **capabilities** — fine-grained authorization embedded in the protocol itself.

**[Chapter 5: Capabilities →](./capabilities.md)**
:::
