# veridot-kafka

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-kafka.svg)](https://central.sonatype.com/artifact/io.github.cyfko/veridot-kafka)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../../LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)

Kafka + RocksDB implementation of the [`Broker`](../veridot-core/src/main/java/io/github/cyfko/veridot/core/Broker.java) interface (Protocol V4).

This is the **recommended broker** for production deployments. Protocol entries are broadcast via Kafka and cached locally in RocksDB — verification reads never hit the network.

---

## How it works

```
Signer node                          Verifier nodes (all)
───────────                          ─────────────────────
put(key, bytes)                      Kafka consumer loop
  ├─ write to local RocksDB           ├─ poll() every ~500ms
  └─ Kafka producer → topic           ├─ write/delete RocksDB entries
                                      └─ get() reads local RocksDB (<1ms)

put(key, new byte[0]) → tombstone →  Kafka consumer
                                      └─ delete from RocksDB
```

- **Fan-out**: one Kafka topic, all consumers receive all entries.
- **Local cache**: RocksDB handles all `get()` calls without network I/O.
- **Race-free**: the signer writes to local RocksDB before the Kafka send, so `verify()` on the same node succeeds immediately.
- **Tombstone deletion**: a `put(key, new byte[0])` publishes a Kafka null-payload tombstone and deletes the local RocksDB entry.
- **TTL compaction**: expired entries are purged automatically by RocksDB's built-in TTL support.

---

## Installation

```xml
<!-- Maven -->
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>4.0.0</version>
</dependency>
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>4.0.0</version>
</dependency>
```

```gradle
// Gradle
implementation 'io.github.cyfko:veridot-core:4.0.0'
implementation 'io.github.cyfko:veridot-kafka:4.0.0'
```

---

## Creating the broker

### Programmatic configuration (recommended for production)

```java
import io.github.cyfko.veridot.kafka.KafkaBroker;

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

Broker broker = KafkaBroker.of(props);
```

### Environment-variable configuration (simple deployments)

```java
// Reads all config from environment variables (see table below)
Broker broker = KafkaBroker.of();
```

The broker implements `AutoCloseable` — always close it when your application shuts down:

```java
try (Broker broker = KafkaBroker.of(props)) {
    // use broker
}
```

---

## Configuration reference

### Kafka properties passed to `KafkaBroker.of(props)`

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

### Environment variables (used by `KafkaBroker.of()`)

| Variable | Description | Default |
|----------|-------------|---------|
| `VDOT_KAFKA_BOOSTRAP_SERVERS` | Kafka bootstrap servers | `localhost:9092` |
| `VDOT_TOKEN_VERIFIER_TOPIC` | Kafka topic for protocol entries | `token-verifier` |
| `VDOT_EMBEDDED_DATABASE_PATH` | RocksDB storage path | temp dir |
| `VDOT_KEYS_ROTATION_MINUTES` | Ephemeral key rotation interval | `1440` (24h) |

---

## Deletion semantics

`KafkaBroker` intercepts `put(key, new byte[0])` (zero-length payload) as a physical deletion signal:

1. The local RocksDB entry is deleted immediately.
2. A Kafka tombstone (null-value record) is produced to the topic.
3. All consuming instances receive the tombstone and delete their local copy.

This is how `GenericSignerVerifier` implements session revocation — no special revocation protocol is needed at the broker level.

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

**Tip**: use a dedicated topic (`token-verifier`) with retention long enough to cover the maximum key TTL. A 7-day retention is sufficient for default settings.

---

## Cluster considerations

- All service instances connected to the **same Kafka cluster and topic** automatically share protocol state.
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
