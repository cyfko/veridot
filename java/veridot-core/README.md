# veridot-core

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-core.svg)](https://central.sonatype.com/artifact/io.github.cyfko/veridot-core)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](../../LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://openjdk.org/)
[![Build](https://img.shields.io/badge/tests-89%20passed-brightgreen.svg)]()

Core module of **Veridot** — the Java implementation of the [Protocol V3.0](../../PROTOCOL_V3.md) distributed token verification protocol.

This README is the **self-contained reference** for using Veridot in Java. You do not need to visit any external website to get started.

---

## Table of contents

- [What Veridot does](#what-veridot-does)
- [Installation](#installation)
- [Core concepts](#core-concepts)
- [TrustAnchor — the security cornerstone](#trustanchor--the-security-cornerstone)
- [First integration: sign, verify, revoke](#first-integration-sign-verify-revoke)
- [Distribution modes: DIRECT vs INDIRECT](#distribution-modes-direct-vs-indirect)
- [Session capacity management](#session-capacity-management)
- [Token tracking](#token-tracking)
- [Custom serialization (POJOs, JSON…)](#custom-serialization-pojos-json)
- [Error handling](#error-handling)
- [Spring Boot integration](#spring-boot-integration)
- [Distributed configuration (Protocol §5)](#distributed-configuration-protocol-5)
- [Environment variables](#environment-variables)
- [Full API reference](#full-api-reference)
- [Migration from v3.0.1](#migration-from-v301)
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
  → signs payload → JWT                  → validates TrustAnchor signature
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
        <version>3.1.0</version>
    </dependency>

    <!-- Pick exactly one broker -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-kafka</artifactId>      <!-- Kafka + RocksDB (recommended) -->
        <version>3.1.0</version>
    </dependency>
    <!-- OR -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-databases</artifactId>  <!-- SQL database -->
        <version>3.1.0</version>
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
    implementation 'io.github.cyfko:veridot-core:3.1.0'
    implementation 'io.github.cyfko:veridot-kafka:3.1.0'  // or veridot-databases
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
| **`MetadataBroker`** | Transports key announcements between service instances (Kafka, SQL…) |
| **`TrustAnchor`** | Validates key announcements — ensures the broker cannot be used to forge keys |
| **`GenericSignerVerifier`** | Default implementation of all four interfaces |
| **`groupId`** | A logical namespace for sessions (e.g., a userId, a client ID) |
| **`sequenceId`** | A unique session within a group (e.g., a device ID, a session UUID) |
| **`VerifiedData<T>`** | Immutable record: `data`, `groupId`, `sequenceId` |

---

## TrustAnchor — the security cornerstone

> Before v3.0, any node with Kafka write-access could inject a fraudulent key announcement and obtain valid verification results. **TrustAnchor closes this gap entirely.**

Every key announcement now carries `sid` and `sig` — a long-term RSA signature. Verifiers check this signature via a `TrustAnchor` **independently** of the broker. Broker write-access alone cannot produce a valid announcement.

`TrustAnchor` is a `sealed` interface with two permitted implementations:

### Option A — `PublicKeyResolver` (you load the public key, Veridot verifies)

Best for: public keys stored in files, Vault KV, ConfigMaps, or any key-value store.

```java
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) sid -> {
    // Load the long-term public key for this sid from your trust store.
    // Called once per unique sid, result may be cached by your implementation.
    byte[] pem = Files.readAllBytes(Paths.get("/etc/veridot/trust/" + sid + ".pub.pem"));
    return parsePemPublicKey(pem);
};
```

### Option B — `DelegatedVerifier` (you delegate verification to a KMS)

Best for: Vault Transit Engine, AWS KMS, Google Cloud KMS, Azure Key Vault, HSMs.
The long-term private key never leaves the KMS.

```java
TrustAnchor anchor = (TrustAnchor.DelegatedVerifier) (sid, canonicalBytes, signature) -> {
    // Your KMS verifies the RSA signature. Throw TrustResolutionException on failure.
    boolean valid = vaultTransit.verify(sid, canonicalBytes, signature);
    if (!valid) {
        throw new TrustResolutionException.SignatureRejected(
            "KMS rejected announcement signature for sid=" + sid);
    }
};
```

### Failure semantics — never silently accept

```java
// The TrustAnchor can throw two checked subtypes:
//
//   TrustResolutionException.Unavailable
//     → KMS/trust store temporarily unreachable (network issue, timeout)
//     → ALWAYS fail safe: reject the token. Log a WARNING. Alert ops.
//     → NEVER catch and treat as "cannot verify → accept".
//
//   TrustResolutionException.SignatureRejected
//     → Cryptographic rejection. The announcement was tampered with.
//     → Log SEVERE. Trigger a security alert. This may be an attack.
//
// Both are wrapped in BrokerExtractionException when raised from verify().
try {
    VerifiedData<String> result = sv.verify(token, s -> s);
} catch (BrokerExtractionException e) {
    if (e.getCause() instanceof TrustResolutionException.Unavailable) {
        alertOps("TrustAnchor temporarily unavailable: " + e.getMessage());
    } else if (e.getCause() instanceof TrustResolutionException.SignatureRejected) {
        alertSecurity("Possible broker injection attempt: " + e.getMessage());
    }
    throw e;
}
```

---

## First integration: sign, verify, revoke

A minimal but complete working example using the Kafka broker.

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.*;
import io.github.cyfko.veridot.kafka.KafkaMetadataBrokerAdapter;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Properties;

// ── 1. Configure Kafka broker ─────────────────────────────────────
Properties kafkaProps = new Properties();
kafkaProps.setProperty("bootstrap.servers", "kafka:9092");
kafkaProps.setProperty("embedded.db.path", "/var/lib/veridot");
MetadataBroker broker = KafkaMetadataBrokerAdapter.of(kafkaProps);

// ── 2. Load long-term private key ─────────────────────────────────
// In production: load from Vault, Kubernetes Secret, or KMS.
byte[] pkcs8Bytes = Files.readAllBytes(Paths.get("/etc/veridot/private.key"));
PrivateKey longTermKey = KeyFactory.getInstance("RSA")
    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));

// ── 3. Configure TrustAnchor ──────────────────────────────────────
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
    byte[] pem = Files.readAllBytes(Paths.get("/etc/veridot/trust/" + signerId + ".pub.pem"));
    return parsePemPublicKey(pem); // your PEM parsing helper
};

// ── 4. Build signer/verifier ──────────────────────────────────────
var sv = new GenericSignerVerifier(broker, anchor, "auth-service", longTermKey);

// ── 5. Sign ───────────────────────────────────────────────────────
String token = sv.sign("alice@example.com",
    BasicConfigurer.builder()
        .groupId("user-alice")         // logical group (e.g. userId)
        .sequenceId("mobile-app-v2")   // session identifier (optional, UUID if omitted)
        .validity(3600)                // TTL: 1 hour
        .build());

// ── 6. Verify (from any service sharing the same broker + trust anchor)
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

// messageId = "2:reports:report-2026-q2"
// → store in your DB, pass to trusted services as a reference

// Verify using messageId — identical API
VerifiedData<ReportData> result = sv.verify(messageId,
    json -> mapper.readValue(json, ReportData.class));
```

**Use INDIRECT when:**
- Payload is large (> a few KB) and should not be embedded in a JWT
- Payload is sensitive and should not transit over client-facing channels
- You want to invalidate a specific data document, not just a session

---

## Session capacity management

Enforce a maximum number of concurrent active sessions per `groupId`. Useful for limiting concurrent device logins.

```java
// Allow up to 3 sessions, evict the oldest on overflow (FIFO)
var sv = new GenericSignerVerifier(
    broker, anchor, "auth-service", longTermKey,
    3, GenericSignerVerifier.EvictionPolicy.FIFO);

// Allow only 1 session. Reject any attempt to create a second.
var sv = new GenericSignerVerifier(
    broker, anchor, "auth-service", longTermKey,
    1, GenericSignerVerifier.EvictionPolicy.REJECT);
```

### Eviction policies

| Policy | Behavior when `maxSessions` is reached |
|--------|---------------------------------------|
| `FIFO` | Evicts the session with the **oldest** timestamp |
| `LIFO` | Evicts the session with the **newest** timestamp |
| `LRU` | Same as FIFO in current implementation |
| `REJECT` | Throws `SessionCapacityExceededException` — no eviction |

```java
// Handle REJECT policy
try {
    String token = sv.sign(data, config);
} catch (SessionCapacityExceededException e) {
    // e.getGroupId()     → which group was at capacity
    // e.getMaxSessions() → the configured limit
    log.warn("Session limit reached for group={}, max={}", e.getGroupId(), e.getMaxSessions());
    // Suggested UX: "You've reached the maximum of N active sessions.
    //                Please log out from another device."
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
boolean isActive = sv.hasActiveToken("2:reports:report-2026-q2");
```

Useful for:
- Pre-flight checks before a protected operation
- Monitoring dashboards ("how many users have active sessions?")
- Logout flows ("is there anything left to revoke?")

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

All Veridot exceptions are `RuntimeException` subclasses (except `TrustResolutionException` which is checked).

### During `verify()`

```java
try {
    VerifiedData<String> result = sv.verify(token, s -> s);
    // ← success path

} catch (BrokerExtractionException e) {
    // Covers all verification failures:
    //   • Token expired (timestamp + ttl < now)
    //   • Session revoked (__REVOKE__ tombstone received)
    //   • TrustAnchor rejected the key announcement (security event)
    //   • TrustAnchor temporarily unavailable (fail safe)
    //   • JWT signature invalid (tampered token)
    //   • MessageId not found in broker
    response.sendError(401);

} catch (DataDeserializationException e) {
    // Payload deserialization failed.
    // Indicates a version mismatch or data corruption — investigate.
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
    // The token was NOT issued.
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
    public TrustAnchor veridotTrustAnchor() {
        return (TrustAnchor.PublicKeyResolver) id -> {
            byte[] pem = Files.readAllBytes(Paths.get(trustDir, id + ".pub.pem"));
            return parsePemPublicKey(pem);
        };
    }

    @Bean
    public MetadataBroker veridotBroker(
            @Value("${spring.kafka.bootstrap-servers}") String servers) {
        Properties p = new Properties();
        p.setProperty("bootstrap.servers", servers);
        p.setProperty("embedded.db.path", "/var/lib/veridot");
        p.setProperty("security.protocol", "SASL_SSL");
        p.setProperty("sasl.mechanism", "SCRAM-SHA-512");
        p.setProperty("sasl.jaas.config", buildJaasConfig());
        return KafkaMetadataBrokerAdapter.of(p);
    }

    @Bean
    public GenericSignerVerifier veridot(MetadataBroker broker,
                                          TrustAnchor anchor,
                                          PrivateKey longTermKey) {
        return new GenericSignerVerifier(
            broker, anchor, signerId, longTermKey,
            maxSessions, GenericSignerVerifier.EvictionPolicy.FIFO);
    }

    // Expose individual interfaces as Spring beans
    @Bean DataSigner    dataSigner(GenericSignerVerifier sv)    { return sv; }
    @Bean TokenVerifier tokenVerifier(GenericSignerVerifier sv)  { return sv; }
    @Bean TokenRevoker  tokenRevoker(GenericSignerVerifier sv)   { return sv; }
    @Bean TokenTracker  tokenTracker(GenericSignerVerifier sv)   { return sv; }
}
```

### `application.yml`

```yaml
veridot:
  signer-id: auth-service
  private-key-path: /var/secrets/veridot/private.key
  trust-dir: /etc/veridot/trust
  max-sessions: 5

spring:
  kafka:
    bootstrap-servers: kafka1:9092,kafka2:9092
```

### Service layer

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class SessionService {

    private final DataSigner    signer;
    private final TokenVerifier verifier;
    private final TokenRevoker  revoker;

    public String createSession(String userId, String deviceId) {
        return signer.sign(userId,
            BasicConfigurer.builder()
                .groupId(userId)
                .sequenceId(deviceId)
                .validity(3600)
                .build());
    }

    public Optional<VerifiedData<String>> validateToken(String token) {
        try {
            return Optional.of(verifier.verify(token, s -> s));
        } catch (BrokerExtractionException e) {
            log.warn("Token validation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void logout(String userId, String deviceId) {
        revoker.revoke(userId, deviceId);
    }

    public void logoutEverywhere(String userId) {
        revoker.revoke(userId, null); // GDPR right-to-erasure at session level
    }
}
```

### Spring Security filter (JWT Bearer)

```java
@Component
@RequiredArgsConstructor
public class VeridotAuthFilter extends OncePerRequestFilter {

    private final TokenVerifier verifier;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(req, res);
            return;
        }

        try {
            VerifiedData<String> result = verifier.verify(
                header.substring(7), s -> s);

            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                    result.data(), null, List.of());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
            SecurityContextHolder.getContext().setAuthentication(auth);

        } catch (BrokerExtractionException e) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        chain.doFilter(req, res);
    }
}
```

---

## Distributed configuration (Protocol §5)

Veridot supports dynamic, signed configuration scopes broadcasted through the broker hierarchy. This allows ops to adjust session capacity limits, eviction policies, and default TTLs on-the-fly without redeploying services.

### Configuration Hierarchy
Precedence is evaluated from highest to lowest:
1. **Local Scope** (`3:<groupId>:__CONFIG__`): Applies strictly to a specific group (e.g. a high-risk user account or specific customer).
2. **Site Scope** (`3:__CONFIG__:<siteId>`): Applies to all groups declaring a specific `site` in their token metadata.
3. **Global Scope** (`3:__CONFIG__:__ALL__`): Applies globally to all groups in the broker namespace.
4. **Default Scope**: Fallback configuration defined via constructor parameters.

### Security & Authority Validation
Configurations are **fully authenticated** to prevent denial-of-service (DoS) or configuration injection attacks:
- **Signature check**: Every configuration announcement must be signed with a long-term key validated by the `TrustAnchor`. Unsigned or invalid configurations are ignored.
- **Authority authorization**: The signer's identity (`sid`) must be explicitly authorized for the scope key according to the `TrustAnchor.isAuthorizedForScope(sid, configKey)` policy.

### Rich API & Code Example

#### Publishing a Dynamic Config
Use the `publishConfig` method on `GenericSignerVerifier` to push configurations dynamically:

```java
// 1. Publish a GLOBAL config: limit all groups to 10 concurrent sessions, LRU eviction
sv.publishConfig(
    GenericSignerVerifier.ConfigScope.GLOBAL, 
    null,                                     // scopeId not needed for GLOBAL
    10,                                       // maxSessions
    GenericSignerVerifier.EvictionPolicy.LRU, // evictionPolicy
    1800,                                     // defaultTtlSeconds (30 min)
    86400                                     // validitySeconds (expires in 24 hours)
);

// 2. Publish a LOCAL config for a target group: limit to 2 sessions, REJECT any more
sv.publishConfig(
    GenericSignerVerifier.ConfigScope.LOCAL, 
    "vip-customer-group",                      // target groupId
    2,                                        // maxSessions
    GenericSignerVerifier.EvictionPolicy.REJECT, 
    3600,                                     // defaultTtlSeconds (1 hour)
    604800                                    // validitySeconds (expires in 7 days)
);

// 3. Publish a SITE config (applies to a datacenter or region):
sv.publishConfig(
    GenericSignerVerifier.ConfigScope.SITE, 
    "site-europe-west",                       // siteId
    5,                                        // maxSessions
    GenericSignerVerifier.EvictionPolicy.FIFO, 
    7200,                                     // defaultTtlSeconds (2 hours)
    86400                                     // validitySeconds
);
```

#### Client Verification & Configuration Resolution
The verifier automatically resolves configurations at token-signing time to enforce limits. Cache entries are valid for 60 seconds (by default) to avoid hitting the broker on every single request.

```java
// Inside your application: signing will automatically enforce the config
// If the limit of "vip-customer-group" is exceeded, it will throw:
try {
    String token = sv.sign("VIP Data", BasicConfigurer.builder()
        .groupId("vip-customer-group")
        .validity(3600)
        .build());
} catch (SessionCapacityExceededException e) {
    System.out.println("Capacity exceeded: " + e.getMaxSessions() + " sessions maximum.");
}
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
new GenericSignerVerifier(MetadataBroker, TrustAnchor, String sid, PrivateKey)

// With session capacity management
new GenericSignerVerifier(MetadataBroker, TrustAnchor, String sid, PrivateKey, int maxSessions, EvictionPolicy)
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

### Exception hierarchy

```
RuntimeException
└── VeridotException
    ├── DataSerializationException     — sign() payload serialization failed
    ├── DataDeserializationException   — verify() payload deserialization failed
    ├── BrokerTransportException       — broker send failed
    ├── BrokerExtractionException      — verify() failed (expired / revoked / invalid)
    └── SessionCapacityExceededException — maxSessions reached + REJECT policy
        ├── .getGroupId() : String
        └── .getMaxSessions() : int

Exception (checked)
└── TrustResolutionException (sealed)
    ├── .Unavailable          — trust store temporarily unreachable → fail safe
    └── .SignatureRejected    — cryptographic rejection → security event
```

### `TrustAnchor` (sealed interface)

```java
sealed interface TrustAnchor
    permits TrustAnchor.PublicKeyResolver, TrustAnchor.DelegatedVerifier

non-sealed interface PublicKeyResolver extends TrustAnchor {
    PublicKey resolve(String sid) throws TrustResolutionException;
}

non-sealed interface DelegatedVerifier extends TrustAnchor {
    void verify(String sid, byte[] canonicalBytes, byte[] signature)
        throws TrustResolutionException;
}
```

---

## Migration from v3.0.1

The only breaking change between v3.0.1 and v3.1.0 is the `GenericSignerVerifier` constructor.

```java
// ✗ v3.0.1 — salt had no cryptographic value
var sv = new GenericSignerVerifier(broker, "my-salt");
var sv = new GenericSignerVerifier(broker, "my-salt", 3, EvictionPolicy.FIFO);

// ✓ v3.1.0 — real cryptographic identity
var sv = new GenericSignerVerifier(broker, anchor, "my-service-id", longTermKey);
var sv = new GenericSignerVerifier(broker, anchor, "my-service-id", longTermKey, 3, EvictionPolicy.FIFO);
```

**Migration steps:**

1. **Generate a long-term RSA-3072 key pair** per signing service:
   ```bash
   openssl genrsa -out private.pem 3072
   openssl rsa -in private.pem -pubout -out public.pem
   ```

2. **Store the private key** in Vault, a Kubernetes Secret, or your KMS.
   Never commit it to source control.

3. **Distribute the public key** to all verifying services (Vault KV, ConfigMap, or a shared PEM directory).
   The public key is not sensitive.

4. **Implement `TrustAnchor`** (3–5 lines):
   ```java
   TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) id -> loadPublicKey(id);
   ```

5. **Update `pom.xml`** to Java 25:
   ```xml
   <maven.compiler.source>25</maven.compiler.source>
   <maven.compiler.target>25</maven.compiler.target>
   ```

6. **Update unit tests** (use the in-memory test setup):
   ```java
   // Test helper — generate a local key pair, no infrastructure needed
   KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
   gen.initialize(2048); // 2048 sufficient for tests
   KeyPair kp = gen.generateKeyPair();

   TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) id -> kp.getPublic();
   var sv = new GenericSignerVerifier(broker, anchor, "test-svc", kp.getPrivate());
   ```

---

## Building and testing

```bash
# All unit tests (no external infrastructure required)
./mvnw test -pl veridot-core --no-transfer-progress

# Integration tests (requires Docker for Testcontainers)
./mvnw test -pl veridot-tests --no-transfer-progress

# Build without tests
./mvnw package -pl veridot-core -DskipTests --no-transfer-progress
```

**v3.1.0 unit test results:**

| Test suite | Tests | Status |
|------------|------:|--------|
| `TrustAnchorSecurityTest` | 9 | ✅ |
| `ConfigTrustSecurityTest` | 7 | ✅ |
| `VerificationTest` | 10 | ✅ |
| `RevocationTest` | 10 | ✅ |
| `TokenTrackerTest` | 8 | ✅ |
| `SigningTest` | 9 | ✅ |
| `SessionCapacityTest` | 14 | ✅ |
| `MultiInstanceSessionTest` | 4 | ✅ |
| `ProtocolTest` | 27 | ✅ |
| **Total** | **98** | **0 failures** |

---

## Security model

> **The broker is transport only — never an authority.**

| Threat | Mitigation since |
|--------|:-----------------|
| Broker injection (forge key announcement) | TrustAnchor `sig` — v3.1.0 |
| Tombstone forgery (fake revocation) | Long-term `sig` — v3.1.0 |
| Tombstone replay | Latest-timestamp-wins — v3.1.0 |
| Race read-after-write (same node) | `MetadataBroker.sendLocal()` pre-populates RocksDB — v3.1.0 |
| Expired key accumulation (RocksDB bloat) | TTL compaction task every 5 min — v3.1.0 |
| Blocking `sign()` on slow broker | 10-second eviction timeout — v3.1.0 |
| Clock drift | ±5 min timestamp validation — v2.0 |
| Token replay after expiry | `exp` claim + `timestamp + ttl` enforcement — v2.0 |

For the full threat model, hardening checklist, and incident response procedures, see the [Security Model page](../../docs/security.md) on the documentation site.

---

## License

[MIT](../../LICENSE) · **Kunrin SA** · [frank.kossi@kunrin.com](mailto:frank.kossi@kunrin.com)
