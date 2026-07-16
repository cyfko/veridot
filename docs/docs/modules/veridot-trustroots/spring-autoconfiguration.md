---
title: spring-autoconfiguration
description: Spring Boot starters for rapidly integrating Veridot V5 and the TAAS client.
keywords: [spring-boot, autoconfiguration, veridot, V5]
sidebar_position: 7
---

# Spring Boot Integration (spring-autoconfiguration)

Welcome to the Veridot V5 integration guide for Spring Boot. 

The `veridot-trustroots-spring` module is designed to dramatically simplify the integration of cryptography and identity verification into your microservices, hiding the complexity of the trust infrastructure.

---

## 1. Trust Infrastructure Auto-configuration

Adding the Maven dependency immediately activates the auto-configuration:

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-trustroots-spring</artifactId>
    <version>5.0.0</version>
</dependency>
```

The starter automatically instantiates the foundational trust layer:
- **`TaasTrustRootProvider`**: Manages network communication with the TAAS cluster to resolve identities.
- **`CachingTrustRoot`**: Wraps the provider to offer L1 (memory) and L2 (RocksDB) caching, ensuring sub-millisecond verification performance.

Connection to the TAAS cluster is simply configured in your `application.yml` file:

```yaml
veridot:
  taas:
    endpoints: 
      - "https://taas.internal.company.com"
    connect-timeout: 3s
  trustroots:
    provider-type: taas
    l1-max-size: 10000
    refresh-threshold: 1h
```

This cache (`TrustRoot`) is then available in the Spring context and can be injected directly if you only need to resolve identities.

---

## 2. Cryptographic Engine Configuration

To sign data or verify envelopes, the application requires the main engine: the **`GenericSignerVerifier`**. 

This component is not auto-instantiated because it requires elements specific to your deployment environment (your private keys and your Broker configuration). You must assemble it in a dedicated `@Configuration` class.

### A. Complete Configuration Class

To facilitate integration, here is the complete configuration class ready to be copied/pasted into your project. It configures the Broker, instantiates the cryptographic engine by generating an ephemeral key (Single Key Per Instance), and exposes the `DataSigner` and `TokenVerifier` business interfaces.

```java
import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.DataSigner;
import io.github.cyfko.veridot.core.EvictionPolicy;
import io.github.cyfko.veridot.core.TokenVerifier;
import io.github.cyfko.veridot.core.TrustRoot;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.kafka.KafkaBroker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Properties;

@Configuration
public class VeridotConfiguration {

    // 1. Broker Configuration (Kafka Example)
    @Bean
    public Broker veridotBroker(@Value("${spring.kafka.bootstrap-servers}") String servers) {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", servers);
        return new KafkaBroker(props);
    }

    // 2. Cryptographic Engine Instantiation
    @Bean
    public GenericSignerVerifier genericSignerVerifier(
            Broker broker,               
            TrustRoot trustRoot, // Auto-injected by the starter
            @Value("${veridot.identity.common-name}") String commonName) throws Exception {       
        
        // The key pair is generated dynamically in memory (Single Key Per Instance)
        KeyPairGenerator instance = KeyPairGenerator.getInstance(Algorithm.ED25519.getJcaKeyAlg());
        KeyPair keyPair = instance.generateKeyPair();

        return new GenericSignerVerifier(
            broker, 
            trustRoot, 
            commonName,
            keyPair.getPrivate(), 
            keyPair.getPublic(),
            Algorithm.ED25519,
            1000,
            EvictionPolicy.FIFO
        );
    }

    // 3. Business Interfaces Exposure
    @Bean
    public DataSigner dataSigner(GenericSignerVerifier sv) { 
        return sv; 
    }

    @Bean
    public TokenVerifier tokenVerifier(GenericSignerVerifier sv) { 
        return sv; 
    }
}
```

### B. Step Explanations

- **Broker Configuration**: The engine relies on the broker for asynchronous distribution of identities and revocations.
- **Instantiation**: The key is generated in memory (respecting the Zero-Trust and Instance-Native model) and provided to the `GenericSignerVerifier`.
- **Business Exposure**: We expose `DataSigner` and `TokenVerifier` so your business services can inject them easily without coupling to the complete `GenericSignerVerifier` implementation.

## Conclusion

The Spring Boot module eliminates repetitive boilerplate code related to setting up the trust root and distributed cache. The final assembly of the cryptographic engine remains explicit, providing the total control necessary to guarantee security and adapt to any infrastructure.
