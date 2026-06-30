# Contributing to Veridot

Thank you for your interest in contributing to Veridot! As an enterprise-grade security library, we maintain strict code quality, security checks, and testing requirements.

---

## 1. Development Prerequisites

To compile and test Veridot locally, you need:
- **Java Development Kit (JDK) 25** or higher.
- **Apache Maven 3.9** or higher (or use the bundled `./mvnw` wrapper).
- **Docker** running locally (required to run integration tests via Testcontainers).

---

## 2. Project Directory Structure

```
veridot/
├── java/
│   ├── veridot-core/        # Core interfaces, TLV serialization, state engine
│   ├── veridot-kafka/       # Kafka Broker + RocksDB cache integration
│   ├── veridot-databases/   # Relational DB broker (JDBC)
│   └── veridot-tests/       # Integration & performance tests
├── PROTOCOL_V4.md           # Protocol V4 Wire Format Specification
└── KEY_ROTATION.md          # Operational key rotation manual
```

---

## 3. Building and Running Tests

### Compile the Library
To build the library and install it in your local Maven repository:
```bash
./mvnw clean install -DskipTests
```

### Running Unit Tests
To run unit tests (does not require Docker):
```bash
./mvnw test -pl veridot-core
```

### Running Integration Tests
Integration tests run against real database instances (PostgreSQL, MySQL, MariaDB, SQL Server) and Kafka using **Testcontainers**. Make sure Docker is running before executing:
```bash
./mvnw test -pl veridot-tests
```

---

## 4. Code & Security Style Guidelines

- **Maintain Documentation**: Do not remove Javadocs or comments unless modifying the corresponding behavior. All new public APIs must be documented.
- **Timing Attacks Defense**: Always use timing-safe comparisons for cryptographic signatures and token hashes.
- **Null Safety**: Annotate parameters and return values with Jakarta or JetBrains Nullability annotations where applicable, or enforce null checks via `Objects.requireNonNull`.
- **Commit Messages**: We enforce Conventional Commits:
  - `feat(core): ...` for new features
  - `fix(kafka): ...` for bug fixes
  - `docs: ...` for documentation updates
  - `test: ...` for adding tests
