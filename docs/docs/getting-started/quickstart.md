---
title: Quickstart
description: Get a complete Veridot sign/verify/revoke cycle working in 5 minutes with a step-by-step Java example.
keywords: [veridot quickstart, getting started, java example, sign verify revoke, GenericSignerVerifier]
sidebar_position: 3
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Quickstart

Get Veridot running in **5 minutes**. By the end, you'll have a working Java application that signs a payload into a token, verifies it, and revokes the session.

## Prerequisites

- **Java 25+** (required by `veridot-core`)
- **Apache Kafka** running locally (or use Testcontainers — see [Choosing a Broker](./choosing-a-broker.md) for the SQL alternative)

## Step 1: Add Dependencies

<Tabs>
  <TabItem value="maven" label="Maven" default>

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>4.0.1</version>
</dependency>
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>4.0.1</version>
</dependency>
```

  </TabItem>
  <TabItem value="gradle" label="Gradle">

```groovy
implementation 'io.github.cyfko:veridot-core:4.0.1'
implementation 'io.github.cyfko:veridot-kafka:4.0.1'
```

  </TabItem>
</Tabs>

## Step 2: Configure the Kafka Broker

The `KafkaBroker` connects to Kafka for entry distribution and uses a local RocksDB directory for sub-millisecond reads:

```java
import io.github.cyfko.veridot.kafka.KafkaBroker;
import io.github.cyfko.veridot.core.Broker;
import java.util.Properties;

Properties kafkaProps = new Properties();
kafkaProps.setProperty("bootstrap.servers", "localhost:9092");
kafkaProps.setProperty("veridot.embedded.db", "/tmp/veridot-quickstart-db");

Broker broker = new KafkaBroker(kafkaProps);
```

:::tip[Embedded DB Path]
The `veridot.embedded.db` property sets the RocksDB directory. Each service instance needs its own local directory. In Kubernetes, use an `emptyDir` volume — persistence across restarts is not required since the Kafka consumer rehydrates from the topic on startup.
:::

## Step 3: Set Up the TrustRoot

The `TrustRoot` establishes out-of-band trust. For this quickstart, we use a `PublicKeyTrustRoot` that maps an issuer ID to its public key:

```java
import io.github.cyfko.veridot.core.PublicKeyTrustRoot;
import io.github.cyfko.veridot.core.TrustIdentity;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

// Generate a long-term Ed25519 key pair (for quickstart only — see production note below)
KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
KeyPair longTermKeyPair = kpg.generateKeyPair();

String issuerId = "auth-service-1";

// Create a TrustRoot that resolves our issuer ID to its public key
PublicKeyTrustRoot trustRoot = issuer -> {
    if (issuerId.equals(issuer)) {
        return new TrustIdentity(longTermKeyPair.getPublic(), true);
    }
    throw new RuntimeException("Unknown issuer: " + issuer);
};
```

:::warning[Single-JVM Limitation]
This example works because the signer and verifier share the **same JVM** and the same `longTermKeyPair` variable. In a real distributed deployment, the verifier service runs in a **different JVM** and needs a way to obtain the signer's long-term public key — it cannot access a local variable from another process.

This is exactly the problem the **Trust Authority Directory (TAD)** solves: it distributes long-term public keys across services via a Raft-replicated cluster. In production, replace the lambda above with a `CachingTrustRoot` connected to a `TadTrustRootProvider`. See the [TrustRoot Setup Guide](../guides/trustroot-setup.md) and [veridot-trustroots module](../modules/veridot-trustroots) for details.
:::

## Step 4: Create GenericSignerVerifier

The `GenericSignerVerifier` is the main entry point — it implements `DataSigner`, `TokenVerifier`, `TokenRevoker`, and `TokenTracker`:

```java
import io.github.cyfko.veridot.core.Algorithm;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;

var veridot = new GenericSignerVerifier(
    broker,
    trustRoot,
    issuerId,
    longTermKeyPair.getPrivate(),
    Algorithm.ED25519
);
```

The `Algorithm.ED25519` parameter specifies the signature algorithm for Protocol V4 envelopes (the long-term key's algorithm). Ephemeral keys default to Ed25519 as well.

## Step 5: Sign a Payload

Use the fluent `BasicConfigurer` builder to sign data:

```java
import io.github.cyfko.veridot.core.impl.BasicConfigurer;

// Sign a string payload, valid for 1 hour
String token = veridot.sign(
    "user@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")        // links token to a user/entity
        .sequenceId("session-A")    // optional: names this session
        .validity(3600)             // 1 hour in seconds
        .build()
);

