# Veridot 3.0.0

Veridot 3.0.0 marks a major milestone in finalizing the Protocol V2 design and improving the developer experience. This release brings a fully refactored, unified verification and tracking API, coupled with an exhaustive Documentation/Javadoc overhaul to make the codebase completely protocol-agnostic.

## ⚠️ Breaking Changes

### 1. `TokenVerifier.verify()` returns `VerifiedData<T>`
The `verify()` method no longer returns the raw deserialized payload `T`. Instead, it returns a `VerifiedData<T>` record.

**Why?** Previously, to correlate a verified token to its sequence or group (for tracking or revocation), developers had to redundantly embed `groupId` and `sequenceId` into their custom payload or re-parse the raw token. `VerifiedData<T>` eliminates this by carrying the `data()`, `groupId()`, and `sequenceId()` natively.

**Migration example:**
```diff
- String payload = sv.verify(token, String::toString);
- sv.revoke("user-123", "session-A"); // where did these come from?
---
+ VerifiedData<String> result = sv.verify(token, String::toString);
+ String payload = result.data();
+ 
+ // Now perfectly correlated for immediate, programmatic revocation:
+ sv.revoke(result.groupId(), result.sequenceId());
```

### 2. Unified `TokenRevoker` API
The `TokenRevoker` interface has been simplified:
- Removed: `revoke(Object token)` (ambiguous) and `revokeGroup(String groupId)`.
- Replaced with a unified `revoke(String groupId, String sequenceId)` method.
  - Revoke a specific sequence: `revoke("user-123", "session-A")`
  - Revoke an entire group: `revoke("user-123", null)`

## ✨ What's New

### Complete Javadoc & Documentation Overhaul
- **Agnostic Core Contracts**: All references to internal implementation details like `JWT`, `RSA`, or claims have been completely removed from public interfaces (`DataSigner`, `TokenVerifier`, `TokenRevoker`, `TokenTracker`). They now reflect pure protocol concepts.
- **Embedded End-to-End Examples**: Every major interface class and implementation now includes `<pre>{@code ... }</pre>` usage block examples right in the code.
- **Eviction Policies Documented**: `GenericSignerVerifier.EvictionPolicy` (`FIFO`, `LIFO`, `LRU`, `REJECT`) defaults have fully outlined behavioral impacts explicitly detailed in their Javadoc.
- **Config & Env-Vars Tables**: All metadata brokers (`KafkaMetadataBrokerAdapter`, `DatabaseMetadataBroker`) heavily document initialization, RocksDB configuration, SQL injection safety measures, environment variables bindings, and `__REVOKE__` parsing schemas.
- **Fully Documented Exceptions**: All 5 domain exceptions (`BrokerExtractionException`, `SessionCapacityExceededException`, etc.) are thoroughly documented explaining causes, contexts, and parameter access methods.

## 📦 Installation

**Maven:**
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>3.0.0</version>
</dependency>
<!-- Add veridot-kafka or veridot-databases based on your broker choice -->
```

**Gradle:**
```gradle
implementation 'io.github.cyfko:veridot-core:3.0.0'
```

---
*For the full list of changes, see the [CHANGELOG](https://github.com/cyfko/veridot/blob/main/java/CHANGELOG.md).*
