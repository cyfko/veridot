# Veridot — Distributed Token Verification

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Protocol V2](https://img.shields.io/badge/Protocol-V2-green.svg)](java/.agent/PROTOCOL_V2.md)

**Enterprise-grade distributed token verification** for microservices architectures where any service may need to verify any incoming token — without shared secrets.

Veridot implements **Protocol V2**, a binary-safe canonical message format with structured revocation, distributed configuration, and cryptographic clock-drift validation.

---

## ✨ Key Features

- 🔐 **Ephemeral RSA key pairs** with automatic rotation
- 📡 **Distributed public key propagation** via pluggable brokers (Kafka, SQL databases)
- 🔄 **Two distribution modes**: DIRECT (JWT returned to caller) or INDIRECT (messageId reference)
- ⚡ **Session capacity management** with FIFO/LIFO/LRU/REJECT eviction policies
- 📢 **Structured revocation** (`__REVOKE__`) for cross-processor interoperability
- ⚙️ **Distributed configuration** (`__CONFIG__`) with local → site → global hierarchy
- ⏱️ **Clock drift validation** (±5 minutes, §9.1)
- 🔍 **Token tracking** — query active tokens by group, JWT, or messageId

---

## 📦 Project Structure

| Module | Artifact | Description |
|--------|----------|-------------|
| [`veridot-core`](veridot-core/) | `io.github.cyfko:veridot-core` | Core API: interfaces, Protocol V2 implementation, `GenericSignerVerifier` |
| [`veridot-kafka`](veridot-kafka/) | `io.github.cyfko:veridot-kafka` | Kafka-based `MetadataBroker` with RocksDB local cache |
| [`veridot-databases`](veridot-databases/) | `io.github.cyfko:veridot-databases` | SQL database-backed `MetadataBroker` (PostgreSQL, MySQL, MariaDB…) |
| [`veridot-tests`](veridot-tests/) | — | Integration tests across all broker implementations |

---

## 🚀 Quick Start

### 1. Add dependencies

**Maven** (with Kafka broker):
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>2.1.3</version>
</dependency>
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>2.1.3</version>
</dependency>
```

**Maven** (with Database broker):
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>2.1.3</version>
</dependency>
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-databases</artifactId>
    <version>2.1.3</version>
</dependency>
```

### 2. Sign, verify, and revoke

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.*;

// Create broker (Kafka or Database — see module READMEs)
MetadataBroker broker = createBroker();

// Create signer/verifier (implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker)
var sv = new GenericSignerVerifier(broker, "my-secret-salt");

// Sign data → returns JWT (DIRECT mode, default)
String jwt = sv.sign("john@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .validity(300)  // 5 minutes
        .build());

// Verify token → extract original data
String email = sv.verify(jwt, String::toString);

// Revoke when needed
sv.revoke(jwt);

// Check if group has active tokens
boolean active = sv.hasActiveToken("user-123");
```

---

## 🔄 How It Works

```
┌──────────────┐     sign()      ┌──────────────────────┐
│ Issuer       │ ──────────────→ │ GenericSignerVerifier │
│ Service      │                 │                      │
└──────────────┘                 │  1. Generate key pair│
                                 │  2. Sign JWT         │
                                 │  3. Publish V2 msg   │
                                 └──────────┬───────────┘
                                            │ send(messageId, v2Message)
                                            ▼
                                 ┌──────────────────────┐
                                 │   MetadataBroker     │
                                 │  (Kafka / Database)  │
                                 └──────────┬───────────┘
                                            │ get(messageId)
                                            ▼
┌──────────────┐    verify()     ┌──────────────────────┐
│ Consumer     │ ──────────────→ │ GenericSignerVerifier │
│ Service      │ ←────────────── │  (any instance)      │
└──────────────┘   deserialized  └──────────────────────┘
                     data
```

---

## 🧪 Running Tests

```bash
# Unit tests (core)
./mvnw -pl veridot-core test

# Integration tests (requires Docker for Testcontainers)
./mvnw -pl veridot-tests test
```

**Test coverage**: 169 tests across 11 suites (68 unit + 101 integration).

---

## 📋 Protocol V2

Veridot implements [Protocol V2](https://cyfko.github.io/veridot) — a self-describing canonical message format:

```
2:<groupId>:<sequenceId>|mode:<b64>,pubkey:<b64>,timestamp:<b64>,ttl:<b64>
```

Key sections:
- **§3** Normal messages (sign/verify lifecycle)
- **§4** Distributed configuration (`__CONFIG__`)
- **§5** Structured revocation (`__REVOKE__`)
- **§9.1** Clock drift validation (±5 minutes)

Full specification: [PROTOCOL_V2.md](.agent/PROTOCOL_V2.md)

---

## 📌 Requirements

- Java ≥ 17
- Maven 3.9+ (wrapper included)

---

## 📄 License

[MIT License](LICENSE)

---

**Maintained by** [Frank KOSSI](mailto:frank.kossi@kunrin.com) — [Kunrin SA](https://www.kunrin.com)
