---
layout: page
title: Getting Started
permalink: /docs/getting-started/
nav_order: 1
---

# Getting Started with Veridot

Veridot est une bibliothèque Java pour la vérification de tokens distribuée en architecture microservices. Ce guide vous emmène de l'installation à votre premier token signé, vérifié et révoqué.

## Prérequis

| Composant | Version | Rôle |
|-----------|---------|------|
| **Java** | **25+** | Requis pour `veridot-core` (sealed interfaces) |
| **Maven ou Gradle** | Any | Build |
| **Kafka** | 3.x+ | Broker recommandé pour la distribution des métadonnées |
| **RocksDB** | inclus | Cache local (fourni par `veridot-kafka`) |

> **Kafka optionnel** : pour les tests locaux ou les déploiements mono-nœud, vous pouvez utiliser `veridot-databases` (PostgreSQL, MySQL…) ou implémenter votre propre `MetadataBroker`.

---

## 1. Installation

### Maven

```xml
<properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
</properties>

<dependencies>
    <!-- Core API — toujours requis -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-core</artifactId>
        <version>3.0.2</version>
    </dependency>

    <!-- Option A : broker Kafka + RocksDB (recommandé pour la production) -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-kafka</artifactId>
        <version>3.0.2</version>
    </dependency>

    <!-- Option B : broker base de données SQL -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-databases</artifactId>
        <version>3.0.2</version>
    </dependency>
</dependencies>
```

### Gradle

```gradle
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    implementation 'io.github.cyfko:veridot-core:3.0.2'
    implementation 'io.github.cyfko:veridot-kafka:3.0.2' // ou veridot-databases
}
```

---

## 2. Concepts clés en 60 secondes

```
Signataire                          Vérificateur
    │                                    │
    │ 1. Génère clé éphémère RSA-3072    │
    │ 2. Signe l'annonce (long-term key) │
    │ 3. Publie sur broker ──────────────┼──────► broker
    │ 4. Signe le payload → JWT          │               │
    │                                    │ 5. Lit depuis broker
    │                                    │ 6. Valide annonce via TrustAnchor ← SÉCURITÉ v3.0
    │                                    │ 7. Vérifie JWT avec clé éphémère
    │                                    │ → payload extrait
    │
    │ revoke() → tombstone signé ────────┼──────► broker
                                         │ → session invalidée partout
```

Trois concepts essentiels :
- **`TrustAnchor`** : valide que l'annonce de clé provient bien d'un signataire légitime.
- **`GenericSignerVerifier`** : implémente `DataSigner`, `TokenVerifier`, `TokenRevoker`, `TokenTracker`.
- **`MetadataBroker`** : transporte les annonces de clés entre nœuds.

---

## 3. Démarrage rapide

### Étape 1 — Préparer les clés long-terme

Chaque service signataire a besoin d'une paire de clés RSA long-terme. En développement :

```bash
# Générer une paire de clés pour votre service
openssl genrsa -out private.pem 3072
openssl rsa -in private.pem -pubout -out public.pem
```

En production, stockez ces clés dans Vault, un Kubernetes Secret, ou votre KMS.

### Étape 2 — Configurer le broker

```java
import io.github.cyfko.veridot.kafka.KafkaMetadataBrokerAdapter;

Properties kafkaProps = new Properties();
kafkaProps.setProperty("bootstrap.servers", "localhost:9092");
kafkaProps.setProperty("embedded.db.path", "./veridot-data");

MetadataBroker broker = KafkaMetadataBrokerAdapter.of(kafkaProps);
```

### Étape 3 — Configurer le TrustAnchor

```java
import io.github.cyfko.veridot.core.TrustAnchor;

// Option simple : clé publique dans un fichier PEM
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
    byte[] pem = Files.readAllBytes(Paths.get("./trust/" + signerId + ".pub.pem"));
    KeyFactory kf = KeyFactory.getInstance("RSA");
    byte[] der = Base64.getDecoder().decode(
        new String(pem).replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
    return kf.generatePublic(new X509EncodedKeySpec(der));
};
```

