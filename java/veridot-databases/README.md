# veridot-databases

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-databases.svg)](https://central.sonatype.com/artifact/io.github.cyfko/veridot-databases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../../LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)

SQL database-backed implementation of the [`Broker`](../veridot-core/src/main/java/io/github/cyfko/veridot/core/Broker.java) interface (Protocol V4).

Use this broker when your infrastructure already runs a relational database and you want to avoid adding Kafka as a dependency. Verification reads hit the database — latency is higher than the Kafka+RocksDB approach, but still adequate for most use cases.

---

## When to choose this broker vs veridot-kafka

| Criterion | `veridot-databases` | `veridot-kafka` |
|-----------|:-------------------:|:---------------:|
| Verification latency | ~1–5ms (DB round-trip) | <1ms (local RocksDB) |
| Infrastructure | Any JDBC DataSource | Kafka 3.x cluster |
| Horizontal scale | Database replication | Kafka consumer group |
| Existing stack | DB-centric apps | Event-driven / streaming apps |
| Revocation propagation | Immediate (same DB) | ~500ms Kafka poll |

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
    <artifactId>veridot-databases</artifactId>
    <version>4.0.0</version>
</dependency>
```

```gradle
// Gradle
implementation 'io.github.cyfko:veridot-core:4.0.0'
implementation 'io.github.cyfko:veridot-databases:4.0.0'
```

---

## Creating the broker

```java
import io.github.cyfko.veridot.databases.DatabaseBroker;

DataSource dataSource = createDataSource(); // your JDBC DataSource

// Default table name: "broker_messages"
Broker broker = new DatabaseBroker(dataSource);

// Custom table name (useful if you have naming conventions)
Broker broker = new DatabaseBroker(dataSource, "veridot_metadata");
```

The broker **auto-creates the table** on startup if it doesn't exist. No manual DDL required.

---

## Database table schema

```sql
-- Created automatically on first use
CREATE TABLE broker_messages (
    storage_key   VARBINARY(4096) NOT NULL PRIMARY KEY,
    payload       BLOB            NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

- `storage_key` is the binary Protocol V4 storage key: `scope ‖ 0x00 ‖ entryType.code ‖ 0x00 ‖ key`.
- `payload` holds the TLV-encoded protocol envelope.
- **Physical deletion**: a `put(key, new byte[0])` triggers a SQL `DELETE` on the matching row (not an upsert of empty bytes).

> **Table name validation**: only alphanumeric characters and underscores are allowed. This prevents SQL injection at the constructor level.

---

## DataSource examples

### PostgreSQL (HikariCP)

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:postgresql://db-host:5432/mydb");
config.setUsername("veridot");
config.setPassword(System.getenv("DB_PASSWORD"));
config.setMaximumPoolSize(10);
config.setConnectionTimeout(3000);

DataSource ds = new HikariDataSource(config);
Broker broker = new DatabaseBroker(ds);
```

### MySQL / MariaDB

```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:mysql://db-host:3306/mydb?useSSL=true&requireSSL=true");
config.setUsername("veridot");
config.setPassword(System.getenv("DB_PASSWORD"));

DataSource ds = new HikariDataSource(config);
Broker broker = new DatabaseBroker(ds, "veridot_meta");
```

### H2 (development / tests)

```java
DataSource ds = new EmbeddedDatabaseBuilder()
    .setType(EmbeddedDatabaseType.H2)
    .build();
Broker broker = new DatabaseBroker(ds);
```

### Spring Boot (auto-configuration)

```java
@Bean
public Broker veridotBroker(DataSource dataSource) {
    return new DatabaseBroker(dataSource, "veridot_metadata");
}
```

---

## Complete usage example

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.databases.DatabaseBroker;

// Build the broker
Broker broker = new DatabaseBroker(dataSource);

// Build TrustRoot (see veridot-core README for full TrustRoot documentation)
TrustRoot trust = new PublicKeyTrustRoot(signerId -> {
    byte[] pem = Files.readAllBytes(Paths.get("/etc/veridot/trust/" + signerId + ".pub.pem"));
    return parsePemPublicKey(pem);
});

// Load long-term private key
PrivateKey longTermKey = loadPrivateKey("/etc/veridot/private.key");

// Build signer/verifier
var sv = new GenericSignerVerifier(broker, trust, "api-gateway", longTermKey, 5, EvictionPolicy.FIFO);

// Sign — DIRECT mode (JWT returned)
String jwt = sv.sign("user@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .validity(3600)
        .build());

// Sign — INDIRECT mode (large/sensitive payload stored in DB, caller gets messageId)
String messageId = sv.sign(sensitiveDocument,
    BasicConfigurer.builder()
        .groupId("docs")
        .sequenceId("contract-789")
        .validity(86400)
        .distribution(DistributionMode.INDIRECT)
        .serializedBy(obj -> mapper.writeValueAsString(obj))
        .build());

// Verify (works with both JWT and messageId)
VerifiedData<String> result = sv.verify(jwt, s -> s);

// Revoke
sv.revoke("user-123", "device-A"); // one session
sv.revoke("user-123", null);       // all sessions for user-123
```

---

## Distributed consistency

- Point all service instances to the **same database** (or to synchronized read replicas for verification).
- The primary key on `storage_key` prevents duplicate entries without distributed coordination.
- Revocation is immediately visible to any instance that queries the same database row.
- If you use read replicas, revocations may be subject to replication lag. For strict revocation consistency, route verification reads to the primary.

---

## Tested databases

| Database | Version | Notes |
|----------|---------|-------|
| PostgreSQL | 13+ | Recommended for production |
| MySQL | 8+ | Requires `useSSL=true` in production |
| MariaDB | 11+ | Full support |
| SQL Server | 2019+ | Full support |
| H2 | Any | Development and testing only |

---

## Requirements

- Java 17+ (this module only; `veridot-core` requires Java 25+)
- A JDBC-compatible `DataSource`
- No additional infrastructure beyond your existing database

---

## See also

- [`veridot-core/README.md`](../veridot-core/README.md) — full usage guide (TrustRoot, Spring Boot, session management)
- [`veridot-kafka/README.md`](../veridot-kafka/README.md) — Kafka broker (lower latency, recommended for high-throughput)

---

## License

[MIT](../../LICENSE) · **Kunrin SA** · [frank.kossi@kunrin.com](mailto:frank.kossi@kunrin.com)
