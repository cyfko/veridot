# Veridot Token Architecture

## Overview

This document describes the modular and extensible architecture of Veridot, a system for securely issuing, verifying, and revoking short-lived cryptographic tokens in distributed environments.

---

## Components

### 1. `DataSigner` (Token Generator)

Responsible for creating signed tokens from a given data payload.

```java
String sign(Object data, Configurer configurer);
```

* **Inputs**: Payload (`Object`), configuration (duration, mode, tracker)
* **Output**: Signed token (e.g., JWT or UUID-based string)

---

### 2. `TokenVerifier` (Token Validator)

Verifies token authenticity and extracts the original payload.

```java
<T> T verify(String token, Function<String, T> deserializer);
```

* **Inputs**: Token string, deserialization function
* **Output**: Deserialized original data

---

### 3. `DataTransformer` (Serialization Layer)

Handles data serialization and deserialization.

```java
String serialize(Object data);
Object deserialize(String data);
```

* Supports: JSON, XML, Protobuf, etc.
* Used by both signing and verifying modules

---

### 4. `MetadataBroker` (Metadata Distributor)

Broadcasts and retrieves metadata required for token verification.

```java
CompletableFuture<Void> send(String key, String message);
String get(String key);
```

* **Responsibilities**:

    * Distribute ephemeral public keys
    * Store and retrieve token-related metadata
    * Enable loose coupling between signer and verifier

---

### 5. `TokenRevoker` (Revocation Service)

Blacklists specific token IDs or full token strings.

```java
void revoke(Object target);
```

* **Inputs**: Token UUID, long tracker, or token string
* **Used by**: Security systems, logout mechanisms, abuse prevention

---

## Flow Diagram

```
+----------------+       (sign)       +--------------------+
|  Issuer Service| --------------->   |    DataSigner      |
+----------------+                   +--------------------+
        |                                   |
        |           (send key)             |
        |--------------------------------->|
        |           MetadataBroker         |
        |<---------------------------------|
        |                                   |
        |                                   |--> Sign -> Token (JWT/UUID)
        |                                   |
        |                                   v
        |----------------- token --------->|
+------------------+                    +--------------------+
|   Consumer Svc   | ---- verify ------> |   TokenVerifier    |
+------------------+                    +--------------------+
         ^                                     |
         |         (get key metadata)          |
         |-------------------------------------|
         |           MetadataBroker            |
         |<------------------------------------|
```

---

## Customization Points

| Component         | Extension Point                     | Purpose                        |
| ----------------- | ----------------------------------- | ------------------------------ |
| `TokenMode`       | Enum or strategy pattern            | Choose between JWT, UUID, etc. |
| `DataTransformer` | Replace with Jackson, XML, etc.     | Format flexibility             |
| `Configurer`      | Implementation injected in context  | Token config per use-case      |
| `MetadataBroker`  | Redis, Kafka, in-memory, DB, etc.   | Message propagation layer      |
| `TokenRevoker`    | Cache or persistent blacklist store | Revoke by ID or full token     |

---

## Best Practices

* Use **ephemeral keys** and rotate frequently
* Configure short **token TTLs** via `Configurer.getDuration()`
* Use `Configurer.getTracker()` to link tokens to users or sessions
* Abstract serialization with `DataTransformer` to support evolution
* Allow **pluggable modes** beyond JWT (e.g., encrypted formats)

---

## License

[MIT License](LICENSE)

---

For implementation samples, see relevant implementation provider or contact core maintainers.
