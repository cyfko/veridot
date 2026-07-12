# Veridot — Distributed Authenticity and Integrity Protocol

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Protocol V5](https://img.shields.io/badge/Protocol-V5.0-green.svg)](PROTOCOL_V5.md)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](java/veridot-core)
[![Trust Architecture](https://img.shields.io/badge/Trust-TAAS%20v5.0-purple.svg)](PROTOCOL_V5.md)
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

Veridot achieves all three by combining **instance-scoped asymmetric key pairs** with **distributed metaasata propagation**, **attestation-first identity**, and a **local RocksDB cache**.

---

## Protocol V5

Version 5 introduces:

- **Attestation-first identity** — every instance backed by a cryptographic attestation proof (K8s PSAT, GCP IIT, TPM)
- **Instance-scoped subjects** — deterministic `CN@hash(pk)` identity, one keypair per instance lifetime
- **TAAS** (Trust Authority & Attestation Service) — Raft-replicated cluster for key registration and attestation verification
- **Post-quantum hybrid signatures** — Ed25519+ML-DSA-65, ECDSA-P256+ML-DSA-65 (FIPS 204)
- **State transparency** — TAAS digest verification, broker omission detection, liveness gap monitoring
- **New entry types** — `SIGNED_DATA` (native mode), `AUDIT_ANCHOR`, `TRUST_REVOCATION`
- **Wire format** — u16 flags register, proto `0x05`, RESERVED_01 eliminated

Full specification: [`PROTOCOL_V5.md`](PROTOCOL_V5.md)

---

## Java implementation → [`java/`](java/)

The primary implementation. Production-ready, Java 25+.

```bash
# Read the full implementation guide
cat java/README.md
cat java/veridot-core/README.md   # ← exhaustive reference
```

---

## Protocol V5 specification → [`PROTOCOL_V5.md`](PROTOCOL_V5.md)

Binary-safe canonical message format with attestation-first identity, instance-scoped subjects, TAAS trust authority, post-quantum hybrid signatures, and state transparency. Any conforming implementation — regardless of language — can verify tokens produced by any other.

---

## Documentation site → [cyfko.github.io/veridot](https://cyfko.github.io/veridot)

Deeper reading: security threat model, Architecture Decision Records, and production deployment guidance.

---

## License

[MIT](LICENSE) · **Kunrin SA** · [frank.kossi@kunrin.com](mailto:frank.kossi@kunrin.com)