### Étape 4 — Créer le signer/verifier

```java
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;

// Charger la clé privée long-terme
byte[] pkcs8 = Files.readAllBytes(Paths.get("./private.pem")); // PKCS#8
// ... parsing ...
PrivateKey longTermKey = KeyFactory.getInstance("RSA")
    .generatePrivate(new PKCS8EncodedKeySpec(pkcs8));

// Instancier
var sv = new GenericSignerVerifier(broker, anchor, "my-auth-service", longTermKey);
```

### Étape 5 — Signer, vérifier, révoquer

```java
// ── SIGNER ────────────────────────────────────────────────────────
String token = sv.sign("john@example.com",
    BasicConfigurer.builder()
        .groupId("user-42")         // identifiant du groupe (ex : userId)
        .sequenceId("device-iphone") // identifiant de la session (optionnel)
        .validity(3600)              // TTL en secondes
        .distribution(DistributionMode.DIRECT) // JWT retourné directement
        .build());

// ── VÉRIFIER (depuis n'importe quel service partageant le même broker) ──
try {
    VerifiedData<String> result = sv.verify(token, s -> s);

    String email   = result.data();       // "john@example.com"
    String group   = result.groupId();    // "user-42"
    String session = result.sequenceId(); // "device-iphone"

} catch (BrokerExtractionException e) {
    // Token invalide, révoqué, expiré, ou TrustAnchor a rejeté l'annonce
    response.sendError(401, "Token invalide");
}

// ── RÉVOQUER ─────────────────────────────────────────────────────
sv.revoke("user-42", "device-iphone"); // révoquer cette session uniquement
sv.revoke("user-42", null);            // révoquer toutes les sessions de user-42
```

---

## 4. Mode INDIRECT (gros payloads)

```java
// INDIRECT : le JWT est stocké dans le broker ; l'appelant reçoit un messageId
String messageId = sv.sign(largeObject,
    BasicConfigurer.builder()
        .groupId("docs")
        .sequenceId("doc-789")
        .validity(86400)
        .distribution(DistributionMode.INDIRECT)
        .build());
// messageId = "2:docs:doc-789"

// Le vérificateur passe le messageId — la logique est identique
VerifiedData<LargeObject> result = sv.verify(messageId,
    json -> new ObjectMapper().readValue(json, LargeObject.class));
```

---

## 5. Gestion des sessions

```java
// Max 3 sessions actives par utilisateur — évincer la plus ancienne (FIFO)
var sv = new GenericSignerVerifier(
    broker, anchor, "my-service", longTermKey,
    3, GenericSignerVerifier.EvictionPolicy.FIFO);

// Max 1 session — rejeter toute tentative supplémentaire
var sv = new GenericSignerVerifier(
    broker, anchor, "my-service", longTermKey,
    1, GenericSignerVerifier.EvictionPolicy.REJECT);

try {
    sv.sign(data, config); // throws SessionCapacityExceededException si dépassement
} catch (SessionCapacityExceededException e) {
    // Inviter l'utilisateur à se déconnecter d'un autre appareil
}
```

---

## 6. Vérifier si un token est actif

```java
// Par groupId
boolean hasActive = sv.hasActiveToken("user-42");

// Par JWT ou messageId
boolean isValid = sv.hasActiveToken(token);
boolean isValid2 = sv.hasActiveToken("2:user-42:device-iphone");
```

---

## Étapes suivantes

- **[Java Guide complet]({{ '/docs/java-guide' | relative_url }})** — TrustAnchor avancé, Spring Boot, migration depuis v3.0.1
- **[Modèle de sécurité]({{ '/docs/security' | relative_url }})** — Modèle de menaces, durcissement production, checklist
- **[Référence API]({{ '/docs/api-reference' | relative_url }})** — Toutes les interfaces, exceptions, variables d'environnement
- **[ADR-001 TrustAnchor]({{ '/docs/adr/001-trust-anchor' | relative_url }})** — Pourquoi et comment le TrustAnchor a été conçu
- **[Protocol V3]({{ '/PROTOCOL_V3' | relative_url }})** — Spécification complète du format de message