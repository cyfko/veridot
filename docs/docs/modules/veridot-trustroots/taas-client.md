---
title: taas-client
description: The TAAS Java Client SDK and TrustRoot implementation for Veridot V5.
keywords: [taas-client, SDK, TrustRoot, veridot, V5]
sidebar_position: 5
---

# taas-client

The `taas-client` module provides the Java 21 SDK for interacting with a TAAS cluster. It serves two primary roles:

1. **Instance Registration**: Provides the APIs for an instance to submit its public key and attestation proof upon boot.
2. **Key Resolution**: Implements the `TrustRootProvider` interface required by `veridot-core` for verifying signatures.

## Maven Dependency

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-trustroots-taas-client</artifactId>
    <version>5.0.0</version>
</dependency>
```

## Registration Client (TaasPublisherClient)

When a service starts, it uses the `TaasPublisherClient` to acquire its identity. The client handles the protocol handshake and formats the request.

```java
import java.time.Duration;
import io.github.cyfko.veridot.trustroots.taas.client.TaasPublisherClient;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.api.KeyAlgorithm;

TaasPublisherClient client = new TaasPublisherClient(
    List.of("https://taas.internal.company.com"), // Endpoints
    null,                                         // SSLContext
    Duration.ofSeconds(5)                         // Timeout
);

// Build the TrustEntry
TrustEntry entry = TrustEntry.builder()
    .subject("api-gateway@a1b2c3...")
    .publicKeyEncoded("...")
    .algorithm(KeyAlgorithm.ED25519)
    // ... complete with notBefore, version, etc.
    .build();

// Register with an explicit Attestation Proof (e.g. K8S SAT)
client.publish(entry, readSatFromDisk());
```

## TaasTrustRootProvider

For verification, `veridot-core` requires a `TrustRoot` to resolve identities to public keys. The `TaasTrustRootProvider` implements the `TrustRootProvider` interface.

```java
import java.time.Duration;

TrustRootProvider trustRootProvider = new TaasTrustRootProvider(
    List.of("https://taas.internal.company.com"),
    null,
    Duration.ofSeconds(5)
);

// We wrap it in a CachingTrustRoot to minimize network calls (this implements TrustRoot)
TrustRoot trustRoot = new CachingTrustRoot(trustRootProvider, ...);

// Inject into the Veridot processor
var sv = new GenericSignerVerifier(
    broker, 
    trustRoot, 
    "my-service",               // CN
    privateKey,                 // Instance Private Key
    publicKey,                  // Instance Public Key
    Algorithm.ED25519, 
    1000,                       // Max sessions
    EvictionPolicy.FIFO         // Eviction policy
);
```

The client implements circuit breaking and jittered exponential backoff to handle network blips without disrupting offline verification.
