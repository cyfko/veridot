# Veridot — Distributed Token Verification Protocol

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Protocol V3](https://img.shields.io/badge/Protocol-V3.0-green.svg)](PROTOCOL_V3.md)
[![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)](java/veridot-core)
[![Trust Architecture](https://img.shields.io/badge/Trust-TrustAnchor%20v3.0-purple.svg)](docs/adr/)
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

## Protocol V3 specification → [`PROTOCOL_V3.md`](PROTOCOL_V3.md)

Binary-safe canonical message format enabling cross-language interoperability. Any conforming implementation — regardless of language — can verify tokens produced by any other.

Current version: **3.0** — adds universal signature over length-prefixed canonical encoding and shortened property names.

---

## Documentation site → [cyfko.github.io/veridot](https://cyfko.github.io/veridot)

Deeper reading: security threat model, Architecture Decision Records, and production deployment guidance.

---

## License

[MIT](LICENSE) · **Kunrin SA** · [frank.kossi@kunrin.com](mailto:frank.kossi@kunrin.com)
