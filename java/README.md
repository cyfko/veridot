# Veridot — Java

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-core.svg)](https://central.sonatype.com/artifact/io.github.cyfko/veridot-core)
[![Build](https://img.shields.io/badge/tests-89%20passed-brightgreen.svg)](veridot-core/)
[![Protocol V2.1](https://img.shields.io/badge/Protocol-V2.1-green.svg)](../PROTOCOL_V2.md)

Java implementation of the Veridot distributed token verification protocol.

---

## Modules

| Module | Artifact | Java | Role |
|--------|----------|:----:|------|
| [`veridot-core`](veridot-core/) | `io.github.cyfko:veridot-core:3.0.2` | 25+ | Core API, `GenericSignerVerifier`, `TrustAnchor`, Protocol V2 |
| [`veridot-kafka`](veridot-kafka/) | `io.github.cyfko:veridot-kafka:3.0.2` | 17+ | `MetadataBroker` backed by Kafka + RocksDB |
| [`veridot-databases`](veridot-databases/) | `io.github.cyfko:veridot-databases:3.0.2` | 17+ | `MetadataBroker` backed by SQL (PostgreSQL, MySQL…) |
| [`veridot-tests`](veridot-tests/) | _(internal)_ | 25+ | Integration tests across all brokers |

> **Start reading here**: [`veridot-core/README.md`](veridot-core/README.md) — self-contained reference for all developers. No external site required.

---

## Choosing a broker

```
Your application needs to distribute public key metadata across service instances.
Choose the broker that matches your infrastructure:

  ┌─── Already running Kafka? ───────► veridot-kafka   (recommended)
  │      Sub-ms reads via RocksDB
  │      Fan-out to all consumers automatically
  │
  └─── No Kafka / prefer SQL? ───────► veridot-databases
         Works with any JDBC DataSource
         Good for existing DB-centric stacks
         Polling-based propagation
```

---

## Running tests

```bash
# Unit tests — no external dependencies
./mvnw test -pl veridot-core --no-transfer-progress

# Integration tests — requires Docker (Testcontainers)
./mvnw test -pl veridot-tests --no-transfer-progress
```

**v3.0.2 results**: 89 unit tests, 0 failures, 0 errors.

---

## License

[MIT](../LICENSE) · **Kunrin SA** · [frank.kossi@kunrin.com](mailto:frank.kossi@kunrin.com)
