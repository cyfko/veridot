---
title: Installation
description: Install Veridot modules via Maven or Gradle, configure version management, and verify your setup with a simple test.
keywords: [veridot installation, maven, gradle, java 25, veridot-core, veridot-kafka, veridot-databases, setup]
sidebar_position: 5
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Installation

This page covers how to add Veridot to your Java project, manage module versions, and verify your installation.

## Requirements

| Requirement | Version | Notes |
|---|---|---|
| **Java** | **25+** | Required by `veridot-core`. `veridot-kafka` and `veridot-databases` modules compile on Java 17+ but require `veridot-core` at runtime. |
| **Maven** or **Gradle** | Any modern version | Maven 3.9+ or Gradle 8+ recommended |
| **Apache Kafka** | 3.x+ | Required only if using `veridot-kafka` |
| **JDBC Database** | See [supported DBs](./choosing-a-broker.md#supported-databases) | Required only if using `veridot-databases` |

:::danger Java 25 is Required
Veridot uses Java 25+ features (sealed interfaces, records, pattern matching). It will **not compile or run** on earlier JDK versions. Verify your Java version:

```bash
java -version
# Expected: openjdk 25.0.x or later
```
:::

## Module Overview

Veridot is split into focused modules. Pick only what you need:

| Module | Artifact | Purpose |
|---|---|---|
| **veridot-core** | `io.github.cyfko:veridot-core` | Core API: `GenericSignerVerifier`, `TrustRoot`, Protocol V4 engine |
| **veridot-kafka** | `io.github.cyfko:veridot-kafka` | Kafka + RocksDB `Broker` implementation |
| **veridot-databases** | `io.github.cyfko:veridot-databases` | SQL `Broker` implementation (PostgreSQL, MySQL, Oracle, MSSQL, H2) |
| **veridot-trustroots** | `io.github.cyfko:veridot-trustroots-*` | Advanced TrustRoot implementations (TAD Server, caching, Spring integration) |

## Core + Kafka (Recommended)

The most common setup for production microservices:

<Tabs>
  <TabItem value="maven" label="Maven" default>

```xml
<properties>
    <veridot.version>4.0.1</veridot.version>
</properties>

<dependencies>
    <!-- Core API and Protocol V4 engine -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-core</artifactId>
        <version>${veridot.version}</version>
    </dependency>

    <!-- Kafka + RocksDB broker -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-kafka</artifactId>
        <version>${veridot.version}</version>
    </dependency>
</dependencies>
```

  </TabItem>
  <TabItem value="gradle" label="Gradle">

```groovy
ext {
    veridotVersion = '4.0.1'
}

dependencies {
    // Core API and Protocol V4 engine
    implementation "io.github.cyfko:veridot-core:${veridotVersion}"

    // Kafka + RocksDB broker
    implementation "io.github.cyfko:veridot-kafka:${veridotVersion}"
}
```

  </TabItem>
</Tabs>

## Core + SQL Database

For teams preferring a SQL-backed broker:

<Tabs>
  <TabItem value="maven" label="Maven" default>

```xml
<properties>
    <veridot.version>4.0.1</veridot.version>
</properties>

<dependencies>
    <!-- Core API and Protocol V4 engine -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-core</artifactId>
        <version>${veridot.version}</version>
    </dependency>

    <!-- SQL broker (PostgreSQL, MySQL, Oracle, MSSQL, H2) -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-databases</artifactId>
        <version>${veridot.version}</version>
    </dependency>

    <!-- Add your JDBC driver -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <version>42.7.3</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

  </TabItem>
  <TabItem value="gradle" label="Gradle">

```groovy
ext {
    veridotVersion = '4.0.1'
}

dependencies {
    // Core API and Protocol V4 engine
    implementation "io.github.cyfko:veridot-core:${veridotVersion}"

    // SQL broker (PostgreSQL, MySQL, Oracle, MSSQL, H2)
    implementation "io.github.cyfko:veridot-databases:${veridotVersion}"

    // Add your JDBC driver
    runtimeOnly 'org.postgresql:postgresql:42.7.3'
}
```

  </TabItem>
</Tabs>

## All Modules

If you need both broker options or want access to everything:

<Tabs>
  <TabItem value="maven" label="Maven" default>

```xml
<properties>
    <veridot.version>4.0.1</veridot.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-core</artifactId>
        <version>${veridot.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-kafka</artifactId>
        <version>${veridot.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-databases</artifactId>
        <version>${veridot.version}</version>
    </dependency>
</dependencies>
```

  </TabItem>
  <TabItem value="gradle" label="Gradle">

```groovy
ext {
    veridotVersion = '4.0.1'
}

dependencies {
    implementation "io.github.cyfko:veridot-core:${veridotVersion}"
    implementation "io.github.cyfko:veridot-kafka:${veridotVersion}"
    implementation "io.github.cyfko:veridot-databases:${veridotVersion}"
}
```

  </TabItem>
</Tabs>

## Version Management

All Veridot modules share the same version number. Always use the same version across all modules to avoid binary incompatibilities.

<Tabs>
  <TabItem value="maven" label="Maven" default>

Define the version once in a property:

```xml
<properties>
    <veridot.version>4.0.1</veridot.version>
</properties>
```

Or in a parent POM's `<dependencyManagement>`:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.cyfko</groupId>
            <artifactId>veridot-core</artifactId>
            <version>${veridot.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.cyfko</groupId>
            <artifactId>veridot-kafka</artifactId>
            <version>${veridot.version}</version>
        </dependency>
        <dependency>
            <groupId>io.github.cyfko</groupId>
            <artifactId>veridot-databases</artifactId>
            <version>${veridot.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

  </TabItem>
  <TabItem value="gradle" label="Gradle">

Use a version catalog (`gradle/libs.versions.toml`):

```toml
[versions]
veridot = "4.0.1"

[libraries]
veridot-core = { module = "io.github.cyfko:veridot-core", version.ref = "veridot" }
veridot-kafka = { module = "io.github.cyfko:veridot-kafka", version.ref = "veridot" }
veridot-databases = { module = "io.github.cyfko:veridot-databases", version.ref = "veridot" }
```

Then in your `build.gradle`:

```groovy
dependencies {
    implementation libs.veridot.core
    implementation libs.veridot.kafka
}
```

  </TabItem>
</Tabs>

## Verify Your Installation

Run this minimal test to confirm everything is wired correctly. This uses an in-memory H2 database so you don't need Kafka running:

<Tabs>
  <TabItem value="maven" label="Maven" default>

```xml
<!-- Add H2 for the verification test -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224</version>
    <scope>test</scope>
</dependency>
```

  </TabItem>
  <TabItem value="gradle" label="Gradle">

```groovy
testImplementation 'com.h2database:h2:2.2.224'
```

  </TabItem>
</Tabs>

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.*;
import io.github.cyfko.veridot.databases.DatabaseBroker;
import org.h2.jdbcx.JdbcDataSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

public class VeridotInstallationTest {

    public static void main(String[] args) throws Exception {
        // 1. In-memory H2 database (no external dependencies)
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:veridot_test;DB_CLOSE_DELAY=-1");
        Broker broker = new DatabaseBroker(ds, "veridot_entries");

        // 2. Generate test key pair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        String issuerId = "test-issuer";

        PublicKeyTrustRoot trustRoot = issuer -> {
            if (issuerId.equals(issuer)) {
                return new TrustIdentity(kp.getPublic(), true);
            }
            throw new RuntimeException("Unknown issuer: " + issuer);
        };

        // 3. Create, sign, verify
        try (var veridot = new GenericSignerVerifier(
                broker, trustRoot, issuerId,
                kp.getPrivate(), Algorithm.ED25519)) {

            String token = veridot.sign("hello-veridot",
                BasicConfigurer.builder()
                    .groupId("test-group")
                    .validity(60)
                    .build());

            VerifiedData<String> result = veridot.verify(token, s -> s);

            assert "hello-veridot".equals(result.data()) : "Payload mismatch!";
            assert "test-group".equals(result.groupId()) : "GroupId mismatch!";

            System.out.println("✅ Veridot installation verified successfully!");
            System.out.println("   Core version: 4.0.1");
            System.out.println("   Payload: " + result.data());
            System.out.println("   Group: " + result.groupId());
        }
    }
}
```

If you see `✅ Veridot installation verified successfully!`, your setup is complete.

## Transitive Dependencies

Veridot keeps its dependency footprint minimal:

| Module | Key Dependencies |
|---|---|
| `veridot-core` | Jackson Databind (JSON serialization) |
| `veridot-kafka` | Apache Kafka Client, RocksDB JNI |
| `veridot-databases` | None (uses `javax.sql.DataSource` — bring your own JDBC driver) |

:::tip Dependency Conflicts
If your project already uses Jackson or Kafka Client, Veridot uses standard versions that are compatible with most modern projects. Use Maven's `<dependencyManagement>` or Gradle's `platform()` to enforce your preferred versions if conflicts arise.
:::

## What's Next?

- **[Quickstart](./quickstart.md)** — build a complete sign/verify/revoke application
- **[Choosing a Broker](./choosing-a-broker.md)** — understand the trade-offs between Kafka and SQL
- **[What Is Veridot?](./what-is-veridot.md)** — understand the authentication trilemma and Veridot's solution
