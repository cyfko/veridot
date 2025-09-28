---
layout: page
title: API Reference
permalink: /docs/api-reference/
nav_order: 4
---

# API Reference

Complete reference documentation for all Veridot interfaces, classes, and methods.

## Java API

### Core Interfaces

#### `DataSigner`
Primary interface for token creation with ephemeral keys.

```java
public interface DataSigner {
    String sign(Object data, Configurer configurer) 
        throws DataSerializationException, BrokerTransportException;
}
```

**Parameters:**
- `data` - Object to be signed (must not be null)
- `configurer` - Configuration for signing (mode, duration, tracking, serialization)

**Returns:** Signed token as string (JWT or reference ID depending on mode)

**Exceptions:**
- `DataSerializationException` - If payload cannot be serialized
- `BrokerTransportException` - If metadata publishing fails

#### `DataSigner.Configurer`
Configuration interface for signing operations.

```java
public interface Configurer {
    TokenMode getMode();                    // jwt or id
    long getTracker();                      // Tracking ID for revocation
    long getDuration();                     // Validity in seconds
    Function<Object, String> getSerializer(); // Custom serializer
}
```

#### `TokenVerifier`
Primary interface for token validation.

```java
public interface TokenVerifier {
    <T> T verify(String token, Function<String, T> deserializer) 
        throws BrokerExtractionException, DataDeserializationException;
}
```

**Parameters:**
- `token` - Token to verify (JWT or reference ID)
- `deserializer` - Function to convert payload string to object

**Returns:** Deserialized payload object

**Exceptions:**
- `BrokerExtractionException` - If token is invalid, expired, or key not found
- `DataDeserializationException` - If payload cannot be deserialized

#### `TokenRevoker`
Interface for token invalidation.

```java
public interface TokenRevoker {
    void revoke(Object target); // Token string or tracking ID (Long)
}
```

#### `MetadataBroker`
Interface for public key distribution.

```java
public interface MetadataBroker {
    CompletableFuture<Void> send(String key, String message);
    String get(String key) throws BrokerExtractionException;
}
```

### Implementation Classes

#### `GenericSignerVerifier`
Default implementation combining all interfaces.

```java
public class GenericSignerVerifier 
    implements DataSigner, TokenVerifier, TokenRevoker {
    
    // Constructors
    public GenericSignerVerifier(MetadataBroker broker)
    public GenericSignerVerifier(MetadataBroker broker, String salt)
}
```

**Features:**
- Automatic RSA key pair generation and rotation
- Ephemeral keys with configurable intervals (default: 60 minutes)
- JWT and ID token modes
- Integrated revocation support

#### `BasicConfigurer`
Builder-pattern implementation of `DataSigner.Configurer`.

```java
public class BasicConfigurer implements DataSigner.Configurer {
    
    public static Builder builder() { ... }
    
    public static class Builder {
        public Builder useMode(TokenMode mode)
        public Builder validity(long seconds)
        public Builder trackedBy(long id)
        public Builder serializedBy(Function<Object, String> serializer)
        public BasicConfigurer build()
    }
    
    // Static helper methods
    public static <T> Function<String, T> deserializer(Class<T> clazz)
}
```

**Example Usage:**
```java
BasicConfigurer config = BasicConfigurer.builder()
    .useMode(TokenMode.jwt)
    .trackedBy(12345L)
    .validity(3600)
    .build();
```

#### `KafkaMetadataBrokerAdapter`
Kafka-based metadata broker implementation.

```java
public class KafkaMetadataBrokerAdapter implements MetadataBroker {
    public static KafkaMetadataBrokerAdapter of(Properties props)
}
```

**Required Properties:**
- `bootstrap.servers` - Kafka broker addresses
- `embedded.db.path` - Local RocksDB storage path

**Optional Properties:**
- `security.protocol` - SASL_SSL, SSL, SASL_PLAINTEXT, PLAINTEXT
- `sasl.mechanism` - PLAIN, SCRAM-SHA-256, SCRAM-SHA-512
- `batch.size` - Producer batch size (default: 16384)
- `compression.type` - none, gzip, snappy, lz4

### Enums

#### `TokenMode`
Token generation modes.

```java
public enum TokenMode {
    jwt,  // Embed data in JWT token
    id    // Return reference ID, store JWT in broker
}
```

### Exceptions

#### `DataSerializationException`
Thrown when payload cannot be serialized.

```java
public class DataSerializationException extends RuntimeException {
    public DataSerializationException(String message)
    public DataSerializationException(String message, Throwable cause)
}
```

#### `DataDeserializationException`
Thrown when payload cannot be deserialized.

