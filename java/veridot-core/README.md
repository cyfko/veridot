# veridot-core

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-core.svg)](https://central.sonatype.com/artifact/io.github.cyfko/veridot-core)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../../LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![Build](https://img.shields.io/badge/tests-132%20passed-brightgreen.svg)]()

Core module of **Veridot** — the Java implementation of the [Protocol V4.0](../../PROTOCOL_V4.md) distributed token verification protocol.

This README is the **self-contained reference** for using Veridot in Java. You do not need to visit any external website to get started.

---

## Table of contents

- [What Veridot does](#what-veridot-does)
- [Installation](#installation)
- [Core concepts](#core-concepts)
- [TrustRoot — the security cornerstone](#trustroot--the-security-cornerstone)
- [First integration: sign, verify, revoke](#first-integration-sign-verify-revoke)
- [Distribution modes: DIRECT vs INDIRECT](#distribution-modes-direct-vs-indirect)
- [Session capacity management](#session-capacity-management)
- [Token tracking](#token-tracking)
- [Custom serialization (POJOs, JSON…)](#custom-serialization-pojos-json)
- [Error handling](#error-handling)
- [Spring Boot integration](#spring-boot-integration)
- [Distributed configuration (Protocol §4)](#distributed-configuration-protocol-4)
- [Environment variables](#environment-variables)
- [Full API reference](#full-api-reference)
- [Building and testing](#building-and-testing)
- [Security model](#security-model)

---

## What Veridot does

Veridot lets any service in your cluster **sign** a payload into a verifiable token, and lets any other service **verify** that token in under 1ms — without sharing a secret, without calling a central authority, and with the ability to **revoke** any token instantly across the entire cluster.

```
Service A (signer)                     Service B (verifier)
──────────────────                     ────────────────────
sv.sign("alice@example.com", config)   sv.verify(token, s -> s)
  → generates RSA-3072 ephemeral key     → reads from local RocksDB (<1ms)
  → signs payload → JWT                  → validates TrustRoot signature
  → publishes key announcement           → verifies JWT signature
    to Kafka (async)                     → returns VerifiedData<String>

sv.revoke("user-alice", null)
  → broadcasts signed __REVOKE__ tombstone
  → Service B invalidates session within ~1s (next Kafka poll)
```

---

## Installation

**Requires Java 25+**

### Maven

```xml
<properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
</properties>

<dependencies>
    <!-- Core API — always required -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-core</artifactId>
        <version>4.0.0</version>
    </dependency>

    <!-- Pick exactly one broker -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-kafka</artifactId>      <!-- Kafka + RocksDB (recommended) -->
        <version>4.0.0</version>
    </dependency>
    <!-- OR -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-databases</artifactId>  <!-- SQL database -->
        <version>4.0.0</version>
    </dependency>
</dependencies>
```

### Gradle

```gradle
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    implementation 'io.github.cyfko:veridot-core:4.0.0'
    implementation 'io.github.cyfko:veridot-kafka:4.0.0'  // or veridot-databases
}
```

---

## Core concepts

| Concept | What it is |
|---------|-----------|
| **`DataSigner`** | Signs a payload → returns a token (JWT or messageId) |
| **`TokenVerifier`** | Verifies a token → returns `VerifiedData<T>` with payload + identifiers |
| **`TokenRevoker`** | Revokes a session or all sessions of a group, cluster-wide |
| **`TokenTracker`** | Checks whether a session is still active without full verification |
| **`Broker`** | Transports protocol envelopes between service instances (Kafka, SQL…) |
| **`TrustRoot`** | Validates key announcements — ensures the broker cannot be used to forge keys |
| **`GenericSignerVerifier`** | Default implementation of all four interfaces |
| **`groupId`** | A logical namespace for sessions (e.g., a userId, a client ID) |
| **`sequenceId`** | A unique session within a group (e.g., a device ID, a session UUID) |
| **`VerifiedData<T>`** | Immutable record: `data`, `groupId`, `sequenceId` |

---

## TrustRoot — the security cornerstone

Every key announcement carries a long-term RSA signature. Verifiers check this signature via a `TrustRoot` **independently** of the broker. Broker write-access alone cannot produce a valid announcement.

`TrustRoot` is a functional interface that resolves a signer ID to a public key verification mechanism. There are two built-in implementations:

### Option A — `PublicKeyTrustRoot` (you load the public key, Veridot verifies)

Best for: public keys stored in files, Vault KV, ConfigMaps, or any key-value store.

```java
TrustRoot trust = new PublicKeyTrustRoot(signerId -> {
    // Load the long-term public key for this signerId from your trust store.
    // Called once per unique signerId, result may be cached by the implementation.
    byte[] pem = Files.readAllBytes(Paths.get("/etc/veridot/trust/" + signerId + ".pub.pem"));
    return parsePemPublicKey(pem);
});
```

### Option B — `DelegatedTrustRoot` (you delegate verification to a KMS)

Best for: Vault Transit Engine, AWS KMS, Google Cloud KMS, Azure Key Vault, HSMs.
The long-term private key never leaves the KMS.

```java
TrustRoot trust = new DelegatedTrustRoot((signerId, canonicalBytes, signature) -> {
    // Your KMS verifies the RSA signature. Return true if valid, false otherwise.
    return vaultTransit.verify(signerId, canonicalBytes, signature);
});
```

---

## First integration: sign, verify, revoke

A minimal but complete working example using the Kafka broker.

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.kafka.KafkaBroker;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Properties;

// ── 1. Configure Kafka broker ─────────────────────────────────────
Properties kafkaProps = new Properties();
kafkaProps.setProperty("bootstrap.servers", "kafka:9092");
kafkaProps.setProperty("embedded.db.path", "/var/lib/veridot");
Broker broker = KafkaBroker.of(kafkaProps);

// ── 2. Load long-term private key ─────────────────────────────────
// In production: load from Vault, Kubernetes Secret, or KMS.
byte[] pkcs8Bytes = Files.readAllBytes(Paths.get("/etc/veridot/private.key"));
PrivateKey longTermKey = KeyFactory.getInstance("RSA")
    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));

// ── 3. Configure TrustRoot ────────────────────────────────────────
TrustRoot trust = new PublicKeyTrustRoot(signerId -> {
    byte[] pem = Files.readAllBytes(Paths.get("/etc/veridot/trust/" + signerId + ".pub.pem"));
    return parsePemPublicKey(pem); // your PEM parsing helper
});

// ── 4. Build signer/verifier ──────────────────────────────────────
var sv = new GenericSignerVerifier(broker, trust, "auth-service", longTermKey);

// ── 5. Sign ───────────────────────────────────────────────────────
String token = sv.sign("alice@example.com",
    BasicConfigurer.builder()
        .groupId("user-alice")         // logical group (e.g. userId)
        .sequenceId("mobile-app-v2")   // session identifier (optional, UUID if omitted)
        .validity(3600)                // TTL: 1 hour
        .build());

// ── 6. Verify (from any service sharing the same broker + trust root)
VerifiedData<String> result = sv.verify(token, s -> s);

System.out.println(result.data());       // "alice@example.com"
System.out.println(result.groupId());    // "user-alice"
System.out.println(result.sequenceId()); // "mobile-app-v2"

// ── 7. Revoke ─────────────────────────────────────────────────────
sv.revoke("user-alice", "mobile-app-v2"); // revoke this specific session
sv.revoke("user-alice", null);             // revoke ALL sessions for this group
```

---

## Distribution modes: DIRECT vs INDIRECT

### DIRECT (default)

The JWT is returned directly to the caller. The JWT itself contains the payload and is self-describing.

```java
String jwt = sv.sign("alice@example.com",
    BasicConfigurer.builder()
        .groupId("user-alice")
        .validity(3600)
        .distribution(DistributionMode.DIRECT) // default, can be omitted
        .build());

// jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIyOnVzZXItYWxpY2U6..."
// → pass to client as Authorization: Bearer <jwt>
```

### INDIRECT

The payload is stored in the broker (Kafka/DB). The caller receives a `messageId` — a short opaque reference. The JWT never reaches the client.

```java
String messageId = sv.sign(sensitiveObject,
    BasicConfigurer.builder()
        .groupId("reports")
        .sequenceId("report-2026-q2")
        .validity(86400)               // 24 hours
        .distribution(DistributionMode.INDIRECT)
        .serializedBy(obj -> mapper.writeValueAsString(obj))
        .build());

// messageId = "4:reports:report-2026-q2"
// → store in your DB, pass to trusted services as a reference

// Verify using messageId — identical API
VerifiedData<ReportData> result = sv.verify(messageId,
    json -> mapper.readValue(json, ReportData.class));
```

**Use INDIRECT when:**
- Payload is large (> a few KB) and should not be embedded in a JWT.
- Payload is sensitive and should not transit over client-facing channels.
- You want to invalidate a specific data document, not just a session.

---

## Session capacity management

Enforce a maximum number of concurrent active sessions per `groupId`. Useful for limiting concurrent device logins.

```java
// Allow up to 3 sessions, evict the oldest on overflow (FIFO)
var sv = new GenericSignerVerifier(
    broker, trust, "auth-service", longTermKey,
    3, EvictionPolicy.FIFO);

// Allow only 1 session. Reject any attempt to create a second.
var sv = new GenericSignerVerifier(
    broker, trust, "auth-service", longTermKey,
    1, EvictionPolicy.REJECT);
```

### Eviction policies

| Policy | Behavior when `maxSessions` is reached |
|--------|---------------------------------------|
| `FIFO` | Evicts the session with the **oldest** timestamp |
| `LIFO` | Evicts the session with the **newest** timestamp |
| `LRU` | Evicts the session that was **least recently used** |
| `REJECT` | Throws `SessionCapacityExceededException` — no eviction |

```java
// Handle REJECT policy
try {
    String token = sv.sign(data, config);
} catch (SessionCapacityExceededException e) {
    // e.getGroupId()     → which group was at capacity
    // e.getMaxSessions() → the configured limit
    log.warn("Session limit reached for group={}, max={}", e.getGroupId(), e.getMaxSessions());
}
```

---

## Token tracking

Check whether sessions are active without performing full verification (no deserialization).

```java
// Is there any active session for this group?
boolean hasActive = sv.hasActiveToken("user-alice");

// Is this specific JWT still valid (not expired, not revoked)?
boolean isValid = sv.hasActiveToken(jwt);

// Is this specific messageId still active?
boolean isActive = sv.hasActiveToken("4:reports:report-2026-q2");
```

---

## Custom serialization (POJOs, JSON…)

By default, Veridot calls `Object.toString()` on your payload. For structured objects, provide a custom serializer/deserializer.

```java
ObjectMapper mapper = new ObjectMapper();

// Signing with custom serializer
String token = sv.sign(myUserObject,
    BasicConfigurer.builder()
        .groupId("user-" + myUserObject.getId())
        .validity(3600)
        .serializedBy(obj -> mapper.writeValueAsString(obj)) // Object → String
        .build());

// Verifying with custom deserializer (lambda)
VerifiedData<UserData> result = sv.verify(token,
    json -> mapper.readValue(json, UserData.class));          // String → T

// Or use the built-in Jackson helper
VerifiedData<UserData> result = sv.verify(token,
    BasicConfigurer.deserializer(UserData.class));            // uses ObjectMapper internally
```

---

## Error handling

All Veridot exceptions are subclasses of the unchecked `VeridotException`.

### During `verify()`

```java
try {
    VerifiedData<String> result = sv.verify(token, s -> s);
    // ← success path

} catch (BrokerExtractionException e) {
    // Covers all verification failures:
    //   • Token expired (timestamp + ttl < now)
    //   • Session revoked (physically deleted or blacklisted)
    //   • TrustRoot verification failed
    //   • JWT signature invalid (tampered token)
    //   • MessageId not found in broker
    response.sendError(401);

} catch (DataDeserializationException e) {
    // Payload deserialization failed.
    log.error("Payload deserialization failure", e);
    response.sendError(500);
}
```

### During `sign()`

```java
try {
    String token = sv.sign(data, config);

} catch (SessionCapacityExceededException e) {
    // Limit reached + REJECT policy. No session was created.
    response.sendError(429, "Too many active sessions");

} catch (DataSerializationException e) {
    // Could not serialize your payload. Check your serializer.
    log.error("Serialization failure", e);
    response.sendError(500);

} catch (BrokerTransportException e) {
    // Kafka/DB unavailable. The signing operation failed.
    log.error("Broker transport failure", e);
    response.sendError(503);
}
```

---

## Spring Boot integration

### Configuration bean

```java
@Configuration
@RequiredArgsConstructor
public class VeridotConfig {

    @Value("${veridot.signer-id}")
    private String signerId;

    @Value("${veridot.private-key-path}")
    private String privateKeyPath;

    @Value("${veridot.trust-dir:/etc/veridot/trust}")
    private String trustDir;

    @Value("${veridot.max-sessions:5}")
    private int maxSessions;

    @Bean
    public PrivateKey veridotLongTermKey() throws Exception {
        byte[] pkcs8 = Files.readAllBytes(Paths.get(privateKeyPath));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    @Bean
    public TrustRoot veridotTrustRoot() {
        return new PublicKeyTrustRoot(id -> {
            byte[] pem = Files.readAllBytes(Paths.get(trustDir, id + ".pub.pem"));
            return parsePemPublicKey(pem);
        });
    }

    @Bean
    public Broker veridotBroker(
            @Value("${spring.kafka.bootstrap-servers}") String servers) {
        Properties p = new Properties();
        p.setProperty("bootstrap.servers", servers);
        p.setProperty("embedded.db.path", "/var/lib/veridot");
        p.setProperty("security.protocol", "SASL_SSL");
        p.setProperty("sasl.mechanism", "SCRAM-SHA-512");
        p.setProperty("sasl.jaas.config", buildJaasConfig());
        return KafkaBroker.of(p);
    }

    @Bean
    public GenericSignerVerifier veridot(Broker broker,
                                         TrustRoot trust,
                                         PrivateKey longTermKey) {
        return new GenericSignerVerifier(
            broker, trust, signerId, longTermKey,
            maxSessions, EvictionPolicy.FIFO);
    }

    // Expose individual interfaces as Spring beans
    @Bean DataSigner    dataSigner(GenericSignerVerifier sv)    { return sv; }
    @Bean TokenVerifier tokenVerifier(GenericSignerVerifier sv)  { return sv; }
    @Bean TokenRevoker  tokenRevoker(GenericSignerVerifier sv)   { return sv; }
    @Bean TokenTracker  tokenTracker(GenericSignerVerifier sv)   { return sv; }
}
```

---

## Distributed configuration (Protocol §4)

Veridot supports dynamic, signed configuration scopes broadcasted through the broker hierarchy. This allows ops to adjust session capacity limits, eviction policies, and default TTLs on-the-fly without redeploying services.

### Configuration Hierarchy
Precedence is evaluated from highest to lowest:
1. **Local Scope** (`LOCAL`): Applies strictly to a specific group.
2. **Site Scope** (`SITE`): Applies to all groups declaring a specific `site` in their token metadata.
3. **Global Scope** (`GLOBAL`): Applies globally to all groups in the broker namespace.
4. **Default Scope**: Fallback configuration defined via constructor parameters.

### Security & Authority Verification
Configurations are **fully authenticated** to prevent denial-of-service (DoS) or configuration injection attacks:
- **Signature check**: Every configuration announcement must be signed with a long-term key validated by the `TrustRoot`. Unsigned or invalid configurations are ignored.
- **Authority authorization**: The signer's identity must be authorized for the scope key.

### Publishing a Dynamic Config

```java
// 1. Publish a GLOBAL config: limit all groups to 10 concurrent sessions, LRU eviction
sv.publishConfig(
    ConfigScope.GLOBAL, 
    null,                                     // scopeId not needed for GLOBAL
    10,                                       // maxSessions
    EvictionPolicy.LRU,                       // evictionPolicy
    1800,                                     // defaultTtlSeconds (30 min)
    86400                                     // validitySeconds (expires in 24 hours)
);
```

---

## Environment variables

| Variable | Description | Default |
|----------|-------------|---------|
| `VDOT_KEYS_ROTATION_MINUTES` | Rotation interval for ephemeral RSA-3072 key pairs | `1440` (24 hours) |

---

## Full API reference

### `GenericSignerVerifier` constructors

```java
// Minimal — no session limit
new GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String sid, PrivateKey longTermKey)

// With session capacity management
new GenericSignerVerifier(Broker broker, TrustRoot trustRoot, String sid, PrivateKey longTermKey, int maxSessions, EvictionPolicy evictionPolicy)
```

### `BasicConfigurer` builder methods

| Method | Required | Description |
|--------|:--------:|-------------|
| `.groupId(String)` | ✅ | Logical group (userId, clientId…) |
| `.sequenceId(String)` | — | Session ID within group (UUID if omitted) |
| `.validity(long seconds)` | ✅ | Token TTL in seconds |
| `.distribution(DistributionMode)` | — | `DIRECT` (default) or `INDIRECT` |
| `.serializedBy(Function<Object, String>)` | — | Custom payload serializer |

### `VerifiedData<T>`

```java
record VerifiedData<T>(T data, String groupId, String sequenceId) {}
```

---

## Building and testing

```bash
# All unit tests (no external infrastructure required)
./mvnw test -pl veridot-core --no-transfer-progress

# Integration tests (requires Docker for Testcontainers)
./mvnw test -pl veridot-tests -am --no-transfer-progress
```

**v4.0.0 test results:** 98 unit tests, 34 integration tests, 0 failures, 0 errors.

---

## Security model

> **The broker is transport only — never an authority.**

| Threat | Mitigation |
|--------|:-----------|
| Broker injection (forge key announcement) | TrustRoot signature verification |
| Tombstone forgery (fake revocation) | Signed revocation envelopes |
| Tombstone replay | Versioned / timestamped epochs |
| Expired key accumulation (RocksDB bloat) | Built-in TTL compaction |
| Clock drift | ±5 min timestamp validation |

---

## License

[MIT](../../LICENSE) · **Kunrin SA** · [frank.kossi@kunrin.com](mailto:frank.kossi@kunrin.com)
