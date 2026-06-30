# Installation

Veridot requires **Java 25** or higher and is distributed via Maven Central.

The project is structured as modular components so you only pull in the dependencies required for your specific Broker transport layer.

---

## 1. Core Module (Required)

The `veridot-core` module contains all the interfaces, the state engine, security checks, and the standard in-memory structures.

### Maven
Add this dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>4.0.0</version>
</dependency>
```

### Gradle
Add this dependency to your `build.gradle` or `build.gradle.kts`:

```kotlin
implementation("io.github.cyfko:veridot-core:4.0.0")
```

---

## 2. Broker Implementations (Optional)

Choose **one** of the following broker integrations based on your infrastructure:

### Apache Kafka Broker (`veridot-kafka`)
Use this module to propagate verification metadata via a Kafka topic. It integrates **RocksDB** locally to persist metadata and allow fast, off-heap local caching on the verifier side.

#### Maven
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>4.0.0</version>
</dependency>
```

#### Gradle
```kotlin
implementation("io.github.cyfko:veridot-kafka:4.0.0")
```

### SQL Database Broker (`veridot-databases`)
Use this module to store and query verification metadata in a relational database. It supports automatic schema creation and custom upsert dialects for **PostgreSQL, MySQL, MariaDB, SQL Server, and Oracle**.

#### Maven
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-databases</artifactId>
    <version>4.0.0</version>
</dependency>
```

#### Gradle
```kotlin
implementation("io.github.cyfko:veridot-databases:4.0.0")
```

---

## 3. Transitive Dependencies

The library is designed to be lightweight and brings in minimal dependencies:
- **Jackson Databind** (for JSON serialization/deserialization)
- **Apache Kafka Clients** (only in `veridot-kafka`)
- **RocksDB Java** (only in `veridot-kafka`)

No additional external logging or utility libraries are forced upon you; Veridot uses Java's built-in `java.util.logging` system.
