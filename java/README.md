# Veridot — Java

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-core.svg)](https://central.sonatype.com/artifact/io.github.cyfko/veridot-core)
[![Protocol V5.0](https://img.shields.io/badge/Protocol-V5.0-green.svg)](../PROTOCOL_V5.md)

Java implementation of the Veridot distributed authenticity and integrity protocol.

---

## Protocol V5 Highlights

- **Instance-scoped identity**: Each instance generates one keypair, registered via TAAS with attestation proof. Subject format: `CN@base64url(SHA-256(pk))[0:32]`
- **TAAS (Trust Authority & Attestation Service)**: Raft-replicated cluster for key registration, attestation verification, and trust resolution
- **Post-quantum hybrid support**: Ed25519+ML-DSA-65, ECDSA-P256+ML-DSA-65, and standalone ML-DSA-65 (FIPS 204)
- **State transparency**: TAAS digest verification, liveness gap detection, and capability version monitoring
- **New entry types**: `SIGNED_DATA` (native mode), `AUDIT_ANCHOR`, `TRUST_REVOCATION`
- **Wire format**: u16 flags register, proto version `0x05`, no KEY_EPOCH

---

## Modules

| Module | Artifact | Java | Role |
|--------|----------|:----:|------|
| [`veridot-core`](veridot-core/) | `io.github.cyfko:veridot-core:5.0.0-SNAPSHOT` | 25+ | Core API, `GenericSignerVerifier`, `TrustRoot`, Protocol V5 |
| [`veridot-kafka`](veridot-kafka/) | `io.github.cyfko:veridot-kafka:5.0.0-SNAPSHOT` | 17+ | `Broker` backed by Kafka + RocksDB |
| [`veridot-databases`](veridot-databases/) | `io.github.cyfko:veridot-databases:5.0.0-SNAPSHOT` | 17+ | `Broker` backed by SQL (PostgreSQL, MySQL…) |
| [`veridot-trustroots`](veridot-trustroots/) | `io.github.cyfko:veridot-trustroots-*:5.0.0-SNAPSHOT` | 25+ | TAAS client/server, trust root caching, attestation SPI |
| [`veridot-tests`](veridot-tests/) | _(internal)_ | 25+ | Integration tests across all brokers |

> **Start reading here**: [`veridot-core/README.md`](veridot-core/README.md) — self-contained reference for all developers.

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
./mvnw test -pl veridot-tests -am --no-transfer-progress
```

---

## License

[MIT](../LICENSE) · **Kunrin SA** · [frank.kossi@kunrin.com](mailto:frank.kossi@kunrin.com)
