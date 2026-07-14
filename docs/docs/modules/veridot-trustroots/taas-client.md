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

## Registration Client

When a service starts, it uses the `TAASRegistrationClient` to acquire its identity. The client handles the protocol handshake and formats the `subject` as `CN@hash(pk)`.

```java
TAASRegistrationClient client = TAASRegistrationClient.builder()
    // Provide a single Load Balancer or API Gateway URL
    // The TAAS cluster transparently handles internal Raft routing
    .endpoints(List.of("https://taas.internal.company.com"))
    .build();

// Register with a Kubernetes Service Account Token (SAT)
client.register(
    "api-gateway",          // Common Name
    myPublicKey,            // Generated ED25519 public key
    "K8S_SAT",              // Attestation Type
    readSatFromDisk()       // The proof
);
```

## TaasTrustRootProvider

For verification, `veridot-core` requires a `TrustRootProvider` to resolve identities to public keys. The `TaasTrustRootProvider` implements this, providing an aggressively caching, highly resilient resolver.

```java
TrustRootProvider trustRootProvider = new TaasTrustRootProvider(
    // Provide a single Load Balancer or API Gateway URL
    List.of("https://taas.internal.company.com")
);

// Inject into the Veridot processor
var sv = new GenericSignerVerifier(
    broker, 
    trustRootProvider, 
    "my-service@a1b2...", 
    privateKey, 
    Algorithm.ED25519
);
```

The client implements circuit breaking and jittered exponential backoff to handle network blips without disrupting offline verification.
