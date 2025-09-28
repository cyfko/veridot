---
layout: page
title: Java Implementation Guide
permalink: /docs/java-guide/
nav_order: 2
---

# Java Implementation Guide

This comprehensive guide covers everything you need to implement Veridot in your Java applications.

## Table of Contents
- [Installation](#installation)
- [Core Concepts](#core-concepts)
- [Basic Usage](#basic-usage)
- [Configuration](#configuration)
- [Advanced Features](#advanced-features)
- [Spring Boot Integration](#spring-boot-integration)
- [Production Deployment](#production-deployment)

## Installation

### Maven
```xml
<dependencies>
    <!-- Core API -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-core</artifactId>
        <version>2.0.1</version>
    </dependency>
    
    <!-- Choose your broker implementation -->
    
    <!-- Option 1: Kafka-based metadata distribution -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-kafka</artifactId>
        <version>2.0.1</version>
    </dependency>
    
    <!-- Option 2: Database-based metadata storage -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-databases</artifactId>
        <version>2.0.1</version>
    </dependency>
</dependencies>
```

### Gradle
```gradle
dependencies {
    implementation 'io.github.cyfko:veridot-core:2.0.1'
    implementation 'io.github.cyfko:veridot-kafka:2.0.1'
    // or
    // implementation 'io.github.cyfko:veridot-databases:2.0.1'
}
```

## Core Concepts

### Key Interfaces

- **`DataSigner`** - Creates cryptographically signed tokens
- **`TokenVerifier`** - Validates token signatures using distributed public keys
- **`MetadataBroker`** - Handles public key distribution (Kafka, Database, Custom)
- **`TokenRevoker`** - Enables immediate token invalidation

### Token Modes

- **`TokenMode.jwt`** - Embeds data directly in JWT (recommended for small payloads)
- **`TokenMode.id`** - Returns reference ID, stores JWT in broker (for large payloads)

## Basic Usage

### Simple Setup

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.*;
import io.github.cyfko.veridot.kafka.KafkaMetadataBrokerAdapter;

// Configure Kafka broker
Properties kafkaProps = new Properties();
kafkaProps.setProperty("bootstrap.servers", "localhost:9092");
kafkaProps.setProperty("embedded.db.path", "./veridot-keys");

// Initialize signer/verifier
MetadataBroker broker = KafkaMetadataBrokerAdapter.of(kafkaProps);
GenericSignerVerifier veridot = new GenericSignerVerifier(broker);
```

### Signing Data

```java
// Create data to sign
public class UserSession {
    private String userId;
    private List<String> roles;
    private long expiresAt;
    
    // constructors, getters, setters...
}

UserSession session = new UserSession("user123", List.of("ADMIN", "USER"));

// Configure signing
BasicConfigurer config = BasicConfigurer.builder()
    .useMode(TokenMode.jwt)        // Embed data in JWT
    .trackedBy(session.getUserId().hashCode())  // For revocation
    .validity(3600)                // 1 hour validity
    .build();

// Sign and get token
String token = veridot.sign(session, config);
```

### Verifying Tokens

```java
// In another service
try {
    UserSession verified = veridot.verify(token, 
        BasicConfigurer.deserializer(UserSession.class));
    
    System.out.println("User: " + verified.getUserId());
    System.out.println("Roles: " + verified.getRoles());
    
} catch (BrokerExtractionException e) {
    // Token invalid, expired, or key not found
    log.warn("Token verification failed: {}", e.getMessage());
    
} catch (DataDeserializationException e) {
    // Token valid but payload corrupted
    log.error("Data deserialization failed: {}", e.getMessage());
}
```

## Configuration

### Kafka Broker Configuration

```java
Properties props = new Properties();

// Basic configuration
props.setProperty("bootstrap.servers", "kafka1:9092,kafka2:9092,kafka3:9092");
props.setProperty("embedded.db.path", "/app/data/veridot");

// Security configuration (for production)
props.setProperty("security.protocol", "SASL_SSL");
props.setProperty("sasl.mechanism", "PLAIN");
props.setProperty("sasl.jaas.config", 
    "org.apache.kafka.common.security.plain.PlainLoginModule required " +
    "username=\"veridot-user\" password=\"secure-password\";");

// Performance tuning
props.setProperty("batch.size", "16384");
props.setProperty("linger.ms", "5");
props.setProperty("compression.type", "snappy");

MetadataBroker broker = KafkaMetadataBrokerAdapter.of(props);
```

### Database Broker Configuration

```java
Properties props = new Properties();
props.setProperty("jdbc.url", "jdbc:postgresql://db:5432/veridot");
props.setProperty("jdbc.username", "veridot_user");
props.setProperty("jdbc.password", "secure_password");
props.setProperty("jdbc.driver", "org.postgresql.Driver");

// Connection pool settings
props.setProperty("connection.pool.size", "20");
props.setProperty("connection.timeout", "30000");

MetadataBroker broker = DatabaseMetadataBroker.of(props);
```

### Custom Serialization

```java
// Custom serializer for specific data formats
Function<Object, String> customSerializer = data -> {
    if (data instanceof SpecialData) {
        return ((SpecialData) data).toCustomFormat();
    }
    return new ObjectMapper().writeValueAsString(data);
};

BasicConfigurer config = BasicConfigurer.builder()
    .useMode(TokenMode.jwt)
    .serializedBy(customSerializer)
    .trackedBy(123L)
    .validity(1800)
    .build();
```

## Advanced Features

### Token Revocation

```java
// Revoke by tracking ID (affects all tokens with that ID)
TokenRevoker revoker = (TokenRevoker) veridot;
revoker.revoke(12345L);

// Revoke specific token
revoker.revoke("eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzI1NiJ9...");
```

### ID Mode for Large Payloads

```java
// For large documents or complex objects
LargeDocument document = new LargeDocument(/* large data */);

BasicConfigurer config = BasicConfigurer.builder()
    .useMode(TokenMode.id)         // Return reference ID only
    .trackedBy(document.getId())
    .validity(1800)                // 30 minutes
    .build();

String documentId = veridot.sign(document, config); // Returns short ID

// Later, in another service
LargeDocument verified = veridot.verify(documentId, 
    BasicConfigurer.deserializer(LargeDocument.class));
```

### Error Handling with Retry

```java
@Retryable(value = {BrokerTransportException.class}, maxAttempts = 3)
public String signWithRetry(Object data, DataSigner.Configurer config) {
    try {
        return dataSigner.sign(data, config);
    } catch (BrokerTransportException e) {
        log.warn("Broker communication failed, retrying: {}", e.getMessage());
        throw e; // Will trigger retry
    }
}
```

## Spring Boot Integration

### Configuration Class

```java
@Configuration
@EnableConfigurationProperties(VeridotProperties.class)
public class VeridotConfig {
    
    @Bean
    public MetadataBroker metadataBroker(VeridotProperties properties) {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", properties.getKafka().getBootstrapServers());
        props.setProperty("embedded.db.path", properties.getDbPath());
        
        return KafkaMetadataBrokerAdapter.of(props);
    }
    
    @Bean
    public DataSigner dataSigner(MetadataBroker broker) {
        return new GenericSignerVerifier(broker);
    }
    
    @Bean 
    public TokenVerifier tokenVerifier(MetadataBroker broker) {
        return new GenericSignerVerifier(broker);
    }
}
```

### Properties Configuration

```java
@ConfigurationProperties(prefix = "veridot")
@Data
public class VeridotProperties {
    private String dbPath = "./veridot-keys";
    private Kafka kafka = new Kafka();
    
    @Data
    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String topic = "veridot-keys";
        private Security security = new Security();
        
        @Data
        public static class Security {
            private String protocol = "PLAINTEXT";
            private String mechanism;
            private String jaasConfig;
        }
    }
}
```

### Application Properties

```properties
# application.yml
veridot:
  db-path: /app/data/veridot
  kafka:
    bootstrap-servers: kafka1:9092,kafka2:9092,kafka3:9092
    topic: veridot-metadata
    security:
      protocol: SASL_SSL
      mechanism: PLAIN
      jaas-config: |
        org.apache.kafka.common.security.plain.PlainLoginModule required
        username="veridot-user"
        password="${KAFKA_PASSWORD}";
```

### Service Implementation

```java
@Service
@Slf4j
public class TokenService {
    
    private final DataSigner dataSigner;
    private final TokenVerifier tokenVerifier;
    
    public TokenService(DataSigner dataSigner, TokenVerifier tokenVerifier) {
        this.dataSigner = dataSigner;
        this.tokenVerifier = tokenVerifier;
    }
    
    public String createUserToken(User user) {
        UserTokenData tokenData = new UserTokenData(
            user.getId(), 
            user.getRoles(), 
            Instant.now().plusSeconds(3600)
        );
        
        BasicConfigurer config = BasicConfigurer.builder()
            .useMode(TokenMode.jwt)
            .trackedBy(user.getId())
            .validity(3600)
            .build();
        
        try {
            return dataSigner.sign(tokenData, config);
        } catch (Exception e) {
            log.error("Failed to sign token for user {}", user.getId(), e);
            throw new TokenCreationException("Token creation failed", e);
        }
    }
    
    public Optional<UserTokenData> verifyToken(String token) {
        try {
            UserTokenData userData = tokenVerifier.verify(token,
                BasicConfigurer.deserializer(UserTokenData.class));
            return Optional.of(userData);
        } catch (BrokerExtractionException | DataDeserializationException e) {
            log.warn("Token verification failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
```

## Production Deployment

### Docker Configuration

```yaml
# docker-compose.yml
version: '3.8'
services:
  app:
    image: myapp:latest
    environment:
      KAFKA_BROKERS: kafka:9092
      VERIDOT_DB_PATH: /app/data/veridot
      JAVA_OPTS: -XX:+UseG1GC -Xmx2g
    volumes:
      - veridot_data:/app/data
    depends_on:
      - kafka

volumes:
  veridot_data:
```

### Monitoring with Micrometer

```java
@Component
public class VeridotMetrics {
    private final Counter signCounter;
    private final Counter verifyCounter;
    private final Timer verificationLatency;
    
    public VeridotMetrics(MeterRegistry registry) {
        this.signCounter = Counter.builder("veridot.tokens.signed")
            .description("Total tokens signed")
            .register(registry);
            
        this.verifyCounter = Counter.builder("veridot.tokens.verified")
            .tag("status", "success")
            .register(registry);
            
        this.verificationLatency = Timer.builder("veridot.verification.duration")
            .description("Token verification latency")
            .register(registry);
    }
    
    // Use in your service methods
    public String signWithMetrics(Object data, DataSigner.Configurer config) {
        signCounter.increment();
        return Timer.Sample.start(registry)
            .stop(verificationLatency, () -> dataSigner.sign(data, config));
    }
}
```

### Health Checks

```java
@Component
public class VeridotHealthIndicator implements HealthIndicator {
    
    private final MetadataBroker broker;
    
    @Override
    public Health health() {
        try {
            // Test broker connectivity
            String testKey = "health-check-" + System.currentTimeMillis();
            broker.send(testKey, "health-check").get(5, TimeUnit.SECONDS);
            
            return Health.up()
                .withDetail("broker", "connected")
                .build();
        } catch (Exception e) {
            return Health.down()
                .withDetail("broker", "disconnected")
                .withDetail("error", e.getMessage())
                .build();
        }
    }
}
```

## Next Steps

- Learn about [Node.js implementation]({{ '/docs/nodejs-guide' | relative_url }})
- Review [Security best practices]({{ '/docs/security' | relative_url }})
- Explore [API Reference]({{ '/docs/api-reference' | relative_url }})
- See [Production deployment examples]({{ '/docs/deployment' | relative_url }})