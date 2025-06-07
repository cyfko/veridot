# A Kafka based implementation of the [core](https://github.com/cyfko/veridot/blob/main/java/veridot-core) API 

The current library especially provide a Kafka-aware implementation of the core [MetadataBroker](https://github.com/cyfko/veridot/blob/main/java/veridot-core/src/main/java/io/github/cyfko/veridot/core/MetadataBroker.java) Interface
defined as a transport layer of metadata exploited for data signing and token verifcation purposes in a distributed environment.

---

## ✨ Features

- 🔐 **JWT Signing & Verification** using RSA
- 🔁 **Automatic Key Rotation**
- 📬 **Public Key Distribution for decentralized verification** via Kafka
- 🧠 **Fast and Persistent Storage** using **[RocksDB](https://rocksdb.org/)** for ultra-fast verification
- ⚙️ **Environment-Based Configuration** with defaults.

---

## 📦 Installation

To install DVerify, follow these steps:

### 1. Add the Dependency

For **Maven**:

```xml

<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>2.0.0</version>
</dependency>
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>2.0.0</version>
</dependency>
```

For **Gradle**:
```gradle
implementation 'io.github.cyfko:veridot-core:2.0.0'
implementation 'io.github.cyfko:veridot-kafka:2.0.0'
```

> ⚠️ **Important Note**  
> The versioning of the current API follow the [semantic versioning](https://semver.org/) approach.

### 2. Environment Variables (Optional)

The application relies on the following environment variables for configuration:

| Variable Name                   | Description                             | Default Value                                    |
|---------------------------------|-----------------------------------------|--------------------------------------------------|
| `VDOT_KAFKA_BOOSTRAP_SERVERS`   | Kafka bootstrap servers                 | `localhost:9092`                                 |
| `VDOT_TOKEN_VERIFIER_TOPIC`     | Kafka topic for token verification      | `token-verifier`                                 |
| `VDOT_EMBEDDED_DATABASE_PATH`   | Path for RocksDB storage                | `veridot_db_data` (relative to _temp_ directory) |
| `VDOT_KEYS_ROTATION_MINUTES`    | Interval (in minutes) for key rotation  | `1440` (24h)                                     |

> NOTE: The `KafkaMetadataBrokerAdapter` implementation of Broker uses **[RocksDB](https://rocksdb.org/)** as the embedded database for local storage.

## 🚀 Usage

Data signature in the form of transparent token (jwt)

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import io.github.cyfko.veridot.core.impl.Config;
import io.github.cyfko.veridot.kafka.KafkaMetadataBrokerAdapter;

import java.util.Properties;

Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092" /* some kafka boostrap server */);
props.put(Config.KEYS_ROTATION_MINUTES, 5);

MetadataBroker metadataBroker = KafkaMetadataBrokerAdapter.of(props);

/* Create an instance of GenericSignerVerifier which implements DataSigner, TokenVerifier and TokenRevoker interfaces  */
GenericSignerVerifier genericSignerVerifier = new GenericSignerVerifier(metadataBroker);

DataSigner dataSigner = genericSignerVerifier;
TokenVerifier tokenVerifier = genericSignerVerifier;
TokenRevoker tokenRevoker = genericSignerVerifier;

/* Configure how to generate and track the token */
BasicConfigurer configurer = BasicConfigurer.builder()
        .useMode(TokenMode.jwt)
        .trackedBy(5)       // Tracker identity, used for revocation purposes.
        .validity(60 * 5)   // Valid for 5 minutes.
        .build();

/* Generate the token (sign the data of interest) */
String data = "john.doe@example.com";
String token = dataSigner.sign(data, configurer);                    // Generate the JWT token embedding the data.

/* Verify the token (extracting the data of interest) */
String verifiedData = tokenVerifier.verify(token, String::toString); // Verifying the JWT token and extracting the embedded data as a String.

assertNotNull(verifiedData);

assertEquals(data, verifiedData);

/* Revoke the token when necessary */
tokenRevoker.revoke(token); // Tokens can also be revoked by passing the tracker ID instead of the token itself.

assertThrows(BrokerExtractionException .class, () -> tokenVerifier.verify(token, String::toString));
```
---

## 📌 Requirements

- Java >= 17

---

## 🔐 Security Considerations

- Uses RSA 4096
- All public keys are stored and verified from **[RocksDB](https://rocksdb.org/)**
- Only valid keys within the expiration window are accepted
