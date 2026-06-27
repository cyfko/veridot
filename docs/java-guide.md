---
layout: page
title: Java Implementation Guide
permalink: /docs/java-guide/
nav_order: 2
---

# Java Implementation Guide

Ce guide couvre tout ce dont vous avez besoin pour intégrer Veridot dans vos applications Java, de l'installation au déploiement en production avec le modèle de confiance v3.0.

## Table des matières

- [Prérequis](#prérequis)
- [Installation](#installation)
- [Concepts clés](#concepts-clés)
- [Démarrage rapide](#démarrage-rapide)
- [Configuration du TrustAnchor](#configuration-du-trustanchor)
- [Signature de tokens](#signature-de-tokens)
- [Vérification de tokens](#vérification-de-tokens)
- [Révocation de sessions](#révocation-de-sessions)
- [Gestion de la capacité de sessions](#gestion-de-la-capacité-de-sessions)
- [Intégration Spring Boot](#intégration-spring-boot)
- [Migration depuis v3.0.1](#migration-depuis-v301)

---

## Prérequis

| Composant | Version minimale |
|-----------|-----------------|
| **Java** | **25** (pour `veridot-core`) |
| Maven / Gradle | Toute version récente |
| Kafka (optionnel) | 3.x+ (pour `veridot-kafka`) |

> **Note** : `veridot-kafka` et `veridot-databases` restent compatibles Java 17. Seul `veridot-core` requiert Java 25 (pour les `sealed interface` et le pattern matching).

---

## Installation

### Maven

```xml
<dependencies>
    <!-- Core API — Java 25 requis -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-core</artifactId>
        <version>3.0.2</version>
    </dependency>

    <!-- Implémentation broker Kafka + RocksDB -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-kafka</artifactId>
        <version>3.0.2</version>
    </dependency>

    <!-- OU : Implémentation broker base de données SQL -->
    <dependency>
        <groupId>io.github.cyfko</groupId>
        <artifactId>veridot-databases</artifactId>
        <version>3.0.2</version>
    </dependency>
</dependencies>
```

Assurez-vous que votre `pom.xml` cible Java 25 :

```xml
<properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
</properties>
```

### Gradle

```gradle
dependencies {
    implementation 'io.github.cyfko:veridot-core:3.0.2'
    implementation 'io.github.cyfko:veridot-kafka:3.0.2'
    // ou
    // implementation 'io.github.cyfko:veridot-databases:3.0.2'
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
```

---

## Concepts clés

### Interfaces principales

| Interface | Rôle |
|-----------|------|
| `DataSigner` | Crée des tokens cryptographiquement signés |
| `TokenVerifier` | Valide les tokens à l'aide des clés publiques distribuées |
| `TokenRevoker` | Invalide immédiatement des sessions sur tout le cluster |
| `TokenTracker` | Interroge le statut actif d'une session |
| `MetadataBroker` | Propage les métadonnées cryptographiques (Kafka, DB, etc.) |
| **`TrustAnchor`** | **v3.0** — valide les annonces de clés provenant du broker |

### Modes de distribution

| Mode | `sign()` retourne | Localisation du JWT | Idéal pour |
|------|------------------|---------------------|------------|
| `DIRECT` | Le JWT lui-même | Retourné à l'appelant | Payloads petits à moyens |
| `INDIRECT` | Un `messageId` (`2:groupe:seq`) | Stocké dans le broker | Gros payloads, confidentialité renforcée |

### Modèle de confiance v3.0

Le broker est un **transport uniquement**. Chaque annonce de clé reçue du broker est validée par un `TrustAnchor` avant que la clé éphémère soit utilisée. Cela empêche les attaquants ayant accès au broker d'injecter des clés frauduleuses.

---

## Démarrage rapide

```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.*;
import io.github.cyfko.veridot.kafka.KafkaMetadataBrokerAdapter;

// 1. Construire un TrustAnchor (résout les clés publiques long-terme des signataires annoncés)
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
    // Charger la clé publique long-terme pour ce signerId depuis votre trust store
    return loadPublicKeyFromVault(signerId); // votre implémentation
};

// 2. Charger la clé privée long-terme du service
PrivateKey longTermKey = loadPrivateKeyFromSecret("/etc/veridot/private.key");

// 3. Connecter au broker
MetadataBroker broker = KafkaMetadataBrokerAdapter.of(kafkaProperties());

// 4. Instancier le signer/vérificateur
var sv = new GenericSignerVerifier(broker, anchor, "mon-service-id", longTermKey);

// 5. Signer
String token = sv.sign("user@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .sequenceId("session-A")
        .validity(3600)   // 1 heure
        .distribution(DistributionMode.DIRECT)
        .build());

// 6. Vérifier (depuis n'importe quel service partageant le même broker + trust anchor)
VerifiedData<String> result = sv.verify(token, s -> s);
String email   = result.data();       // "user@example.com"
String group   = result.groupId();    // "user-123"
String session = result.sequenceId(); // "session-A"

// 7. Révoquer
sv.revoke("user-123", "session-A");  // révoquer une session spécifique
sv.revoke("user-123", null);         // révoquer toutes les sessions du groupe
```

---

## Configuration du TrustAnchor

Le `TrustAnchor` est la pierre angulaire de la sécurité v3.0. Choisissez l'implémentation adaptée à votre infrastructure.

### Option A : Clé publique statique (fichier PEM / Vault KV)

```java
// Chaque signerId correspond à un fichier PEM dans un répertoire en lecture seule
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
    Path keyPath = Paths.get("/etc/veridot/trust/" + signerId + ".pub.pem");
    byte[] pem = Files.readAllBytes(keyPath);
    return parsePemPublicKey(pem);
};
```

### Option B : Vault KV secrets

```java
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
    VaultResponse resp = vaultTemplate.read("secret/veridot/trust/" + signerId);
    String pem = (String) resp.getData().get("public_key");
    return parsePemPublicKey(pem.getBytes(StandardCharsets.UTF_8));
};
```

### Option C : Vault Transit Engine (KMS — la clé ne quitte pas Vault)

```java
TrustAnchor anchor = (TrustAnchor.DelegatedVerifier) (signerId, canonical, sig) -> {
    // Vault vérifie la signature RSA ; la clé privée ne quitte jamais Vault
    boolean valid = vaultTransit.verify(signerId, canonical, sig);
    if (!valid) throw new TrustResolutionException.SignatureRejected(
        "Vault a rejeté la signature pour " + signerId);
};
```

### Gestion des exceptions TrustAnchor

```java
try {
    var result = sv.verify(token, s -> s);

} catch (BrokerExtractionException e) {
    // Inspecter la cause pour distinguer les échecs TrustAnchor
    Throwable cause = e.getCause();
    if (cause instanceof TrustResolutionException.Unavailable) {
        // Infrastructure KMS indisponible — fail safe, alerter les ops
        alertOps("TrustAnchor indisponible : " + cause.getMessage());
    } else if (cause instanceof TrustResolutionException.SignatureRejected) {
        // Événement de sécurité — alerter l'équipe sécurité
        alertSecurity("Tentative d'injection de clé possible : " + cause.getMessage());
    }
    throw e; // toujours propager
}
```

---

## Signature de tokens

```java
// Mode DIRECT : JWT retourné à l'appelant
String jwt = sv.sign(userPayload,
    BasicConfigurer.builder()
        .groupId(userId)              // identifie le groupe "propriétaire"
        .sequenceId(deviceId)         // identifie cette session spécifique
        .validity(3600)               // secondes
        .distribution(DistributionMode.DIRECT)
        .build());

// Mode INDIRECT : messageId retourné, JWT stocké dans le broker
String messageId = sv.sign(largeDocument,
    BasicConfigurer.builder()
        .groupId("docs")
        .sequenceId(docId)
        .validity(86400)
        .distribution(DistributionMode.INDIRECT)
        .build());
```

### Sérialisation personnalisée

```java
ObjectMapper mapper = new ObjectMapper();

String token = sv.sign(myObject,
    BasicConfigurer.builder()
        .groupId("grp")
        .validity(3600)
        .serializedBy(obj -> mapper.writeValueAsString(obj))
        .build());

VerifiedData<MyClass> result = sv.verify(token,
    json -> mapper.readValue(json, MyClass.class));
```

---

## Vérification de tokens

```java
try {
    // Accepte indifféremment un JWT brut (DIRECT) ou un messageId (INDIRECT)
    VerifiedData<String> result = sv.verify(tokenOrMessageId, s -> s);

    String payload   = result.data();       // payload désérialisé
    String groupId   = result.groupId();    // ex. "user-123"
    String sessionId = result.sequenceId(); // ex. "session-A"

} catch (BrokerExtractionException e) {
    // Token invalide, révoqué, expiré, ou échec du trust anchor
    log.warn("Vérification échouée : {}", e.getMessage());
}
```

---

## Révocation de sessions

```java
// Révoquer une session spécifique
sv.revoke("user-123", "session-A");

// Révoquer toutes les sessions d'un utilisateur (droit à l'oubli)
sv.revoke("user-123", null);

// Vérifier si une session est active avant de révoquer
if (sv.hasActiveToken("user-123")) {
    sv.revoke("user-123", null);
}

// hasActiveToken accepte aussi un JWT ou un messageId
boolean isActive  = sv.hasActiveToken(jwt);
boolean isActive2 = sv.hasActiveToken(messageId);
```

Les tombstones de révocation sont **signés** avec la clé long-terme du service (v3.0). Un attaquant avec accès au broker ne peut pas forger un tombstone arbitraire ni rejouer un ancien tombstone.

---

## Gestion de la capacité de sessions

```java
// Refuser tout dépassement (1 session active max, REJECT)
var sv = new GenericSignerVerifier(
    broker, anchor, signerId, longTermKey,
    1, GenericSignerVerifier.EvictionPolicy.REJECT);

// Évincer la plus ancienne session si dépassement (FIFO, max 3)
var sv = new GenericSignerVerifier(
    broker, anchor, signerId, longTermKey,
    3, GenericSignerVerifier.EvictionPolicy.FIFO);
```

### Politiques d'éviction

| Politique | Comportement au dépassement |
|-----------|-----------------------------|
| `FIFO` | Évince la session la plus ancienne (timestamp minimum) |
| `LIFO` | Évince la session la plus récente (timestamp maximum) |
| `LRU` | Identique à FIFO dans l'implémentation actuelle |
| `REJECT` | Lance `SessionCapacityExceededException` — pas d'éviction |

```java
// Exemple REJECT avec gestion d'exception
try {
    String token = sv.sign(data, config);
} catch (SessionCapacityExceededException e) {
    log.warn("Limite de sessions atteinte pour {} : max={}", e.getGroupId(), e.getMaxSessions());
    // Inviter l'utilisateur à se déconnecter d'un autre appareil
}
```

---

## Intégration Spring Boot

```java
@Configuration
public class VeridotConfig {

    @Value("${veridot.signer-id}")
    private String signerId;

    @Value("${veridot.long-term-key-path}")
    private String keyPath;

    @Bean
    public PrivateKey longTermPrivateKey() throws Exception {
        byte[] pkcs8 = Files.readAllBytes(Paths.get(keyPath));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    @Bean
    public TrustAnchor trustAnchor(VaultTemplate vault) {
        return (TrustAnchor.PublicKeyResolver) id -> {
            String pem = (String) vault.read("secret/veridot/trust/" + id)
                                       .getData().get("public_key");
            return parsePemPublicKey(pem.getBytes(StandardCharsets.UTF_8));
        };
    }

    @Bean
    public MetadataBroker metadataBroker(
            @Value("${spring.kafka.bootstrap-servers}") String brokers) {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", brokers);
        props.setProperty("security.protocol", "SASL_SSL");
        props.setProperty("sasl.mechanism", "SCRAM-SHA-512");
        props.setProperty("sasl.jaas.config", buildJaasConfig());
        props.setProperty("ssl.endpoint.identification.algorithm", "https");
        return KafkaMetadataBrokerAdapter.of(props);
    }

    @Bean
    public GenericSignerVerifier veridot(MetadataBroker broker,
                                          TrustAnchor anchor,
                                          PrivateKey longTermPrivateKey) {
        return new GenericSignerVerifier(broker, anchor, signerId, longTermPrivateKey);
    }

    // Exposer les interfaces individuelles comme beans Spring
    @Bean public DataSigner   dataSigner(GenericSignerVerifier sv)   { return sv; }
    @Bean public TokenVerifier tokenVerifier(GenericSignerVerifier sv) { return sv; }
    @Bean public TokenRevoker  tokenRevoker(GenericSignerVerifier sv)  { return sv; }
    @Bean public TokenTracker  tokenTracker(GenericSignerVerifier sv)  { return sv; }
}
```

### application.yml

```yaml
veridot:
  signer-id: mon-service-auth
  long-term-key-path: /var/secrets/veridot/private.key

spring:
  kafka:
    bootstrap-servers: kafka1:9092,kafka2:9092
```

### Service d'exemple

```java
@Service
@Slf4j
public class TokenService {

    private final DataSigner    dataSigner;
    private final TokenVerifier tokenVerifier;
    private final TokenRevoker  tokenRevoker;

    public String createAccessToken(User user) {
        return dataSigner.sign(user.getId(),
            BasicConfigurer.builder()
                .groupId(user.getId())
                .sequenceId(UUID.randomUUID().toString())
                .validity(3600)
                .distribution(DistributionMode.DIRECT)
                .build());
    }

    public Optional<VerifiedData<String>> verifyToken(String token) {
        try {
            return Optional.of(tokenVerifier.verify(token, s -> s));
        } catch (BrokerExtractionException e) {
            log.warn("Vérification échouée : {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void logout(String userId, String sessionId) {
        tokenRevoker.revoke(userId, sessionId);
    }

    public void logoutAll(String userId) {
        tokenRevoker.revoke(userId, null);
    }
}
```

---

## Migration depuis v3.0.1

Le **seul breaking change** entre v3.0.1 et v3.0.2 est le constructeur de `GenericSignerVerifier`.

### Avant (v3.0.1)

```java
// Ancien constructeur — le salt n'avait aucune valeur cryptographique
var sv = new GenericSignerVerifier(broker, "mon-salt");
var sv = new GenericSignerVerifier(broker, "mon-salt", 3, EvictionPolicy.FIFO);
```

### Après (v3.0.2)

```java
// Nouveau constructeur — sécurité réelle via TrustAnchor
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> loadPublicKey(signerId);
PrivateKey longTermKey = loadPrivateKey("/etc/veridot/private.key");

var sv = new GenericSignerVerifier(broker, anchor, "mon-service-id", longTermKey);
var sv = new GenericSignerVerifier(broker, anchor, "mon-service-id", longTermKey, 3, EvictionPolicy.FIFO);
```

### Étapes de migration

**Étape 1** : Générer une paire de clés RSA long-terme (3072 bits) pour chaque service signataire :

```bash
openssl genrsa -out private.pem 3072
openssl rsa -in private.pem -pubout -out public.pem
```

**Étape 2** : Stocker la clé privée dans Vault, un Kubernetes Secret, ou un autre gestionnaire de secrets sécurisé. Ne jamais la committer dans le code source.

**Étape 3** : Distribuer la clé publique à tous les services vérificateurs. Un secret Vault KV en lecture seule ou un ConfigMap Kubernetes suffit — la clé publique n'est pas sensible.

**Étape 4** : Implémenter `TrustAnchor` (3 à 5 lignes selon le backend, voir [Configuration du TrustAnchor](#configuration-du-trustanchor)).

**Étape 5** : Mettre à jour le compilateur vers Java 25 dans `pom.xml` :

```xml
<properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
</properties>
```

**Étape 6** : Mettre à jour les tests unitaires. Remplacer `new GenericSignerVerifier(broker, "salt")` par un setup minimal en mémoire :

```java
// Setup de test minimal
KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
gen.initialize(3072); // RSA-3072 (recommandation NIST) — même taille qu'en production
KeyPair kp = gen.generateKeyPair();

TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) id -> kp.getPublic();
var sv = new GenericSignerVerifier(broker, anchor, "test-signer", kp.getPrivate());
```

> **Note** : le projet Veridot fournit `TestTrustSetup` dans le module de test pour éviter cette duplication.

---

## Étapes suivantes

- Lire le [Modèle de sécurité et bonnes pratiques]({{ '/docs/security' | relative_url }})
- Consulter [ADR-001 : TrustAnchor]({{ '/docs/adr/001-trust-anchor' | relative_url }})
- Explorer la [Référence API]({{ '/docs/api-reference' | relative_url }})
- Voir la [Spécification du Protocole V2]({{ '/protocol-v2' | relative_url }})