# Changelog

All notable changes to the Veridot project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [4.0.1] - 2026-07-01

### 🔐 Security

- **Hardened Asymmetric Key Encapsulation (ECIES and RSA)** — Replaced textbook RSA encryption with `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` for RSA keys. Replaced insecure `AES/ECB/PKCS5Padding` and SHA-256 direct hash key derivation with `AES/GCM/NoPadding` (authenticated encryption using randomly generated 12-byte IVs) and `HKDF-SHA256` key derivation for EC (ECIES) keys.
- **Timing attack warnings** — Signature verification now emits a warning log when verifying signatures with non-constant-time JCA algorithms (RSA, ECDSA), recommending migrating to Ed25519.
- **Default allowed algorithms restriction** — `Config.ALLOWED_SIG_ALGS` defaults to only `ED25519` and `RSA_PSS` to prevent the use of weaker algorithms (like RSA PKCS#1 v1.5) by default.

### Changed

- **Reduced cache latency** — Reduced `CAPABILITY_CACHE_TTL_SECONDS` default from 60 seconds to 10 seconds to shorten the propagation window of revoked capabilities.
- **Deterministic JWT serialization** — `JwtMaker` now uses `LinkedHashMap` and sorts custom claims alphabetically to produce canonical, deterministic JSON output.

## [4.0.0] - 2026-06-28

### ⚠️ Breaking Changes

- **MetadataBroker interface removed** — Replaced by `Broker` interface for Protocol V4 storage.
- **TrustAnchor interface removed** — Replaced by `TrustRoot` interface for Protocol V4 root identity and key resolution.
- **Verification exceptions unified** — `V4Exception` is renamed to `VeridotException` and serves as the root unchecked exception.
- **V3-V4 adapters and compatibility interfaces removed** — Classes like `KafkaMetadataBrokerAdapter` and `DatabaseMetadataBroker` have been deleted. Database and Kafka persistence are implemented directly in `DatabaseBroker` and `KafkaBroker` respectively.
- **Encapsulation consolidation** — Internal implementation helper classes (e.g., `LivenessChecker`, `SessionCounter`, `TlvCodec`, and payloads) have been made package-private and grouped directly under the `io.github.cyfko.veridot.core.impl` package to isolate them from public API consumption.

### Added

- **Protocol V4 implementation** — Full implementation of the V4 protocol, resolving structural and cryptographic verification vulnerabilities (RFC flaws F-01 through F-09).
- **Physical deletion via Broker** — `DatabaseBroker` and `KafkaBroker` now support entry deletion (SQL DELETE / Kafka tombstones) when `put()` is called with an empty byte payload (`length == 0`).
- **Resource safety** — Constructor leak protections and thread termination awaits implemented in `KafkaBroker` to prevent JVM crashes (`SIGSEGV`) when closing RocksDB.

## [3.1.0] - 2026-06-27

### ⚠️ Breaking Behavior

- **Distributed configuration must now be signed** — Any `__CONFIG__` message written to the broker that lacks a valid signature from an identity resolved by the `TrustAnchor` will be silently ignored. Systems will fall back to the next priority level or constructor defaults.

  **Migration**: Any session capacity configurations (maxSessions, eviction policy, defaultTTL) published prior to this release must be republished using the new `publishConfig(...)` API.

### 🔐 Security

- **CVE-class fix: closed configuration DoS injection vector (F9)** — Prior to this release, an attacker with write access to the broker could inject unsigned configuration overrides (e.g. `maxSessions=1` with `EvictionPolicy.REJECT`) to execute a silent denial of service (DoS) on target groups. Configuration parameters are now authenticated via the `TrustAnchor`.

- **TrustAnchor availability degradation** — Unavailability of the `TrustAnchor` (KMS down) during configuration verification degrades gracefully to the next priority level or default settings without blocking token signing.

### Added

- **`publishConfig(ConfigScope, String, int, EvictionPolicy, long, long)` API** — Public method on `GenericSignerVerifier` to publish cryptographically signed configurations.
- **`TrustAnchor.isAuthorizedForScope(String, String)`** — Default permissive method on `TrustAnchor` to verify if a signing identity is authorized to configure a specific scope (local/site/global).
- **`ConfigTrustSecurityTest`** — 7 unit tests targeting configuration signature validation, expired config, KMS unavailability fallback, and scope authorization.

---

## [3.0.2] - 2026-06-27

### ⚠️ Breaking Changes

- **Constructor signature changed** — `GenericSignerVerifier(MetadataBroker, String salt)` and
  `GenericSignerVerifier(MetadataBroker, String salt, int maxSessions, EvictionPolicy policy)` are
  **removed**. The `salt` parameter had no cryptographic function.

  New constructors:
  ```java
  // Without session limit
  new GenericSignerVerifier(MetadataBroker, TrustAnchor, String signerId, PrivateKey longTermPrivateKey)

  // With session limit
  new GenericSignerVerifier(MetadataBroker, TrustAnchor, String signerId, PrivateKey longTermPrivateKey,
                            int maxSessions, EvictionPolicy policy)
  ```

  **Migration**: inject a `TrustAnchor` (that resolves long-term public keys) and the service's
  long-term `PrivateKey`. The salt is gone. See [migration guide](docs/java-guide.md#migrating-from-v301).

- **Java 25 required** for `veridot-core` (up from Java 17). `veridot-kafka` and
  `veridot-databases` remain compatible with Java 17.

- **`MetadataBroker.sendLocal(String key, String message)`** — new `default` method (no-op by
  default). Existing custom `MetadataBroker` implementations remain source-compatible; implement
  this method for zero-latency same-node verification.

### 🔐 Security

- **CVE-class fix: broker-injection attack vector closed** — Prior to this release, any node with
  write access to the Kafka topic could inject a fraudulent key announcement and obtain valid
  verification results from any Veridot consumer. v3.0.2 requires every key announcement to carry
  a valid long-term RSA signature (`announcementSig`) over canonical bytes. The `TrustAnchor`
  validates this signature before accepting the ephemeral public key. See
  [ADR-001](docs/adr/adr-001-trust-anchor.md).

- **Signed revocation tombstones** (F7) — `revoke()` now embeds a `tombstoneSig` (long-term RSA
  signature over `groupId ‖ target ‖ timestamp`) in every `__REVOKE__` message. The
  latest-timestamp-wins rule makes replay of old tombstones harmless. See
  [ADR-003](docs/adr/adr-003-tombstone-signed.md).

### Added

- **`TrustAnchor` sealed interface** (`io.github.cyfko.veridot.core.TrustAnchor`) — root of trust
  that decouples broker write-access from signing authority. Two permitted sub-interfaces:
  - `TrustAnchor.PublicKeyResolver` — resolves `signerId → PublicKey` locally (trust store, Vault
    KV, PEM file). Veridot then verifies the announcement signature in-process.
  - `TrustAnchor.DelegatedVerifier` — delegates the full signature verification to an external
    KMS or HSM (Vault Transit, AWS KMS, etc.). The long-term private key never leaves the KMS
    boundary.

- **`TrustResolutionException` sealed exception** (`io.github.cyfko.veridot.core.exceptions`) —
  two semantically distinct subtypes:
  - `TrustResolutionException.Unavailable` — transient infrastructure failure (KMS down). Must
    **fail safe**: never accept the token when the trust anchor is unreachable.
  - `TrustResolutionException.SignatureRejected` — definitive cryptographic rejection. Must
    **alert**: log at SEVERE and notify the security team.

- **Signed key announcements** — `sign()` computes a long-term RSA signature over the canonical
  announcement bytes (`len(pubkeyDER) ‖ pubkeyDER ‖ timestamp ‖ ttl ‖ len(signerId) ‖ signerId`,
  all big-endian, length-prefixed) and embeds `signerId` + `announcementSig` in the V2 metadata
  message.

- **`MetadataBroker.sendLocal(String, String)`** — default no-op method. `KafkaMetadataBrokerAdapter`
  implements it by writing directly to the local RocksDB without producing to Kafka. Called by
  `sign()` before the async Kafka `send()`. Eliminates the same-node read-after-write race (F5):
  a `verify()` call immediately after `sign()` on the same JVM now succeeds without waiting for
  the Kafka round-trip.

- **RocksDB TTL compaction** (F6) — `KafkaMetadataBrokerAdapter` now schedules a periodic
  compaction task (every 5 minutes) that iterates RocksDB and deletes entries where
  `timestamp + ttl + 300 < now`. Database entry count is logged after each compaction run.

- **Control-channel priority** (F4) — within each Kafka consumer poll cycle, messages with
  `:__REVOKE__` or `:__CONFIG__` keys are processed before data messages. This bounds revocation
  propagation latency to ≈1 poll interval (1s p99 with default settings).

- **`Config.ASYMMETRIC_KEY_SIZE = 3072`** — explicit public constant for the RSA ephemeral key
  size (previously implicit JDK default of 2048). See [ADR-002](docs/adr/adr-002-rsa-3072.md).

- **New test class `TrustAnchorSecurityTest`** — 7 security-focused unit tests:
  - Broker-injection without `announcementSig` → rejected.
  - Broker-injection with random (forged) `announcementSig` → rejected.
  - `TrustAnchor.Unavailable` → fail safe, token not accepted.
  - Unknown `signerId` → rejected.
  - Canonical announcement determinism and field-binding (length-prefix collision prevention).
  - Positive path: valid announcement + valid JWT → accepted.

- **`TestTrustSetup` test utility** — creates a self-contained long-term key pair and
  `TrustAnchor.PublicKeyResolver` for unit tests. Replaces the removed salt-based constructors in
  all test classes.

### Changed

- **RSA ephemeral key size**: **RSA-3072** (up from implicit RSA-2048). All existing tokens remain
  verifiable until their TTL expires; the key size is embedded in the stored public key DER bytes,
  not in a separate field.

- **Eviction send timeout**: broker sends inside `enforceSessionLimit` now have a **10-second
  timeout** (F8). Previously `CompletableFuture.get()` was unbounded, blocking the `sign()` hot
  path indefinitely on a slow broker. Timeout triggers a warning log, not an exception.

- **`Protocol` metadata** — normal key announcements now include two mandatory fields:
  `signerId` (base64url, UTF-8) and `announcementSig` (base64url, RSA signature bytes). Tombstone
  messages include `tombstoneSig`. Implementations that do not validate these fields continue to
  parse the messages without error (additive change).

- **Java 25** set as `maven.compiler.source/target` in `veridot-core/pom.xml`.

### Removed

- `GenericSignerVerifier(MetadataBroker, String salt)` constructor.
- `GenericSignerVerifier(MetadataBroker, String salt, int maxSessions, EvictionPolicy policy)` constructor.

### Tests

- All existing test classes (`SigningTest`, `RevocationTest`, `VerificationTest`,
  `SessionCapacityTest`, `MultiInstanceSessionTest`, `TokenTrackerTest`) migrated to
  `TestTrustSetup`.
- `SigningTest`: added assertions that `signerId:` and `announcementSig:` fields are present in
  stored V2 metadata.
- `RevocationTest`: added `tombstoneSig:` assertion; added replay-after-tombstone regression test.
- `VerificationTest`: added forge test — manually injected broker entry without trust-anchor
  fields must be rejected.
- Test suite: **89 tests, 0 failures, 0 errors** (Java 25, Maven 3.9.15).

---

## [3.0.1] - 2026-04-14

### 🐛 Bug Fixes

- **`GenericSignerVerifier.revoke()` race condition** — The sequence deletion send inside
  `revoke(groupId, sequenceId)` and the `__REVOKE__` broadcast were previously fire-and-forget
  (no `.get()` on the returned `CompletableFuture`). This caused a race condition where a
  `sign()` call issued immediately after `revoke()` could still observe the deleted entry as
  active in the broker, triggering a spurious `SessionCapacityExceededException` in REJECT
  mode with `maxSessions=1`. Both sends are now awaited (`.get(3, TimeUnit.MINUTES)`),
  consistent with the eviction behavior already implemented in `enforceSessionLimit`.

### ✅ Tests

- Added regression test `revoke_then_sign_immediately_no_race_condition` in
  `SessionCapacityTest` that validates the fix without any `Thread.sleep()`, proving that
  `revoke()` is fully synchronous before `enforceSessionLimit` runs.

---

## [3.0.0] - 2026-04-12


### ⚠️ Breaking Changes

- **`TokenVerifier.verify()` now returns `VerifiedData<T>`** instead of `T`.
  - `VerifiedData<T>` is a new record carrying the deserialized payload (`.data()`)
    alongside the Protocol V2 identifiers (`.groupId()`, `.sequenceId()`) that were
    bound to the token at signing time.
  - **Migration**: replace `T result = verifier.verify(token, fn)` with
    `VerifiedData<T> result = verifier.verify(token, fn)` and access the payload via `result.data()`.
  - **Benefit**: eliminates the need to re-parse the token or redundantly embed
    `groupId`/`sequenceId` inside the payload to use them for revocation or correlation.

### Added

- **`VerifiedData<T>` record** (`veridot-core`) — immutable result of a successful
  verification, providing typed access to `data()`, `groupId()` and `sequenceId()`.

### Changed

- **Comprehensive Javadoc overhaul** across all modules (`veridot-core`, `veridot-kafka`,
  `veridot-databases`):
  - All public interfaces (`DataSigner`, `TokenVerifier`, `TokenRevoker`, `TokenTracker`,
    `MetadataBroker`, `DistributionMode`, `VerifiedData`, `DataTransformer`) now include
    end-to-end code examples and precise `@param`/`@return`/`@throws` documentation.
  - Interfaces are now strictly **format-agnostic**: no references to JWT, JWS, RSA, or
    `sub` claim appear in public contracts. JWT/RSA is documented as an `@implNote` in
    `GenericSignerVerifier` only.
  - All 5 exception classes now have full Javadoc (causes, context, constructor `@param`).
  - `EvictionPolicy` enum constants are individually documented with
    behaviour descriptions and use-case guidance.
  - `KafkaMetadataBrokerAdapter`, `Constant`, `SignerConfig`, `VerifierConfig` updated with
    architecture overview, env-var tables, and initialization examples.
  - `DatabaseMetadataBroker` constructor and all `@Override` methods now documented.

---

## [2.2.0] - 2026-04-12


### Changed

- **⚠️ BREAKING CHANGE**: Refactored `TokenRevoker` API to use a unified `revoke(groupId, sequenceId)` method instead of `revoke(Object)` and `revokeGroup(String)`.
  - To revoke a specific session: `revoke("user-123", "session-A")`
  - To revoke an entire group: `revoke("user-123", null)`

---

## [2.1.3] - 2026-04-12

### Changed

- **Identifier validation relaxed** — GroupId and sequenceId now accept any printable characters except protocol delimiters (`:`, `,`, `|`) and whitespace. Pattern changed from `[A-Za-z0-9_-]{1,64}` to `[^:,|\s]{1,125}`, allowing emails, IPs, dot-separated namespaces, etc.
- **Protocol V2 spec updated** (§2.2.2) to reflect the new identifier rules

---

## [2.1.2] - 2026-04-12

### Fixed

- **Lazy garbage collection** — Expired sessions are now automatically purged from the broker (DB/Kafka/InMemory) during `enforceSessionLimit`, preventing stale entries from accumulating indefinitely

### Added

- Tests for expired session cleanup and REJECT policy with expired sessions

---

## [2.1.1] - 2026-04-11

### Added

- **EvictionPolicy.REJECT** — Refuses the signing attempt with `SessionCapacityExceededException` instead of evicting an existing session
- **VeridotException** — Root exception class for all Veridot exceptions, enabling unified `catch (VeridotException e)` handling
- **SessionCapacityExceededException** — Dedicated exception with `getGroupId()` and `getMaxSessions()` accessors

---

## [2.1.0] - 2026-04-11

### ⚠️ Breaking Changes (from 1.x / 2.0.x)

- **Constructor**: `GenericSignerVerifier(MetadataBroker)` removed → use `GenericSignerVerifier(MetadataBroker, String salt)` or `GenericSignerVerifier(MetadataBroker, String salt, int maxSessions, EvictionPolicy policy)`
- **TokenMode removed**: `TokenMode.jwt` / `TokenMode.id` replaced by `DistributionMode.DIRECT` / `DistributionMode.INDIRECT`
- **Configurer API**: `trackedBy(long)` removed → use `groupId(String)` + `sequenceId(String)` + `validity(long)`
- **Message format**: V1 JSON → V2 canonical format `2:<groupId>:<sequenceId>|<metadata>` with Base64url-encoded values (RFC 4648 §5)
- **MetadataBroker interface**: Added `getKeysByPrefix(String prefix)` method (required for group operations)

### Added

- **Protocol V2** — Canonical message format with permissive identifier validation (`[^:,|\s]{1,125}`)
- **DistributionMode** — `DIRECT` (return JWT to caller) and `INDIRECT` (return messageId, store JWT in broker)
- **Structured `__REVOKE__` messages (§5)** — Formal revocation messages `2:grp:__REVOKE__|target:<b64>,timestamp:<b64>` for cross-processor interoperability
- **Distributed `__CONFIG__` (§4)** — Dynamic configuration resolution from broker: local (`2:grp:__CONFIG__`) → site (`2:__CONFIG__:siteId`) → global (`2:__CONFIG__:__ALL__`) → constructor defaults
- **Clock drift validation (§9.1)** — Messages with timestamps >5 minutes in the future are rejected
- **EvictionPolicy** — `FIFO`, `LIFO`, `LRU`, `REJECT` session eviction strategies when `maxSessions` is reached
- **EvictionPolicy.REJECT** — Refuses the signing attempt with `SessionCapacityExceededException` instead of evicting an existing session
- **TokenTracker interface** — `hasActiveToken(Object)` to query whether active tokens exist for a group, JWT, or messageId
- **TokenRevoker.revokeGroup(String)** — Revoke all active sequences for a group
- **Reserved sequence detection** — `__CONFIG__`, `__REVOKE__`, `__ALL__` excluded from session counting
- **Kafka consumer** — Processes `__REVOKE__` messages from the topic to delete targeted sequences in RocksDB

### Changed

- Session counting is now derived from broker state (not a local counter), per §3.5.1
- Eviction emits formal `__REVOKE__` messages before deleting sequences
- Config cache with 60s TTL reduces broker queries
- **VeridotException** — All exceptions (`BrokerExtractionException`, `BrokerTransportException`, `DataSerializationException`, `DataDeserializationException`, `SessionCapacityExceededException`) now extend a common `VeridotException` root class for unified error handling

---

## [2.0.3] - 2026-03-01 (veridot-databases only)

### Fixed

- Database broker compatibility improvements

## [2.0.2] - 2026-02-20

### Fixed

- Minor stability improvements

## [2.0.1] - 2026-02-15

### Fixed

- Database metadata broker refinements

## [2.0.0] - 2026-02-01

### Changed

- Renamed project from **DVerify** to **Veridot**
- New package: `io.github.cyfko.veridot`
- New modular architecture: `veridot-core`, `veridot-kafka`, `veridot-databases`
- `GenericSignerVerifier` replaces `KafkaDataSigner` / `KafkaDataVerifier`

---

## Pre-Veridot (DVerify)

### 4.0.0 (2025-04-10)

- Added `trackingId` to `Signer.sign()` for token revocation tracking

### 3.0.0 (2025-04-10)

- Renamed `DataSigner` → `Signer`, `DataVerifier` → `Verifier`
- Added `Broker` interface for metadata propagation
- Removed `KafkaDataSigner` / `KafkaDataVerifier`
- Added `GenericSignerVerifier`

### 2.2.0 (2025-03-05)

- Scheduled task to automatically remove expired embedded database entries

### 2.1.0 (2025-03-03)

- UUID token mode via `SignerConfig.GENERATED_TOKEN_CONFIG`

### 2.0.0 (2025-03-01)

- Moved package to `io.github.cyfko.dverify`
- RocksDB persistence replacing cache mechanism
- Autocommit offsets for Kafka consumer

### 1.1.0 (2025-02-26)

- Moved package to `io.github.cyfko.disver`
- Bug fixes in `KafkaDataVerifier`

### 1.0.0 (2025-02-25)

- Initial release