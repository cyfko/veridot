## 🧱 Project Structure

The **Veridot** project is structured to promote modularity, extensibility, and clean separation of concerns between contracts and implementations.

### 📦 `veridot-core`

> Location: [`/veridot-core`](https://github.com/cyfko/veridot/tree/main/java/veridot-core)

This module defines the **core API**, including:

* Fundamental interfaces like `DataSigner`, `TokenVerifier`, `MetadataBroker`, `DataTransformer`, and `TokenRevoker`.
* The interaction model between signing, verification, metadata propagation, and revocation.
* Common exceptions and domain contracts that govern token issuance and validation.

It is **implementation-agnostic**, serving as the backbone for any custom or prebuilt Veridot components.

---

### 🔌 `veridot-broker-kafka`

> Location: [`/veridot-kafka`](https://github.com/cyfko/veridot/tree/main/java/veridot-kafka)

This module provides a **Kafka-based implementation of `MetadataBroker`**, enabling ephemeral token metadata propagation over a distributed messaging system.

* Suited for high-throughput, event-driven environments.
* Leverages Kafka topics to broadcast and retrieve public keys or verification metadata.

---

### 🗃️ `veridot-broker-db`

> Location: [`/veridot-databases`](https://github.com/cyfko/veridot/tree/main/java/veridot-databases)

This module provides a **relational database-backed implementation of `MetadataBroker`**, using a persistent store (e.g., PostgreSQL, MySQL) to manage metadata.

* Designed for systems that prefer durability and transactional consistency.
* Useful in environments without Kafka or for audit-compliant workflows.

---

✅ veridot-tests

> Location: [`/veridot-tests`](https://github.com/cyfko/veridot/tree/main/java/veridot-tests)

This module contains integration and compatibility tests for the entire Veridot ecosystem:

Ensures consistent behavior across all MetadataBroker implementations.

Validates end-to-end scenarios such as token creation, propagation, verification, and revocation.

Can be extended to test custom implementations or edge cases.

To run the tests:

```shell
cd java/veridot-tests
./gradlew test
```

---

## 🔄 How It All Fits Together

Veridot enables distributed services to securely:

1. **Sign** payloads into short-lived, verifiable tokens.
2. **Propagate** signing metadata (e.g., public keys) using a broker.
3. **Verify** tokens with cryptographic integrity, retrieving metadata as needed.
4. Optionally **revoke** tokens by ID or content for security control.

The architecture encourages you to plug in different `MetadataBroker` implementations depending on your system's needs (e.g., Kafka, DB, in-memory, etc.), while sharing the same token issuing and verifying core.