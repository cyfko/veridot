---
title: Quickstart
description: Get a complete Veridot V5 sign/verify/revoke cycle working in 5 minutes with a step-by-step Java 21 example using NATIVE mode.
keywords: [veridot quickstart, getting started, java example, sign verify revoke, InstanceManager, taas]
sidebar_position: 3
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Quickstart

Get Veridot V5 running in **5 minutes**. By the end, you'll have a working Java 21 application that registers an instance, signs a payload natively, verifies it without network calls, and revokes it instantly.

## Prerequisites

- **Java 21+** (required by `veridot-core` V5)
- **Apache Kafka** running locally (or use Testcontainers)
- A local **TAAS** cluster (or use the embedded mock for testing)

## Step 1: Add Dependencies

<Tabs>
  <TabItem value="maven" label="Maven" default>

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>5.0.0</version>
</dependency>
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>5.0.0</version>
</dependency>
```

  </TabItem>
  <TabItem value="gradle" label="Gradle">

```groovy
implementation 'io.github.cyfko:veridot-core:5.0.0'
implementation 'io.github.cyfko:veridot-kafka:5.0.0'
```

  </TabItem>
</Tabs>

## Step 2: Configure the Broker and TAAS

Veridot V5 requires an untrusted broker for state distribution and a TAAS client for identity resolution.

```java
import io.github.cyfko.veridot.kafka.KafkaBroker;
import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.core.taas.MockTaasClient;
import java.util.Properties;

// 1. Configure Kafka Broker with embedded RocksDB cache
Properties kafkaProps = new Properties();
kafkaProps.setProperty("bootstrap.servers", "localhost:9092");
kafkaProps.setProperty("veridot.embedded.db", "/tmp/veridot-v5-quickstart");

Broker broker = new KafkaBroker(kafkaProps);

// 2. Setup TAAS Client (using mock for local development)
var taasClient = new MockTaasClient();
```

## Step 3: Initialize the InstanceManager

The `InstanceManager` is the core V5 primitive. Upon initialization, it generates a single asymmetric key pair, registers with TAAS to obtain a `CN@hash(pk)` identity, and begins tracking broker state.

```java
import io.github.cyfko.veridot.core.InstanceManager;
import io.github.cyfko.veridot.core.Algorithm;

// Initialize InstanceManager
InstanceManager instanceManager = InstanceManager.builder()
    .broker(broker)
    .taasClient(taasClient)
    .algorithm(Algorithm.ED25519)
    .build();

// The instance is now registered with a verifiable identity
System.out.println("Instance Identity: " + instanceManager.getIdentity());
```

## Step 4: Sign a Session Natively

Use the fluent `BasicConfigurer` to sign data in **NATIVE** mode. In V5, `sign()` stores the actual `SIGNED_DATA` envelope in the broker and returns a compact reference.

```java
import io.github.cyfko.veridot.core.BasicConfigurer;

// Sign a string payload, valid for 1 hour
String nativeToken = instanceManager.sign(
    "user@example.com",
    BasicConfigurer.builder()
        .scope("group:user-123")
        .key("session-A")
        .validity(3600) // 1 hour in seconds
        .build()
);

System.out.println("Token: " + nativeToken);
// Output looks like: "8:group:user-123:session-A"
```

Under the hood, `sign()`:
1. Serializes the payload into a `SIGNED_DATA` binary envelope.
2. Signs the envelope using the instance's private key.
3. Publishes the `SIGNED_DATA` and a `LIVENESS(ACTIVE)` attestation to the broker.

## Step 5: Verify the Session

Any service instance connected to the same broker can verify the token natively with sub-millisecond latency:

```java
import io.github.cyfko.veridot.core.VerifiedData;

// Verifies purely from the local RocksDB cache
VerifiedData<String> result = instanceManager.verify(nativeToken, s -> s);

System.out.println("Payload: " + result.data());       // "user@example.com"
System.out.println("Scope:   " + result.scope());      // "group:user-123"
System.out.println("Key:     " + result.key());        // "session-A"
```

The `verify()` call:
- Reads the `SIGNED_DATA` and `LIVENESS` entries from RocksDB (no network call).
- Resolves the identity's public key from the TAAS cache.
- Cryptographically verifies the V5 envelope signature.
- Confirms the session is active.

## Step 6: Revoke the Session

Revocation is instantaneous and cryptographic:

```java
// Revoke a specific session
instanceManager.revoke("group:user-123", "session-A");

// Or revoke ALL sessions in a group atomically
instanceManager.revoke("group:user-123", null);
```

After revocation, the instance publishes a `LIVENESS(REVOKED)` entry with a strictly increasing monotonic version. Any subsequent `verify()` call for the revoked session will throw an exception:

```java
try {
    instanceManager.verify(nativeToken, s -> s);
} catch (Exception e) {
    System.out.println("Session rejected: " + e.getMessage()); // V5204 LIVENESS_REVOKED
}
```

## Complete Example

Here is the full working Java 21 code:

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.taas.MockTaasClient;
import io.github.cyfko.veridot.kafka.KafkaBroker;
import java.util.Properties;

public class VeridotV5Quickstart {

    public static void main(String[] args) throws Exception {
        
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("bootstrap.servers", "localhost:9092");
        kafkaProps.setProperty("veridot.embedded.db", "/tmp/veridot-v5");

        try (var broker = new KafkaBroker(kafkaProps)) {
            
            var taasClient = new MockTaasClient();
            
            try (var instanceManager = InstanceManager.builder()
                    .broker(broker)
                    .taasClient(taasClient)
                    .algorithm(Algorithm.ED25519)
                    .build()) {

                System.out.println("✅ Identity: " + instanceManager.getIdentity());

                // Sign NATIVE
                String token = instanceManager.sign("user@example.com",
                    BasicConfigurer.builder()
                        .scope("group:user-123")
                        .key("session-A")
                        .validity(3600)
                        .build());

                System.out.println("✅ Issued NATIVE token: " + token);

                // Verify
                VerifiedData<String> result = instanceManager.verify(token, s -> s);
                System.out.println("✅ Verified Payload: " + result.data());

                // Revoke
                instanceManager.revoke(result.scope(), result.key());
                System.out.println("✅ Session revoked");

                // Verify post-revocation
                try {
                    instanceManager.verify(token, s -> s);
                    System.out.println("❌ Should not reach here");
                } catch (Exception e) {
                    System.out.println("✅ Post-revocation verify rejected: " + e.getMessage());
                }
            }
        }
    }
}
```

## What's Next?

- **[Choosing a Broker](./choosing-a-broker.md)** — decide between Kafka+RocksDB and SQL for your infrastructure.
- **[Installation](./installation.md)** — full Maven/Gradle setup for all modules.
- **[How It Works](./how-it-works.md)** — understand the V5 architectural primitives in depth.
