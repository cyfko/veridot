# veridot-kafka

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-kafka.svg)](https://central.sonatype.com/artifact/io.github.cyfko/veridot-kafka)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../../LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)

Kafka + RocksDB implementation of the [`MetadataBroker`](../veridot-core/src/main/java/io/github/cyfko/veridot/core/MetadataBroker.java) interface.

This is the **recommended broker** for production deployments. Key announcements are broadcast via Kafka and cached locally in RocksDB — verification reads never hit the network.

---

## How it works

```
Signer node                          Verifier nodes (all)
───────────                          ─────────────────────
send(key, msg)                       Kafka consumer loop
  ├─ sendLocal() → RocksDB           ├─ poll() every ~500ms
  └─ Kafka producer → topic          ├─ write to RocksDB
                                     └─ verify() reads RocksDB (<1ms)

revoke() → __REVOKE__ tombstone  →   Kafka consumer
                                     └─ delete from RocksDB
```

- **Fan-out**: one Kafka topic, all consumers receive all announcements.
- **Local cache**: RocksDB handles all `get()` calls without network I/O.
- **Race-free**: `sendLocal()` pre-populates RocksDB before the Kafka send, so `verify()` on the same node works immediately.
- **Compaction**: expired entries (`timestamp + ttl + 300 < now`) are purged every 5 minutes.

---

## Installation

```xml
<!-- Maven -->
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>3.1.0</version>
</dependency>
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>3.1.0</version>
</dependency>
```

```gradle
// Gradle
implementation 'io.github.cyfko:veridot-core:3.1.0'
implementation 'io.github.cyfko:veridot-kafka:3.1.0'
```

---

## Creating the broker

### Programmatic configuration (recommended for production)

```java
import io.github.cyfko.veridot.kafka.KafkaMetadataBrokerAdapter;

Properties props = new Properties();

// Required
props.setProperty("bootstrap.servers", "kafka1:9092,kafka2:9092");
props.setProperty("embedded.db.path", "/var/lib/veridot/rocksdb");

// TLS + SASL (recommended in production)
props.setProperty("security.protocol", "SASL_SSL");
props.setProperty("sasl.mechanism", "SCRAM-SHA-512");
props.setProperty("sasl.jaas.config",
    "org.apache.kafka.common.security.scram.ScramLoginModule required " +
    "username=\"veridot-svc\" password=\"${KAFKA_PASSWORD}\";");
props.setProperty("ssl.endpoint.identification.algorithm", "https");
props.setProperty("ssl.truststore.location", "/etc/ssl/kafka.truststore.jks");
props.setProperty("ssl.truststore.password", System.getenv("TRUSTSTORE_PASSWORD"));

MetadataBroker broker = KafkaMetadataBrokerAdapter.of(props);
```

### Environment-variable configuration (simple deployments)

```java
// Reads all config from environment variables (see table below)
MetadataBroker broker = KafkaMetadataBrokerAdapter.of();
```

---

## Configuration reference

### Kafka properties passed to `KafkaMetadataBrokerAdapter.of(props)`

All standard Kafka producer/consumer properties are supported. The most relevant:

| Property | Required | Description | Default |
|----------|:--------:|-------------|---------|
| `bootstrap.servers` | ✅ | Kafka broker addresses | — |
| `embedded.db.path` | ✅ | RocksDB directory path | — |
| `security.protocol` | — | `PLAINTEXT`, `SSL`, `SASL_PLAINTEXT`, `SASL_SSL` | `PLAINTEXT` |
| `sasl.mechanism` | — | `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512` | — |
| `sasl.jaas.config` | — | JAAS login config string | — |
| `ssl.truststore.location` | — | Path to JKS truststore | — |
| `ssl.truststore.password` | — | Truststore password | — |
| `ssl.endpoint.identification.algorithm` | — | `https` to enforce hostname verification | — |
| `compression.type` | — | `none`, `gzip`, `snappy`, `lz4` | `none` |

### Environment variables (used by `KafkaMetadataBrokerAdapter.of()`)

| Variable | Description | Default |
|----------|-------------|---------|
| `VDOT_KAFKA_BOOSTRAP_SERVERS` | Kafka bootstrap servers | `localhost:9092` |
| `VDOT_TOKEN_VERIFIER_TOPIC` | Kafka topic for metadata | `token-verifier` |
| `VDOT_EMBEDDED_DATABASE_PATH` | RocksDB storage path | temp dir |
| `VDOT_KEYS_ROTATION_MINUTES` | Ephemeral key rotation interval | `1440` (24h) |

---

## Revocation processing

When a `__REVOKE__` tombstone is received from the Kafka topic, the consumer automatically:

- **Single-session revocation** (`target=<sequenceId>`): deletes `2:<groupId>:<sequenceId>` from RocksDB.
- **Group-wide revocation** (`target=__ALL__`): deletes all normal sequences for `groupId` from RocksDB (preserves `__REVOKE__` and `__CONFIG__` entries).

Revocation propagates to all consumers within the next Kafka poll interval (~500ms by default, ~1s p99 under normal load).

### Tombstone authenticity (v3.0.2)

Since v3.0.2, tombstones carry `tombstoneSig` — a long-term RSA signature. The consumer verifies this signature via the `TrustAnchor` before applying the revocation. A node with Kafka write-access cannot forge a valid tombstone.

---

## Kafka topic and ACL recommendations

```bash
# Create the topic (replication factor 3 for production)
kafka-topics.sh --create \
  --topic token-verifier \
  --replication-factor 3 \
  --partitions 12 \
  --bootstrap-server kafka:9092

# ACL: signing services may produce
kafka-acls.sh --add --allow-principal User:veridot-signer \
  --operation Write --topic token-verifier \
  --bootstrap-server kafka:9092

# ACL: verifying services may consume
kafka-acls.sh --add --allow-principal User:veridot-verifier \
  --operation Read --topic token-verifier \
  --bootstrap-server kafka:9092
```

**Tip**: use a dedicated topic (`token-verifier`) with aggressive retention. A 7-day retention is sufficient — ephemeral keys rotate every 24h and are compacted from RocksDB on expiry.

---

## Cluster considerations

- All service instances connected to the **same Kafka cluster and topic** automatically share metadata.
- RocksDB is a **local per-instance store**. Each pod has its own RocksDB directory.
- In Kubernetes: mount RocksDB on an `emptyDir` or ephemeral volume — persistence across pod restarts is not required (the Kafka consumer rehydrates from the topic on startup).
- The consumer uses an **auto-assigned `groupId`** so every instance receives all messages (broadcast semantics).

---

## Requirements

- Java 17+ (this module only; `veridot-core` requires Java 25+)
- Apache Kafka 3.x or later
- RocksDB native libraries (included transitively)

---

## License

[MIT](../../LICENSE) · **Kunrin SA** · [frank.kossi@kunrin.com](mailto:frank.kossi@kunrin.com)
