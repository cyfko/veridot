---
title: spring-autoconfiguration
description: Spring Boot starters for rapidly integrating Veridot V5 and the TAAS client.
keywords: [spring-boot, autoconfiguration, veridot, V5]
sidebar_position: 7
---

# spring-autoconfiguration

The `spring-autoconfiguration` module provides Spring Boot starters to dramatically reduce boilerplate when deploying Veridot Protocol V5.

## Maven Dependency

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-spring-boot-starter</artifactId>
    <version>5.0.0</version>
</dependency>
```

## Auto-Wired Components

The starter automatically configures and injects the following beans into your application context:

1. **`TAASClientTrustRoot`**: Configured based on the `veridot.taas.endpoints` property.
2. **`Broker`**: Depending on your classpath, it will auto-configure either a `KafkaBroker` (if `veridot-kafka` is present) or a `DatabaseBroker` (if `veridot-databases` is present).
3. **`GenericSignerVerifier`**: The core V5 protocol engine, ready for injection.

## Application Properties

```yaml
veridot:
  identity:
    common-name: "order-service"
  taas:
    # Use a single Load Balancer URL in production
    endpoints: 
      - "https://taas.internal.company.com"
  broker:
    mode: "NATIVE"
    kafka:
      bootstrap-servers: "kafka:9092"
```

## Usage in Services

Simply autowire the `DataSigner` or `TokenVerifier` interfaces into your controllers or services.

```java
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final DataSigner signer;

    public OrderController(DataSigner signer) {
        this.signer = signer;
    }

    @PostMapping
    public ResponseEntity<String> createOrder() {
        // Sign using the V5 NATIVE mode
        String token = signer.sign("order-data", config -> config
            .setGroupId("user-123")
            .setDistribution(DistributionMode.NATIVE)
        );
        return ResponseEntity.ok(token);
    }
}
```

The Spring Boot starter seamlessly integrates the V5 Single Key Per Instance lifecycle. It automatically generates the ED25519 key on startup, performs the TAAS registration using the available environment attestation, and cleanly shuts down the `LivenessManager` upon application exit, sending the explicit `LIVENESS(REVOKED)` entry.
