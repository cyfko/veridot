# Changelog

All notable changes to the Veridot project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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