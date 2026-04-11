# veridot-kafka

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-kafka.svg)](https://central.sonatype.com/artifact/io.github.cyfko/veridot-kafka)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

Kafka-based implementation of the [`MetadataBroker`](https://github.com/cyfko/veridot/blob/main/java/veridot-core/src/main/java/io/github/cyfko/veridot/core/MetadataBroker.java) interface for **Veridot**, enabling distributed token verification metadata propagation via Apache Kafka with **RocksDB** local persistence.

---

## ✨ Features

- 🔐 **JWT Signing & Verification** using ephemeral RSA key pairs
- 🔁 **Automatic Key Rotation** (configurable interval)
- 📬 **Public key distribution** via Kafka topics
- 🧠 **RocksDB** embedded store for ultra-fast local lookups
- 📢 **`__REVOKE__` message processing** — Kafka consumer parses structured revocation messages and deletes targeted sequences from RocksDB
- ⚙️ **Environment-based configuration** with sensible defaults

---

## 📦 Installation

**Maven**:
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>2.1.2</version>
</dependency>
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>2.1.2</version>
</dependency>
```

**Gradle**:
```gradle
implementation 'io.github.cyfko:veridot-core:2.1.2'
implementation 'io.github.cyfko:veridot-kafka:2.1.2'
```

> ⚠️ This project follows [Semantic Versioning](https://semver.org/).

---

## ⚙️ Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `VDOT_KAFKA_BOOSTRAP_SERVERS` | Kafka bootstrap servers | `localhost:9092` |
| `VDOT_TOKEN_VERIFIER_TOPIC` | Kafka topic for verification metadata | `token-verifier` |
| `VDOT_EMBEDDED_DATABASE_PATH` | RocksDB storage path | `veridot_db_data` (temp dir) |
| `VDOT_KEYS_ROTATION_MINUTES` | RSA key pair rotation interval (minutes) | `1440` (24h) |

---

## 🚀 Usage

### Creating the broker

```java
import io.github.cyfko.veridot.kafka.KafkaMetadataBrokerAdapter;
import io.github.cyfko.veridot.core.MetadataBroker;

// Using environment variables (simplest)
MetadataBroker broker = KafkaMetadataBrokerAdapter.of();

// Using custom properties
Properties props = new Properties();
props.put("bootstrap.servers", "kafka-cluster:9092");
MetadataBroker broker = KafkaMetadataBrokerAdapter.of(props);
```

### Sign, verify, and revoke

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.*;

// Create signer/verifier with the Kafka broker
var sv = new GenericSignerVerifier(broker, "my-secret-salt");

// Sign data — returns JWT (DIRECT mode)
String jwt = sv.sign("john@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .validity(300)  // 5 minutes
        .build());

// Verify from any service connected to the same Kafka cluster
String email = sv.verify(jwt, String::toString);

// Revoke
sv.revoke(jwt);

// Verify after revocation → throws BrokerExtractionException
sv.verify(jwt, String::toString); // throws!
```

### With session management

```java
// Max 3 concurrent sessions per user, FIFO eviction
var sv = new GenericSignerVerifier(broker, "salt", 3, GenericSignerVerifier.EvictionPolicy.FIFO);

// Track active sessions
boolean hasActive = sv.hasActiveToken("user-123");

// Revoke all sessions for a user
sv.revokeGroup("user-123");
```

---

## 🔄 Kafka Consumer — `__REVOKE__` Processing

The Kafka consumer automatically processes Protocol V2 `__REVOKE__` messages:

- **`target=<sequenceId>`** → Deletes the specific sequence from RocksDB
- **`target=__ALL__`** → Deletes all normal sequences for the group (preserves `__REVOKE__` and `__CONFIG__` entries)

This ensures that when one processor revokes a session, all other processors consuming the same topic will reflect the revocation in their local RocksDB stores.

---

## 📌 Requirements

- Java ≥ 17
- Apache Kafka cluster (tested with Kafka 3.9.x / Confluent 7.6.x)

---

## 🔐 Security Considerations

- Ephemeral RSA key pairs with configurable rotation
- All public keys are persisted and verified from **RocksDB**
- Only keys within the TTL window are accepted
- ±5 minute clock drift validation (Protocol V2 §9.1)

---

## 📄 License

[MIT License](../LICENSE)
