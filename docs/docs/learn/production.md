---
title: "Chapter 7: Going to Production"
description: "Deploy Veridot with Spring Boot, Kafka, and the TAD — monitoring, configuration, and distribution modes."
sidebar_position: 7
pagination_prev: learn/session-management
pagination_next: null
---

# Chapter 7: Going to Production

Your code works. Now let's deploy it.

ShopFlow's order-service signs tokens, shipping-service and admin-service verify them, and a TAD cluster anchors trust. In the previous chapters we focused on the logic — signing, verifying, capabilities, session management. This chapter is about the infrastructure that makes it all run reliably.

---

## Spring Boot Auto-Configuration

Veridot ships a Spring Boot starter that wires everything for you: the TAD client, the two-level cache, and the `PublicKeyTrustRoot` bean.

### 1. Add the starter

```xml title="pom.xml"
<dependency>
  <groupId>io.github.cyfko</groupId>
  <artifactId>veridot-trustroots-spring</artifactId>
  <version>${veridot.version}</version>
</dependency>
```

### 2. Configure via `application.yml`

```yaml title="application.yml"
veridot:
  trustroots:
    tad-cluster-urls: https://tad-1.shopflow.internal:8443,https://tad-2.shopflow.internal:8443,https://tad-3.shopflow.internal:8443
    l1-max-size: 10000            # In-memory cache entries (default)
    l2-directory: ~/.veridot/trust-cache  # RocksDB on-disk cache
    refresh-threshold: 1h         # Re-fetch a key after this interval
    stale-window: 5min            # Serve stale if TAD is unreachable for < 5 min
    full-sync-interval: 6h        # Full resync with TAD every 6 hours
```

### 3. What the starter does

Behind the scenes, the starter auto-creates two beans:

1. **`TadTrustRootProvider`** — connects to your TAD cluster and resolves issuer → long-term public key.
2. **`CachingTrustRoot`** — wraps the provider with an L1 in-memory cache and an L2 RocksDB cache, implementing `PublicKeyTrustRoot`.

One starter, everything wired automatically.

:::tip[Custom providers]
The starter uses `@ConditionalOnMissingBean`. If you define your own `TrustRootProvider` bean, the auto-configured one backs off — useful for testing or custom trust resolution logic.
:::

---

## Choosing a Broker

Veridot needs a broker to propagate signed tokens and revocations from the signer to verifier nodes. Two modules are available:

### Kafka + RocksDB

```xml title="pom.xml"
<dependency>
  <groupId>io.github.cyfko</groupId>
  <artifactId>veridot-kafka</artifactId>
  <version>${veridot.version}</version>
</dependency>
```

Best for **event-driven architectures**. Kafka topics carry token events; each verifier node consumes and populates its local RocksDB store. Propagation is sub-second.

**ShopFlow uses this.** Order-service publishes to Kafka, shipping-service and admin-service each consume and verify locally — no cross-service calls for verification.

### SQL (PostgreSQL / MySQL)

```xml title="pom.xml"
<dependency>
  <groupId>io.github.cyfko</groupId>
  <artifactId>veridot-databases</artifactId>
  <version>${veridot.version}</version>
</dependency>
```

Best for **database-centric stacks** where Kafka would be over-engineered. Verifier nodes poll the database for new token events. Simpler to operate, but propagation depends on polling interval.

### Decision Table

| Factor                  | Kafka + RocksDB                        | SQL (PostgreSQL / MySQL)             |
|-------------------------|----------------------------------------|--------------------------------------|
| **Propagation latency** | Sub-second                             | Polling interval (configurable)      |
| **Throughput**          | High (partitioned topics)              | Moderate (DB write throughput)       |
| **Operational overhead**| Kafka cluster required                 | Existing database is enough          |
| **Best fit**            | Event-driven microservices             | Monolith or small service count      |
| **ShopFlow choice**     | ✅ Recommended                         | Good for admin-only internal tools   |

:::note[Recommendation]
For most production deployments, **Kafka + RocksDB** is the recommended choice. It gives you sub-second propagation, horizontal scalability, and decoupled verifier nodes.
:::

---

## Distribution Modes

When a signer creates a token, *how* does it reach the consumer? Veridot supports three distribution modes. The right choice depends on your payload sensitivity.

### DIRECT

The JWT is returned directly to the caller and typically passed via the HTTP `Authorization` header.

```
┌───────────────┐    sign()    ┌───────────────┐
│ order-service │─────────────▶│   Veridot     │
│               │◀─────────────│   Signer      │
│               │  JWT string  └───────────────┘
└──────┬────────┘
       │  Authorization: Bearer <JWT>
       ▼
┌────────────────┐
│shipping-service│  ──▶ verifier.verify(jwt)
└────────────────┘
```

**When to use:** Most common pattern. The token payload is not sensitive (e.g., user roles, order IDs).

```java title="ShopFlow — order-service (DIRECT)"
String jwt = signer.sign(
    new OrderContext(orderId, "SHIPPED"),
    BasicConfigurer.builder()
        .groupId("order-" + customerId)
        .sequenceId("order-" + orderId)
        .validity(3600)
        .build()
);
// Return JWT in response header or body
response.setHeader("Authorization", "Bearer " + jwt);
```

### INDIRECT

Only a `messageId` is returned to the caller. The actual JWT is stored in the broker (Kafka or SQL). The verifier retrieves the JWT from the broker using the `messageId`.

