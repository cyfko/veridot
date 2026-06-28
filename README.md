# Veridot — Distributed Token Verification Protocol

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Protocol V4](https://img.shields.io/badge/Protocol-V4.0-green.svg)](PROTOCOL_V4.md)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](java/veridot-core)
[![Trust Architecture](https://img.shields.io/badge/Trust-TrustRoot%20v4.0-purple.svg)](docs/adr/)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-core)](https://central.sonatype.com/search?q=io.github.cyfko.veridot)

**Veridot** solves the distributed authentication trilemma: verify tokens in **sub-millisecond** without a central authority, revoke them **instantly** across the cluster, and maintain **zero shared secrets** between services.

---

## The problem

Microservice token verification always forces a compromise:

| Approach | Revocable? | No shared secret? | No network call? |
|----------|:----------:|:-----------------:|:----------------:|
| Shared HMAC | ✅ | ❌ | ✅ |
| Stateless RSA/ECDSA JWT | ❌ | ✅ | ✅ |
| Centralized IdP call | ✅ | ✅ | ❌ |
| **Veridot** | ✅ | ✅ | ✅ |

Veridot achieves all three by combining **ephemeral asymmetric key pairs** with **distributed metadata propagation** and a **local RocksDB cache**.

---

## Java implementation → [`java/`](java/)

The primary implementation. Production-ready, Java 25+, 89 tests, 0 failures.

```bash
# Read the full implementation guide
cat java/README.md
cat java/veridot-core/README.md   # ← exhaustive reference
```

---

## Protocol V4 specification → [`PROTOCOL_V4.md`](PROTOCOL_V4.md)

Binary-safe canonical message format with sealed TrustRoot interface, cryptographic capability authorization, and distributed liveness attestation. Any conforming implementation — regardless of language — can verify tokens produced by any other.

Current version: **4.0** — evolves from V3 with TrustRoot sealed interface, hierarchical configuration scope authorization, fenced session capacity, monotonic watermark tracking, and positive-proof liveness protocol.

---

## Documentation site → [cyfko.github.io/veridot](https://cyfko.github.io/veridot)

Deeper reading: security threat model, Architecture Decision Records, and production deployment guidance.

---

## License

[MIT](LICENSE) · **Kunrin SA** · [frank.kossi@kunrin.com](mailto:frank.kossi@kunrin.com)
