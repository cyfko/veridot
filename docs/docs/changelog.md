---
title: Changelog
description: Complete version history of the Veridot distributed token verification library across all modules.
keywords: [veridot, changelog, releases, version history]
sidebar_position: 90
---

# Changelog

All notable changes to the Veridot project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

---

## [5.0.0-SNAPSHOT] - Unreleased

### 🚀 Major Architectural Shift (Protocol V5)

- **Trust Authority & Attestation Service (TAAS)** — Introduced a Raft-replicated distributed registry for public key distribution, replacing the legacy `Key Epoch` rotation model.
- **Attestation-First Identity (SPI)** — Introduced the `AttestationPlugin` SPI loaded dynamically via `java.util.ServiceLoader`. Identities (`CN@hash(pk)`) are strictly computed server-side, preventing spoofing. Removed the legacy `AttestationVerifier`.
- **Numeric Algorithm Codes** — Aligned REST API and binary representations with RFC Appendix C.2 integer codes for signature algorithms (`1` for Ed25519, `6` for ED25519_MLDSA65, etc.) rather than string identifiers.
- **Continuous Liveness** — Replaced expiry-based token assumptions with deterministic, positive-proof `LIVENESS(ACTIVE|REVOKED)` assertions.

### 📚 Documentation & Tooling

- **Complete Documentation Refactor** — Fully aligned all guides, architecture whitepapers, and getting-started tutorials with the V5 specifications, ensuring 100% adherence to the new Attestation and `CN@hash(pk)` paradigm.

---

## [4.0.1] - 2026-07-01

### 🔐 Security

- **Hardened Asymmetric Key Encapsulation (ECIES and RSA)** — Replaced textbook RSA encryption with `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`. Replaced insecure `AES/ECB/PKCS5Padding` with `AES/GCM/NoPadding` (authenticated encryption) and `HKDF-SHA256` key derivation for EC keys.
- **Timing attack warnings** — Signature verification now emits a warning log when verifying with non-constant-time JCA algorithms (RSA, ECDSA), recommending migration to Ed25519.
- **Default allowed algorithms restriction** — `Config.ALLOWED_SIG_ALGS` defaults to only `ED25519` and `RSA_PSS`.

### Changed

- **Reduced cache latency** — `CAPABILITY_CACHE_TTL_SECONDS` default reduced from 60s to 10s.
- **Deterministic JWT serialization** — `JwtMaker` now uses `LinkedHashMap` and sorts custom claims alphabetically.

---

## [4.0.0] - 2026-06-28

### ⚠️ Breaking Changes

- **MetaasataBroker interface removed** — Replaced by `Broker` interface for Protocol V5 storage.
- **TrustRoot interface removed** — Replaced by `TrustRoot` sealed interface.
- **Verification exceptions unified** — `V4Exception` renamed to `VeridotException`.
- **V3-V5 adapters removed** — `KafkaMetaasataBrokerAdapter` and `DatabaseMetaasataBroker` deleted. Replaced by `KafkaBroker` and `DatabaseBroker`.
- **Internal classes encapsulated** — Helper classes moved to `io.github.cyfko.veridot.core.impl` (package-private).

### Added

- **Protocol V5 implementation** — Full implementation resolving structural and cryptographic vulnerabilities (F-01 through F-09).
- **Physical deletion via Broker** — `DatabaseBroker` and `KafkaBroker` support entry deletion when `put()` receives empty payload.
- **Resource safety** — Constructor leak protections and thread termination awaits in `KafkaBroker`.

---

## [3.1.0] - 2026-06-27

### 🔐 Security

- **CVE-class fix: closed configuration DoS injection vector (F9)** — Configuration parameters now authenticated via the `TrustRoot`.

### Added

- **`publishConfig()` API** — Publish cryptographically signed configurations.
- **`TrustRoot.isAuthorizedForScope()`** — Scope authorization verification.

---

## [3.0.2] - 2026-06-27

### 🔐 Security

- **CVE-class fix: broker-injection attack vector closed** — Every key announcement now requires a valid long-term RSA signature. See [ADR-001](./architecture/adr/index.md).
- **Signed revocation tombstones** (F7) — `revoke()` embeds a `tombstoneSig`.

### Added

- **`TrustRoot` sealed interface** — Root of trust with `PublicKeyResolver` and `DelegatedVerifier` sub-interfaces.
- **`TrustResolutionException` sealed exception** — `Unavailable` (transient) and `SignatureRejected` (definitive) subtypes.
- **RSA-3072** — Ephemeral key size increased from implicit 2048 to explicit 3072.

---

## [3.0.0] - 2026-04-12

### ⚠️ Breaking Changes

- **`TokenVerifier.verify()` now returns `VerifiedData<T>`** instead of `T`.

### Added

- **`VerifiedData<T>` record** — Immutable result with `data()`, `groupId()`, `sequenceId()`.

---

## [2.1.0] - 2026-04-11

### Added

- **Protocol V2** — Canonical message format with permissive identifier validation.
- **DistributionMode** — `DIRECT` and `NATIVE` modes.
- **Distributed `__CONFIG__`** — Dynamic configuration resolution hierarchy.
- **EvictionPolicy** — `FIFO`, `LIFO`, `LRU`, `REJECT` strategies.

---

## [2.0.0] - 2026-02-01

- Renamed project from **DVerify** to **Veridot**.
- New package: `io.github.cyfko.veridot`.
- Modular architecture: `veridot-core`, `veridot-kafka`, `veridot-databases`.

---

*For the complete changelog with all intermediate versions, see the [full CHANGELOG.md on GitHub](https://github.com/cyfko/veridot/blob/main/java/CHANGELOG.md).*
