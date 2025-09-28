---
layout: page
title: Security Best Practices
permalink: /docs/security/
nav_order: 5
---

# Security Best Practices

Veridot is designed with security as a first principle. This guide covers essential security considerations, threat models, and best practices for production deployments.

## Cryptographic Foundation

### Supported Algorithms

#### Java Implementation
- **Algorithm**: RSA-2048 with SHA-256
- **Security Level**: >112-bit equivalent
- **Standards Compliance**: FIPS 140-2 Level 1
- **Key Rotation**: Configurable (default: 60 minutes)

#### Node.js Implementation  
- **Algorithm**: ECDSA P-256 (ES256)
- **Security Level**: 128-bit equivalent
- **Standards Compliance**: NIST P-256, FIPS 186-4
- **Key Rotation**: Configurable (default: 60 minutes)

### Why Ephemeral Keys?

Traditional JWT implementations use long-lived shared secrets, creating several security risks:

```diff
- Shared Secret JWT (Traditional)
- ❌ Single point of compromise
- ❌ Key distribution complexity  
- ❌ No forward secrecy
- ❌ Difficult key rotation

+ Ephemeral Asymmetric Keys (Veridot)
+ ✅ Distributed key generation
+ ✅ Automatic key rotation
+ ✅ Forward secrecy guaranteed
+ ✅ Zero shared secrets
```

## Threat Model & Mitigations

### 1. Token Replay Attacks

**Threat**: Attacker intercepts and reuses valid tokens.

**Mitigations**:
- Short token lifetimes (5-60 minutes recommended)
- Unique tracking IDs for correlation and revocation
- Network-level TLS encryption
- Token binding to client certificates (advanced)

```java
// Java: Short-lived tokens
BasicConfigurer config = BasicConfigurer.builder()
    .validity(300)  // 5 minutes for sensitive operations
    .trackedBy(request.getSessionId().hashCode())
    .build();
```

```typescript
// Node.js: Short-lived tokens
const { token } = await dverify.sign(sensitiveData, 300); // 5 minutes
```

### 2. Key Compromise

**Threat**: Private keys are compromised through memory dumps, side-channel attacks, or insider threats.

**Mitigations**:
- Automatic key rotation limits exposure window
- Ephemeral keys are never persisted to disk
- Memory protection through secure coding practices
- Hardware Security Module (HSM) integration (roadmap)

```java
// Java: Frequent rotation for high-security environments
GenericSignerVerifier verifier = new GenericSignerVerifier(broker) {
    // Override default rotation interval
    private static final long ROTATION_MINUTES = 15; // 15 minutes
};
```

### 3. Man-in-the-Middle (MITM) Attacks

**Threat**: Attackers intercept and modify broker communications.

**Mitigations**:
- Mandatory TLS for all broker communications
- Certificate pinning for critical environments
- VPN or private networks for broker traffic
- Message authentication codes (MACs) for metadata integrity

```java
// Java: Secure Kafka configuration
Properties props = new Properties();
props.setProperty("security.protocol", "SASL_SSL");
props.setProperty("ssl.truststore.location", "/path/to/truststore.jks");
props.setProperty("ssl.truststore.password", "truststore-password");
props.setProperty("ssl.keystore.location", "/path/to/keystore.jks");
props.setProperty("ssl.keystore.password", "keystore-password");
```

### 4. Metadata Tampering

**Threat**: Attackers modify public key metadata in transit or storage.

**Mitigations**:
- Cryptographic signing of metadata messages
- Broker-level access controls and authentication
- Network segmentation for broker infrastructure
- Integrity checks on metadata retrieval

### 5. Denial of Service (DoS)

**Threat**: Resource exhaustion through excessive signing/verification requests.

**Mitigations**:
- Rate limiting on token operations
- Connection pooling and circuit breakers
- Resource monitoring and alerting
- Graceful degradation strategies

```java
// Java: Circuit breaker pattern
@CircuitBreaker(name = "veridot-sign", fallbackMethod = "fallbackSign")
public String signWithProtection(Object data, Configurer config) {
    return dataSigner.sign(data, config);
}

public String fallbackSign(Object data, Configurer config, Exception ex) {
    // Return cached token or simplified authentication
    log.warn("Veridot signing failed, using fallback: {}", ex.getMessage());
    return generateFallbackToken(data);
}
```

## Production Security Configuration

### Network Security