System.out.println("Token: " + token);
// In DIRECT mode (default), this is a signed JWT
```

Under the hood, `sign()`:
1. Generates an ephemeral Ed25519 key pair
2. Signs the payload into a JWT with the ephemeral private key
3. Publishes `KEY_EPOCH` (ephemeral public key) to the broker
4. Publishes `LIVENESS(ACTIVE)` attestation to the broker
5. Starts a background renewal loop for the liveness attestation

## Step 6: Verify the Token

Any service instance connected to the same broker can verify the token:

```java
import io.github.cyfko.veridot.core.VerifiedData;

VerifiedData<String> result = veridot.verify(token, s -> s);

System.out.println("Payload:  " + result.data());        // "user@example.com"
System.out.println("Group:    " + result.groupId());     // "user-123"
System.out.println("Session:  " + result.sequenceId());  // "session-A"
```

The `verify()` call:
- Reads `KEY_EPOCH` and `LIVENESS` entries from local RocksDB (no network call)
- Validates the Protocol V4 envelope signature against the `TrustRoot`
- Verifies the JWT using the ephemeral public key
- Returns a `VerifiedData<T>` with the deserialized payload and protocol identifiers

:::tip[POJO Deserialization]
For structured payloads, use `BasicConfigurer.deserializer()`:

```java
record UserClaims(String email, String role) {}

// Sign a POJO
String token = veridot.sign(
    new UserClaims("user@example.com", "admin"),
    BasicConfigurer.builder()
        .groupId("user-123")
        .validity(3600)
        .build()
);

// Verify and deserialize back to POJO
VerifiedData<UserClaims> result = veridot.verify(
    token,
    BasicConfigurer.deserializer(UserClaims.class)
);

UserClaims claims = result.data();
// claims.email() → "user@example.com"
// claims.role()  → "admin"
```
:::

## Step 7: Revoke the Session

Revoke a specific session or all sessions for a group:

```java
// Revoke a specific session
veridot.revoke("user-123", "session-A");

// Revoke ALL sessions for the user (e.g., password change, security breach)
veridot.revoke("user-123", null);
```

After revocation, any `verify()` call for the revoked session will throw `BrokerExtractionException`:

```java
try {
    veridot.verify(token, s -> s);
} catch (BrokerExtractionException e) {
    System.out.println("Token revoked: " + e.getMessage());
}
```

## Complete Example

Here's the full working code in a single file:

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.*;
import io.github.cyfko.veridot.kafka.KafkaBroker;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Properties;

public class VeridotQuickstart {

    public static void main(String[] args) throws Exception {

        // 1. Configure Kafka broker
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("bootstrap.servers", "localhost:9092");
        kafkaProps.setProperty("veridot.embedded.db", "/tmp/veridot-quickstart-db");

        try (var broker = new KafkaBroker(kafkaProps)) {

            // 2. Generate long-term key pair (single-JVM quickstart only)
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
            KeyPair longTermKeyPair = kpg.generateKeyPair();
            String issuerId = "auth-service-1";

            // 3. Set up TrustRoot
            PublicKeyTrustRoot trustRoot = issuer -> {
                if (issuerId.equals(issuer)) {
                    return new TrustIdentity(longTermKeyPair.getPublic(), true);
                }
                throw new RuntimeException("Unknown issuer: " + issuer);
            };

            // 4. Create GenericSignerVerifier
            try (var veridot = new GenericSignerVerifier(
                    broker, trustRoot, issuerId,
                    longTermKeyPair.getPrivate(), Algorithm.ED25519)) {

                // 5. Sign
                String token = veridot.sign("user@example.com",
                    BasicConfigurer.builder()
                        .groupId("user-123")
                        .sequenceId("session-A")
                        .validity(3600)
                        .build());

                System.out.println("✅ Token issued: " + token.substring(0, 50) + "...");

                // 6. Verify
                VerifiedData<String> result = veridot.verify(token, s -> s);
                System.out.println("✅ Verified: " + result.data());
                System.out.println("   Group:   " + result.groupId());
                System.out.println("   Session: " + result.sequenceId());

                // 7. Revoke
                veridot.revoke(result.groupId(), result.sequenceId());
                System.out.println("✅ Session revoked");

                // 8. Verify after revocation — should fail
                try {
                    veridot.verify(token, s -> s);
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

- **[Choosing a Broker](./choosing-a-broker.md)** — decide between Kafka+RocksDB and SQL for your infrastructure
- **[Installation](./installation.md)** — full Maven/Gradle setup for all modules
- **[How It Works](./how-it-works.md)** — understand the cryptographic layers in depth
