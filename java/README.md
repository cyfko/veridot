# 📦 dverify

A Java implementation for signing and verifying data encoded in either JWT or unique ID, secured with ECDSA keys distributed via brokers.

---

## ✨ Features

- 🔐 **JWT Signing & Verification** using ES256 (ECDSA)
- 🔁 **Automatic Key Rotation**
- 📬 **Public Key Distribution** via Kafka or cluster databases 
- 🧠 **Fast and Persistent Storage** using **[RocksDB](https://rocksdb.org/)** for ultra-fast verification *(Kafka based implementation)*
- ⚙️ **Environment-Based Configuration** with defaults.

---

## 📦 Installation

To install DVerify, follow these steps:

### 1. Add the Dependency

For **Maven**:

```xml

<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>dverify</artifactId>
    <version>4.0.0</version>
</dependency>
```

For **Gradle**:
```gradle
implementation 'io.github.cyfko:dverify:4.0.0'
```

> ⚠️ **Important Note**  
> Prior to API version `4`, major updates introduced breaking changes.  
> It is strongly recommended to use version `>= 4.0.0` to ensure compatibility and stability.

### 2. Environment Variables (Optional)

The application relies on the following environment variables for configuration:

| Variable Name                     | Description                             | Default Value                                    |
|-----------------------------------|-----------------------------------------|--------------------------------------------------|
| `DVER_KAFKA_BOOSTRAP_SERVERS`     | Kafka bootstrap servers                 | `localhost:9092`                                 |
| `DVER_TOKEN_VERIFIER_TOPIC`       | Kafka topic for token verification      | `token-verifier`                                 |
| `DVER_EMBEDDED_DATABASE_PATH`     | Path for RocksDB storage                | `dverify_db_data` (relative to _temp_ directory) |
| `DVER_KEYS_ROTATION_MINUTES`      | Interval (in minutes) for key rotation  | `1440` (24h)                                     |

> NOTE: The `KafkaBrokerAdapter` implementation of Broker uses **[RocksDB](https://rocksdb.org/)** as the embedded database for local storage.

## 🚀 Usage

### 🔧 Using `GenericSignerVerifier` with a Kafka-based `Broker`

- #### Data signature in the form of transparent token (jwt)

    ```java
    import io.github.cyfko.veridot.core.TokenMode;
    import impl.io.github.cyfko.veridok.core.GenericSignerVerifier;
    import kafka.impl.io.github.cyfko.veridok.core.KafkaBrokerAdapter;
    import java.util.Properties;
    
    Properties properties = new Properties();
    properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    
    Signer signer = new GenericSignerVerifier(KafkaBrokerAdapter.of(properties));
    String jwt = signer.sign(new UserData("john.doe@example.com"), Duration.ofHours(2), TokenMode.jwt);
    System.out.println("Generated Token: " + jwt); // output >> Generated Token: <JWT>
    ```

  #### Verifying the token and extracting the data
    ```java
    import io.github.cyfko.veridot.core.TokenMode;Verifier verifier = new GenericSignerVerifier(KafkaBrokerAdapter()); // KafkaBrokerAdapter constructed with default properties
    UserData userData = verifier.verify(jwt, UserData.class, TokenMode.jwt);
    System.out.println("Verified Data: " + userData.getEmail());  // output >> Verified Data: john.doe@example.com
    ```
- #### Data signature in the form of an opaque token (uuid)

    ```java
    import io.github.cyfko.veridot.core.TokenMode;
    import java.util.Properties;
    
    Properties properties = new Properties();
    properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
    
    Signer signer = new GenericSignerVerifier(KafkaBrokerAdapter.of(properties));
    String token = signer.sign(new UserData("john.doe@example.com"), Duration.ofHours(2), TokenMode.id);
    System.out.println("Generated Token: " + token); // output >> Generated Token: <UUID>
    ```

  #### Verifying the token and extracting the data
    ```java
    Verifier verifier = new GenericSignerVerifier(KafkaBrokerAdapter());
    UserData userData = verifier.verify(token, UserData.class);
    System.out.println("Verified Data: " + userData.getEmail());  // output >> Verified Data: john.doe@example.com
    ```

### 🔧 Using `GenericSignerVerifier` with a Relational database-based `Broker`

`DVerify` also provides a database-backed broker implementation: `DatabaseBroker`. This allows your application to persist and retrieve messages (such as signed verification tokens) through a SQL database in a consistent, scalable manner.

This is ideal for:

- Centralized message storage in **monolithic apps**.
- Scalable and redundant access in **microservices** and **database clusters**.

#### ✨ Features
- ✅ Pluggable table name (safe from SQL injection)
- ✅ Works with major relational databases (PostgreSQL, MySQL, SQL Server, H2, etc.)
- ✅ Async operations using CompletableFuture
- ✅ Auto-create message table on startup (optional)
- ✅ Safe and compliant with SQL standard types (BIGINT, VARCHAR, TEXT, TIMESTAMP)

#### 📦 Table Structure

```sql
CREATE TABLE broker_messages (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  message_key VARCHAR(255) NOT NULL UNIQUE,
  message_value TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

#### 🧠 Usage

```java
import io.github.cyfko.veridot.core.TokenMode;

import javax.sql.DataSource;

import db.impl.io.github.cyfko.veridok.core.DatabaseBroker;
import io.github.cyfko.dverify.GenericSignerVerifier;

DataSource dataSource = // obtain via HikariCP, Spring, etc.
        String
tableName ="broker_messages";

DatabaseBroker broker = new DatabaseBroker(dataSource, tableName);
GenericSignerVerifier signerVerifier = new GenericSignerVerifier(broker);

// Use as usual
String jwt = signerVerifier.sign("service #1", Duration.ofHours(15), TokenMode.jwt);
String serviceName1 = signerVerifier.verify(jwt, String.class); // Expected: serviceName1.equals("service #1")

String uuid = signerVerifier.sign("service #2", Duration.ofHours(15), TokenMode.id);
String serviceName2 = signerVerifier.verify(uuid, String.class); // Expected: serviceName2.equals("service #2")
```
#### ⚠️ Security & Best Practices
- The tableName is validated to prevent SQL injection. Only alphanumeric and underscores are allowed.
- `DatabaseBroker` automatically ensures the broker table exists on first usage. It inspects the database metadata and creates the table if it is missing — no extra setup required.
  
#### 🏗️ Cluster/Distributed Considerations
In sharded database or clustered setups:

- All nodes/services should point to the same logical message store (or synchronized replicas)
- `message_key` column with `UNIQUE` constraint ensures uniqueness without needing global coordination
- If you use DB-level replication, `DatabaseBroker` naturally benefits from it (high availability)

#### ✅ Tested with:
- PostgreSQL 13+
- MySQL 8+
- SQL Server 2019+
- MariaDB 11+
- H2 (dev mode)

---

## 📌 Requirements

- Java >= 21

---

## 🔐 Security Considerations

- Uses ES256 (ECDSA with P-256 curve)
- All public keys are stored and verified from **[RocksDB](https://rocksdb.org/)**
- Only valid keys within the expiration window are accepted