#### Kafka Cluster Security
```yaml
# docker-compose.yml - Production Kafka
kafka:
  image: confluentinc/cp-kafka:latest
  environment:
    # Enable SASL_SSL
    KAFKA_SECURITY_INTER_BROKER_PROTOCOL: SASL_SSL
    KAFKA_SASL_MECHANISM_INTER_BROKER_PROTOCOL: PLAIN
    KAFKA_SASL_ENABLED_MECHANISMS: PLAIN
    
    # SSL Configuration
    KAFKA_SSL_KEYSTORE_FILENAME: kafka.keystore.jks
    KAFKA_SSL_KEYSTORE_CREDENTIALS: keystore_creds
    KAFKA_SSL_KEY_CREDENTIALS: key_creds
    KAFKA_SSL_TRUSTSTORE_FILENAME: kafka.truststore.jks
    KAFKA_SSL_TRUSTSTORE_CREDENTIALS: truststore_creds
    
    # Client authentication
    KAFKA_SSL_CLIENT_AUTH: required
  volumes:
    - ./secrets:/etc/kafka/secrets
```

#### Database Security (PostgreSQL)
```yaml
postgres:
  image: postgres:15
  environment:
    POSTGRES_SSL_MODE: require
    POSTGRES_SSL_CERT: /var/lib/postgresql/server.crt
    POSTGRES_SSL_KEY: /var/lib/postgresql/server.key
    POSTGRES_SSL_CA: /var/lib/postgresql/ca.crt
  volumes:
    - ./certs:/var/lib/postgresql/certs:ro
```

### Application Security

#### Java Security Configuration
```java
@Configuration
@EnableWebSecurity
public class VeridotSecurityConfig {
    
    @Bean
    public MetadataBroker secureMetadataBroker() {
        Properties props = new Properties();
        
        // Kafka security
        props.setProperty("security.protocol", "SASL_SSL");
        props.setProperty("sasl.mechanism", "SCRAM-SHA-512");
        props.setProperty("sasl.jaas.config", createJaasConfig());
        
        // SSL configuration
        props.setProperty("ssl.truststore.location", 
            environment.getProperty("veridot.ssl.truststore"));
        props.setProperty("ssl.truststore.password", 
            environment.getProperty("veridot.ssl.truststore.password"));
        
        // Security enhancements
        props.setProperty("ssl.endpoint.identification.algorithm", "https");
        props.setProperty("ssl.protocol", "TLSv1.3");
        
        return KafkaMetadataBrokerAdapter.of(props);
    }
    
    private String createJaasConfig() {
        return String.format(
            "org.apache.kafka.common.security.scram.ScramLoginModule required " +
            "username=\"%s\" password=\"%s\";",
            environment.getProperty("veridot.kafka.username"),
            environment.getProperty("veridot.kafka.password")
        );
    }
}
```

#### Node.js Security Configuration
```typescript
// Secure environment configuration
const secureConfig = {
  kafka: {
    clientId: 'veridot-secure',
    brokers: [process.env.KAFKA_BROKER_SECURE!],
    ssl: {
      rejectUnauthorized: true,
      ca: [fs.readFileSync('./certs/ca.pem')],
      key: fs.readFileSync('./certs/client-key.pem'),
      cert: fs.readFileSync('./certs/client-cert.pem'),
    },
    sasl: {
      mechanism: 'scram-sha-512',
      username: process.env.KAFKA_USERNAME!,
      password: process.env.KAFKA_PASSWORD!,
    },
  },
  lmdb: {
    path: process.env.SECURE_DB_PATH!,
    compression: true,
    encryption: true, // Custom implementation
  }
};
```

## Security Monitoring

### Metrics to Monitor

```java
// Java: Security metrics with Micrometer
@Component
public class VeridotSecurityMetrics {
    
    private final Counter tokenSignFailures;
    private final Counter tokenVerifyFailures;
    private final Counter keyRotationEvents;
    private final Timer verificationLatency;
    
    public VeridotSecurityMetrics(MeterRegistry registry) {
        this.tokenSignFailures = Counter.builder("veridot.sign.failures")
            .description("Failed token signing attempts")
            .tag("type", "security")
            .register(registry);
            
        this.tokenVerifyFailures = Counter.builder("veridot.verify.failures")
            .description("Failed token verification attempts")
            .tag("reason", "expired")
            .register(registry);
            
        this.keyRotationEvents = Counter.builder("veridot.key.rotations")
            .description("Key rotation events")
            .register(registry);
    }
    
    public void recordSignFailure(String reason) {
        tokenSignFailures.increment(Tags.of("reason", reason));
    }
    
    public void recordVerifyFailure(String reason) {
        tokenVerifyFailures.increment(Tags.of("reason", reason));
    }
}
```