**When to use:** The payload contains sensitive data you don't want on the wire between services (e.g., payment details, internal pricing).

```java title="ShopFlow — order-service (INDIRECT)"
String messageId = signer.sign(
    new PaymentConfirmation(orderId, amount, cardLast4),
    BasicConfigurer.builder()
        .groupId("order-" + customerId)
        .sequenceId("payment-" + orderId)
        .validity(3600)
        .distributionMode(DistributionMode.INDIRECT)
        .build()
);
// Only the messageId travels over HTTP — JWT stays in the broker
response.setHeader("X-Veridot-Message-Id", messageId);
```

### PRIVATE

End-to-end encrypted distribution. The token is encrypted with hybrid encryption (AES-GCM + RSA/ECDH) so that only named recipients can decrypt it.

**When to use:** PII or data subject to regulatory requirements where even broker operators should not see plaintext (e.g., GDPR-covered customer data).

```java title="ShopFlow — admin-service (PRIVATE)"
String encryptedRef = signer.sign(
    new CustomerPII(name, email, address),
    BasicConfigurer.builder()
        .groupId("customer-" + customerId)
        .sequenceId("pii-export-" + exportId)
        .validity(1800)
        .distributionMode(DistributionMode.PRIVATE)
        .recipients("admin-service")
        .build()
);
// Only admin-service can decrypt this token
```

### Mode Comparison

| Mode         | What travels on the wire | Broker sees plaintext? | Use case                     |
|--------------|--------------------------|------------------------|------------------------------|
| **DIRECT**   | Full JWT                 | Yes                    | General purpose, most common |
| **INDIRECT** | messageId only           | Yes                    | Sensitive payloads           |
| **PRIVATE**  | Encrypted reference      | No                     | PII, regulated data          |

---

## Monitoring & Health

### TAD Health Endpoint

Each TAD node exposes a health endpoint:

```bash
curl https://tad-1.shopflow.internal:8443/health
```

```json
{
  "role": "LEADER",
  "leaderId": "tad-1"
}
```

For follower nodes:

```json
{
  "role": "FOLLOWER",
  "leaderId": "tad-1"
}
```

Use this in your load balancer health checks and monitoring dashboards.

### Cache Hit Rate

The `CachingTrustRoot` tracks L1 and L2 hit rates. A healthy deployment should see:

- **L1 hit rate > 95%** — most key lookups served from memory
- **L2 hit rate > 99%** — nearly all lookups served without hitting the TAD

If L1 drops below 90%, consider increasing `l1-max-size`.

### What to Alert On

| Condition                               | Severity | Action                                        |
|-----------------------------------------|----------|-----------------------------------------------|
| TAD leader unreachable > `stale-window` | 🔴 Critical | Verifiers will reject new issuers. Check TAD cluster. |
| L1 cache hit rate < 90%                 | 🟡 Warning  | Increase `l1-max-size` or check for key cardinality explosion. |
| No TAD leader elected                   | 🔴 Critical | Raft consensus lost. Check network between TAD nodes. |
| Full sync failures                      | 🟡 Warning  | TAD may be overloaded. Check `full-sync-interval`. |
| Broker propagation lag > 5s             | 🟡 Warning  | Kafka consumer lag or DB polling too slow.     |

---

## Configuration Hierarchy

Veridot supports a three-level configuration hierarchy. Lower levels override higher ones:

```
Global defaults
  └── Site overrides
        └── Group overrides
```

This lets you set sane defaults and override them for specific use cases.

### Example: Session Capacity Tiers

ShopFlow offers free and premium tiers with different session limits:

```yaml title="application.yml"
veridot:
  session:
    max-active: 10          # Global default: 10 concurrent sessions per user
```

For premium customers, override at the group level:

```java title="ShopFlow — order-service"
// Premium customer: allow up to 100 concurrent sessions
String jwt = signer.sign(
    new OrderContext(orderId, "CREATED"),
    BasicConfigurer.builder()
        .groupId("customer-" + premiumCustomerId)
        .sequenceId("session-" + sessionId)
        .maxActiveSequences(100)   // Group-level override
        .validity(3600)
        .build()
);
```

The hierarchy means you don't need to repeat configuration everywhere:

| Level    | Setting              | Value | Effect                                  |
|----------|----------------------|-------|-----------------------------------------|
| Global   | `max-active`         | 10    | Free-tier users get 10 sessions         |
| Group    | `maxActiveSequences` | 100   | Premium users get 100 sessions          |

---

## What's Next?

You've built ShopFlow from problem to production. Here's where to go for deeper dives:

- **[Security Model](/docs/architecture/security-model)** — threat model, key custody invariants, and trust boundaries
- **[Protocol V4 Specification](/docs/protocol/v4/)** — wire format, entry types, key epochs, and error codes
- **[Performance Tuning](/docs/architecture/performance)** — benchmarks, cache sizing, and broker optimization
- **[Architecture Decision Records](/docs/architecture/adr/)** — the *why* behind every major design choice
- **[Distribution Modes Guide](/docs/guides/distribution-modes)** — detailed configuration for DIRECT, INDIRECT, and PRIVATE
- **[Environment Variables](/docs/guides/environment-variables)** — complete reference for all configuration options

:::tip[12 Use Cases]
Veridot isn't just for e-commerce. The architecture applies to IoT device attestation, multi-tenant SaaS, financial transaction signing, and more. Check the [Guides](/docs/guides/core-concepts) section for patterns across different domains.
:::
