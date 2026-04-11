# veridot-core

[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-core.svg)](https://central.sonatype.com/artifact/io.github.cyfko/veridot-core)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

Core module for **Veridot** — defines the API contracts and provides the default implementation (`GenericSignerVerifier`) conforming to [Protocol V2](../.agent/PROTOCOL_V2.md).

---

## 📦 Installation

**Maven**:
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>2.1.1</version>
</dependency>
```

**Gradle**:
```gradle
implementation 'io.github.cyfko:veridot-core:2.1.1'
```

---

## 🏗️ API Overview

### Interfaces

| Interface | Purpose |
|-----------|---------|
| `DataSigner` | Sign data into a verifiable token (JWT or messageId) |
| `TokenVerifier` | Verify a token and extract the original payload |
| `TokenRevoker` | Revoke a specific token or an entire group |
| `TokenTracker` | Query whether active (non-revoked, non-expired) tokens exist |
| `MetadataBroker` | Transport layer for publishing/retrieving V2 messages |

### Default Implementation

`GenericSignerVerifier` implements all four interfaces:

```java
// Minimal — no session limit
var sv = new GenericSignerVerifier(broker, "salt");

// With session management
var sv = new GenericSignerVerifier(broker, "salt", 5, EvictionPolicy.FIFO);
//                                                  ↑ max 5 concurrent sessions per group
```

---

## 🚀 Usage

### Signing (DIRECT mode — returns JWT)

```java
String jwt = sv.sign("payload-data",
    BasicConfigurer.builder()
        .groupId("user-123")       // required — business entity
        .validity(300)             // required — TTL in seconds
        .build());
```

### Signing (INDIRECT mode — returns messageId)

```java
String messageId = sv.sign("payload-data",
    BasicConfigurer.builder()
        .groupId("user-123")
        .sequenceId("session-A")   // optional — auto-generated UUID if omitted
        .distribution(DistributionMode.INDIRECT)
        .validity(600)
        .build());
// messageId = "2:user-123:session-A"
```

### Verification

```java
// Works with both JWT (DIRECT) and messageId (INDIRECT)
String data = sv.verify(token, String::toString);

// With POJO deserialization
MyPojo pojo = sv.verify(token, BasicConfigurer.deserializer(MyPojo.class));
```

### Revocation

```java
// Revoke a specific token (accepts JWT or messageId)
sv.revoke(jwt);
sv.revoke("2:user-123:session-A");

// Revoke ALL tokens for a group
sv.revokeGroup("user-123");
```

### Tracking

```java
// Check if a group has any active tokens
boolean active = sv.hasActiveToken("user-123");

// Check a specific token
boolean valid = sv.hasActiveToken(jwt);

// Check a specific messageId
boolean exists = sv.hasActiveToken("2:user-123:session-A");
```

---

## ⚙️ Session Capacity Management

When `maxSessions` is set, the signer manages sessions according to the configured eviction policy:

```java
// Auto-evict oldest when limit reached
var sv = new GenericSignerVerifier(broker, "salt", 3, EvictionPolicy.FIFO);

// Or reject new sign() attempts when limit reached
var sv = new GenericSignerVerifier(broker, "salt", 3, EvictionPolicy.REJECT);
// → throws SessionCapacityExceededException on 4th sign()
```

### Eviction Policies

| Policy | Behavior |
|--------|----------|
| `FIFO` | Evicts the **oldest** session (lowest timestamp) |
| `LIFO` | Evicts the **newest** session (highest timestamp) |
| `LRU`  | Evicts the **least recently used** session |
| `REJECT` | **Refuses** the signing attempt — throws `SessionCapacityExceededException` |

### Distributed Configuration (§4)

Session limits can also be configured dynamically via the broker, with a 3-level hierarchy:

1. **Local**: `2:<groupId>:__CONFIG__|maxSessions:<b64>,policy:<b64>,...`
2. **Site**: `2:__CONFIG__:<siteId>|...`
3. **Global**: `2:__CONFIG__:__ALL__|...`
4. **Default**: Constructor parameters

---

## 🔐 Protocol V2 Message Format

```
2:<groupId>:<sequenceId>|mode:<b64>,pubkey:<b64>,timestamp:<b64>,ttl:<b64>
```

- **Version**: always `2`
- **Identifiers**: `[A-Za-z0-9_-]{1,64}`
- **Values**: Base64url-encoded (RFC 4648 §5, no padding)
- **Reserved sequences**: `__CONFIG__`, `__REVOKE__`, `__ALL__`

### Security

- ±5 minute clock drift validation (§9.1)
- Strict TTL enforcement: `now < timestamp + ttl`
- Ephemeral RSA key pairs with configurable rotation interval

---

## 🔧 Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `VDOT_KEYS_ROTATION_MINUTES` | RSA key pair rotation interval | `1440` (24h) |

---

## 📌 Requirements

- Java ≥ 17

---

## 📄 License

[MIT License](../LICENSE)