### Alerting Rules

```yaml
# Prometheus alerting rules
groups:
  - name: veridot-security
    rules:
      - alert: VeridotHighFailureRate
        expr: rate(veridot_verify_failures_total[5m]) > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High Veridot token verification failure rate"
          
      - alert: VeridotKeyRotationFailed
        expr: increase(veridot_key_rotations_total[1h]) == 0
        for: 2h
        labels:
          severity: critical
        annotations:
          summary: "Veridot key rotation appears to have stopped"
          
      - alert: VeridotBrokerDown
        expr: up{job="veridot-broker"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Veridot metadata broker is down"
```

## Security Audit Checklist

### Pre-Production Checklist

- [ ] **Cryptographic Configuration**
  - [ ] Strong algorithms selected (RSA-2048+ or ECDSA P-256+)
  - [ ] Appropriate key rotation intervals configured
  - [ ] No hardcoded cryptographic materials

- [ ] **Network Security**
  - [ ] TLS enabled for all broker communications
  - [ ] Certificate validation enabled
  - [ ] Network segmentation implemented
  - [ ] Access controls configured

- [ ] **Application Security**
  - [ ] Input validation on all token operations
  - [ ] Proper error handling without information leakage
  - [ ] Rate limiting implemented
  - [ ] Security logging enabled

- [ ] **Operational Security**
  - [ ] Security monitoring configured
  - [ ] Incident response procedures documented
  - [ ] Regular security updates scheduled
  - [ ] Backup and recovery procedures tested

### Runtime Security Checks

```java
// Java: Runtime security validation
@Component
public class VeridotSecurityValidator {
    
    @EventListener
    public void validateSecurityConfiguration(ApplicationReadyEvent event) {
        // Check TLS configuration
        if (!isTlsEnabled()) {
            throw new SecurityException("TLS must be enabled in production");
        }
        
        // Validate key rotation settings
        if (getKeyRotationInterval() > Duration.ofHours(4)) {
            log.warn("Key rotation interval exceeds recommended maximum");
        }
        
        // Check broker connectivity with security
        validateSecureBrokerConnection();
    }
    
    private boolean isTlsEnabled() {
        String protocol = environment.getProperty("veridot.kafka.security.protocol");
        return protocol != null && protocol.contains("SSL");
    }
}
```

## Compliance Considerations

### GDPR Compliance
- Token contents should not include personal data directly
- Implement data minimization in token payloads
- Provide mechanisms for token revocation (right to be forgotten)
- Log retention policies for security events

### SOC 2 Type II
- Comprehensive access logging
- Change management for security configurations
- Regular security assessments
- Incident response documentation

### HIPAA (Healthcare)
- Encrypt tokens containing PHI references
- Audit all access to health-related tokens
- Implement business associate agreements
- Regular risk assessments

## Emergency Response

### Security Incident Response

1. **Immediate Response**
   ```bash
   # Revoke all tokens for affected user/system
   curl -X POST /api/admin/revoke \
     -H "Authorization: Bearer $ADMIN_TOKEN" \
     -d '{"trackingId": 12345}'
   ```

2. **Key Rotation**
   ```java
   // Force immediate key rotation
   @Autowired
   private GenericSignerVerifier veridot;
   
   public void emergencyKeyRotation() {
       veridot.forceKeyRotation(); // Custom implementation
       log.error("Emergency key rotation completed");
   }
   ```

3. **Broker Isolation**
   ```yaml
   # Temporarily isolate compromised broker
   kafka:
     security.protocol: SSL
     ssl.client.auth: required
     # Update client certificates
   ```

### Recovery Procedures

1. **Assess Impact**: Determine scope of compromise
2. **Isolate Systems**: Prevent further damage
3. **Rotate Keys**: Generate new key pairs
4. **Update Configurations**: Apply security patches
5. **Monitor**: Enhanced monitoring post-incident
6. **Document**: Lessons learned and improvements

## Future Security Enhancements

### Roadmap Items
- **Post-Quantum Cryptography**: Migration to quantum-resistant algorithms
- **Hardware Security Modules**: HSM integration for key protection
- **Zero-Trust Architecture**: Enhanced identity verification
- **Homomorphic Encryption**: Computation on encrypted tokens

### Research Areas
- Token binding to hardware attestation
- Confidential computing integration
- Advanced threat detection using ML
- Blockchain-based key distribution