```java
public class DataDeserializationException extends RuntimeException {
    public DataDeserializationException(String message)
    public DataDeserializationException(String message, Throwable cause)
}
```

#### `BrokerTransportException`
Thrown when broker communication fails.

```java
public class BrokerTransportException extends RuntimeException {
    public BrokerTransportException(String message)
    public BrokerTransportException(String message, Throwable cause)
}
```

#### `BrokerExtractionException`
Thrown when token verification fails.

```java
public class BrokerExtractionException extends RuntimeException {
    public BrokerExtractionException(String message)
    public BrokerExtractionException(String message, Throwable cause)
}
```

---

## Node.js/TypeScript API

### Core Classes

#### `DVerify`
Unified signing and verification class.

```typescript
class DVerify {
    constructor(
        broker?: string,
        kafkaTopic?: string,
        dbPath?: string,
        rotationIntervalMs?: number
    )
    
    async sign(
        message: Record<string, any>, 
        duration?: number
    ): Promise<SignResponse>
    
    async verify<T>(token: string): Promise<VerifyResponse<T>>
}
```

#### `DataSigner`
Dedicated signing service.

```typescript
class DataSigner {
    constructor(
        broker?: string,
        kafkaTopic?: string,
        dbPath?: string,
        rotationIntervalMs?: number
    )
    
    async sign<T>(data: T, durationSeconds: number): Promise<string>
}
```

#### `DataVerifier`
Dedicated verification service.

```typescript
class DataVerifier {
    constructor(
        broker?: string,
        kafkaTopic?: string,
        dbPath?: string,
        cleanupIntervalMs?: number
    )
    
    async verify<T>(token: string): Promise<T>
}
```

### Type Definitions

#### `SignResponse`
Response from signing operations.

```typescript
interface SignResponse {
    token: string;
}
```

#### `VerifyResponse<T>`
Response from verification operations.

```typescript
interface VerifyResponse<T> {
    valid: boolean;
    data: T;
}
```

#### `KeyRecord`
Internal key storage format.

```typescript
interface KeyRecord {
    kind: string;        // Token type (jwt/uuid)
    publicKey: string;   // PEM-formatted public key
    expiration: number;  // Expiration timestamp
    variant: string;     // Additional metadata
}
```

### Configuration

#### Environment Variables

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `KAFKA_BROKER` | string | `localhost:9093` | Kafka broker URL |
| `DVERIFY_KAFKA_TOPIC` | string | `public_keys_topic` | Topic for key exchange |
| `DVERIFY_DB_PATH` | string | `./signer-db` | LMDB storage path |
| `DVERIFY_KEY_ROTATION_MS` | number | `3600000` | Key rotation interval (ms) |
| `DVERIFY_CLEANUP_INTERVAL_MS` | number | `1800000` | Cleanup interval (ms) |

### Exceptions

#### `JsonEncodingException`
Thrown when token generation fails.

```typescript
class JsonEncodingException extends Error {
    constructor(message: string)
}
```

#### `DataExtractionException`
Thrown when token verification fails.

```typescript
class DataExtractionException extends Error {
    constructor(message: string)
}
```

---

## Cross-Language Interoperability

### Metadata Format
Both implementations use the same metadata format for cross-language compatibility:

```
Format: [mode]:[publicKey]:[expiration]:[variant]
Example: jwt:MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...:1640995200:
```

### Token Structure
- **JWT Mode**: Standard JWT with `data` claim and `sub` field for key ID
- **ID Mode**: Short reference ID that maps to stored JWT in broker

### Key Exchange Protocol
1. Service generates ephemeral key pair
2. Public key metadata published to broker with expiration
3. Verifying services consume metadata and cache locally
4. Expired keys automatically cleaned up

---

## Best Practices

### Error Handling
```java
// Java
try {
    Data result = verifier.verify(token, Data.class);
    // Process result
} catch (BrokerExtractionException e) {
    // Token invalid/expired - return 401
} catch (DataDeserializationException e) {
    // Data corruption - return 500
}
```

```typescript
// TypeScript
try {
    const result = await verifier.verify<Data>(token);
    // Process result
} catch (error) {
    if (error.message.includes('expired')) {
        // Token expired - return 401
    } else {
        // Other error - return 500
    }
}
```

### Token Lifetimes
- **Short operations**: 5-15 minutes
- **User sessions**: 1-2 hours  
- **Background processes**: 24 hours maximum
- **Never exceed**: Key rotation interval

### Performance Tips
- Cache verification results for repeated tokens
- Use connection pooling for broker connections
- Monitor key rotation and cleanup operations
- Implement circuit breakers for broker failures