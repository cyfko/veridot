---
layout: page
title: API Reference
permalink: /docs/api-reference/
nav_order: 4
---

# API Reference — Java (v3.0.2)

Référence complète de toutes les interfaces, classes, exceptions, et variables d'environnement de Veridot.

---

## Interfaces principales

### `DataSigner`

Crée des tokens cryptographiquement signés.

```java
public interface DataSigner {
    String sign(Object data, Configurer configurer)
        throws DataSerializationException, BrokerTransportException;
}
```

| Paramètre | Type | Description |
|-----------|------|-------------|
| `data` | `Object` | Payload à signer (non null) |
| `configurer` | `Configurer` | Configuration de la signature |

**Retourne** : JWT (mode `DIRECT`) ou `messageId` de la forme `2:<groupId>:<sequenceId>` (mode `INDIRECT`).

**Exceptions** :
- `DataSerializationException` — le payload n'a pas pu être sérialisé
- `BrokerTransportException` — l'envoi des métadonnées au broker a échoué
- `SessionCapacityExceededException` — limite de sessions atteinte et politique `REJECT`

---

### `DataSigner.Configurer`

Interface de configuration pour les opérations de signature. Construire via `BasicConfigurer.builder()`.

```java
public interface Configurer {
    String getGroupId();
    String getSequenceId();                    // null → UUID auto-généré
    long getDuration();                        // TTL en secondes
    DistributionMode getDistributionMode();    // DIRECT ou INDIRECT
    Function<Object, String> getSerializer();  // sérialiseur personnalisé
}
```

---

### `TokenVerifier`

Valide les tokens et extrait les payloads.

```java
public interface TokenVerifier {
    <T> VerifiedData<T> verify(String token, Function<String, T> deserializer)
        throws BrokerExtractionException, DataDeserializationException;
}
```

| Paramètre | Type | Description |
|-----------|------|-------------|
| `token` | `String` | JWT (DIRECT) ou messageId (INDIRECT) |
| `deserializer` | `Function<String, T>` | Convertit le payload String en objet |

**Retourne** : `VerifiedData<T>` — payload + identifiants du protocole.

**Exceptions** :
- `BrokerExtractionException` — token invalide, révoqué, expiré, ou `TrustAnchor` a rejeté l'annonce
- `DataDeserializationException` — désérialisation du payload échouée

---

### `TokenRevoker`

Invalide des sessions instantanément sur tout le cluster.

```java
public interface TokenRevoker {
    void revoke(String groupId, String sequenceId)
        throws BrokerTransportException;
}
```

| `sequenceId` | Comportement |
|-------------|-------------|
| `"session-A"` | Révoque uniquement la session `groupId:session-A` |
| `null` | Révoque **toutes** les sessions actives du groupe |

Depuis v3.0.2 : les tombstones de révocation sont **signés** avec la clé long-terme du service.

---

### `TokenTracker`

Interroge le statut actif des sessions sans vérifier le payload.

```java
public interface TokenTracker {
    boolean hasActiveToken(Object query);
}
```

`query` peut être :
- Un `String` groupId → `true` si le groupe a au moins une session active
- Un JWT (DIRECT) → `true` si le token est encore valide
- Un messageId (`2:group:seq`) → `true` si la session est active

---

### `MetadataBroker`

Interface de transport des métadonnées cryptographiques. Implémentez-la pour un broker personnalisé.

```java
public interface MetadataBroker {
    CompletableFuture<Void> send(String key, String message);
    String get(String key) throws BrokerExtractionException;
    List<String> getKeysByPrefix(String prefix) throws BrokerExtractionException;

    // v3.0.2 — no-op par défaut, implémenté par KafkaMetadataBrokerAdapter
    default void sendLocal(String key, String message) { /* no-op */ }
}
```

---

### `TrustAnchor` *(v3.0.2)*

Valide les annonces de clés reçues du broker. Interface `sealed` — deux variantes permises.

```java
public sealed interface TrustAnchor
        permits TrustAnchor.PublicKeyResolver, TrustAnchor.DelegatedVerifier {

    /** Résolution locale : Veridot charge la clé et vérifie la signature en-process. */
    non-sealed interface PublicKeyResolver extends TrustAnchor {
        PublicKey resolve(String signerId) throws TrustResolutionException;
    }

    /** Délégation KMS : le système externe vérifie la signature. */
    non-sealed interface DelegatedVerifier extends TrustAnchor {
        void verify(String signerId, byte[] canonicalAnnouncement, byte[] signature)
                throws TrustResolutionException;
    }
}
```

**Exemples** :

```java
// PublicKeyResolver — fichier PEM
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
    byte[] pem = Files.readAllBytes(Paths.get("/trust/" + signerId + ".pub.pem"));
    return parsePemPublicKey(pem);
};

// DelegatedVerifier — Vault Transit
TrustAnchor anchor = (TrustAnchor.DelegatedVerifier) (signerId, canonical, sig) -> {
    if (!vaultTransit.verify(signerId, canonical, sig))
        throw new TrustResolutionException.SignatureRejected("Rejected: " + signerId);
};
```

---

## Classes d'implémentation

### `GenericSignerVerifier`

Implémentation par défaut combinant toutes les interfaces.

```java
public class GenericSignerVerifier
        implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker {

    // Sans limite de sessions
    public GenericSignerVerifier(
        MetadataBroker broker,
        TrustAnchor trustAnchor,
        String signerId,
        PrivateKey longTermPrivateKey)

    // Avec limite de sessions
    public GenericSignerVerifier(
        MetadataBroker broker,
        TrustAnchor trustAnchor,
        String signerId,
        PrivateKey longTermPrivateKey,
        int maxSessions,
        EvictionPolicy policy)
}
```

#### `EvictionPolicy`

| Valeur | Comportement quand `maxSessions` est atteint |
|--------|----------------------------------------------|
| `FIFO` | Évince la session la plus ancienne (timestamp minimum) |
| `LIFO` | Évince la session la plus récente (timestamp maximum) |
| `LRU` | Identique à FIFO dans l'implémentation actuelle |
| `REJECT` | Lance `SessionCapacityExceededException` — aucune éviction |

---

### `BasicConfigurer`

Builder pour `DataSigner.Configurer`.

```java
BasicConfigurer config = BasicConfigurer.builder()
    .groupId("user-123")                          // REQUIRED — groupe métier
    .sequenceId("session-A")                      // OPTIONAL — UUID si omis
    .validity(3600)                               // REQUIRED — TTL en secondes
    .distribution(DistributionMode.DIRECT)        // OPTIONAL — défaut DIRECT
    .serializedBy(obj -> mapper.writeValueAsString(obj)) // OPTIONAL
    .build();

// Helper de désérialisation
Function<String, MyClass> deser = BasicConfigurer.deserializer(MyClass.class);
```

---

### `VerifiedData<T>`

Record immuable retourné par `TokenVerifier.verify()`.

```java
public record VerifiedData<T>(T data, String groupId, String sequenceId) {}
```

```java
VerifiedData<String> result = sv.verify(token, s -> s);
String payload   = result.data();       // payload désérialisé
String groupId   = result.groupId();    // ex. "user-123"
String sessionId = result.sequenceId(); // ex. "session-A"
```

---

### `KafkaMetadataBrokerAdapter`

Implémentation Kafka + RocksDB de `MetadataBroker`.

```java
public class KafkaMetadataBrokerAdapter implements MetadataBroker {
    public static KafkaMetadataBrokerAdapter of(Properties props)
    public KafkaMetadataBrokerAdapter(String bootstrapServers)
}
```

#### Propriétés de configuration

| Propriété | Requis | Description |
|-----------|:------:|-------------|
| `bootstrap.servers` | ✅ | Adresses du cluster Kafka (`host1:9092,host2:9092`) |
| `embedded.db.path` | ✅ | Chemin local du store RocksDB |
| `security.protocol` | — | `PLAINTEXT` (défaut), `SASL_SSL`, `SSL`, `SASL_PLAINTEXT` |
| `sasl.mechanism` | — | `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512` |
| `sasl.jaas.config` | — | Configuration JAAS pour SASL |
| `ssl.truststore.location` | — | Chemin du truststore JKS |
| `ssl.truststore.password` | — | Mot de passe du truststore |
| `ssl.endpoint.identification.algorithm` | — | `https` recommandé en production |

#### Comportement v3.0.2

- **`sendLocal(key, message)`** : écrit directement dans RocksDB sans produire sur Kafka. Élimine la race read-after-write sur le même nœud.
- **Compaction TTL** : tâche périodique toutes les 5 min purge les entrées expirées (`timestamp + ttl + 300 < now`).
- **Priorité canal de contrôle** : les messages `:__REVOKE__` et `:__CONFIG__` sont traités en premier dans chaque cycle de poll.

---

## Exceptions

Toutes les exceptions Veridot étendent `VeridotException` (elle-même `RuntimeException`).

### Hiérarchie

```
VeridotException (abstract, RuntimeException)
├── DataSerializationException        — erreur de sérialisation du payload
├── DataDeserializationException      — erreur de désérialisation du payload
├── BrokerTransportException          — envoi au broker échoué
├── BrokerExtractionException         — récupération / vérification échouée
└── SessionCapacityExceededException  — limite de sessions atteinte (REJECT)
    ├── getGroupId() : String
    └── getMaxSessions() : int

TrustResolutionException (checked Exception — sealed)
├── TrustResolutionException.Unavailable      — infrastructure transitoirement indisponible
└── TrustResolutionException.SignatureRejected — rejet cryptographique définitif
```

> **Important** : `TrustResolutionException` est une `checked Exception` — le compilateur force son traitement. Elle est wrappée dans `BrokerExtractionException` quand elle remonte jusqu'à `verify()`.

### Gestion recommandée

```java
try {
    VerifiedData<MyClass> result = sv.verify(token,
        json -> mapper.readValue(json, MyClass.class));

} catch (BrokerExtractionException e) {
    // Token invalide, expiré, révoqué, ou TrustAnchor a rejeté
    log.warn("Verification failed: {}", e.getMessage());
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);

} catch (DataDeserializationException e) {
    // Payload corrompu — anomalie applicative
    log.error("Payload corruption: {}", e.getMessage());
    throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
}
```

```java
try {
    String token = sv.sign(data, config);

} catch (SessionCapacityExceededException e) {
    log.warn("Session limit for group={}, max={}", e.getGroupId(), e.getMaxSessions());
    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
        "Maximum concurrent sessions reached. Please log out from another device.");

} catch (DataSerializationException | BrokerTransportException e) {
    log.error("Sign failed: {}", e.getMessage());
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
}
```

---

## Variables d'environnement

| Variable | Description | Défaut |
|----------|-------------|--------|
| `VDOT_KEYS_ROTATION_MINUTES` | Intervalle de rotation des clés éphémères RSA-3072 | `1440` (24h) |

---

## Enum `DistributionMode`

```java
public enum DistributionMode {
    DIRECT,   // sign() retourne le JWT complet directement à l'appelant
    INDIRECT  // sign() retourne le messageId ; le JWT est stocké dans le broker
}
```

---

## Voir aussi

- [Java Guide]({{ '/docs/java-guide' | relative_url }}) — guide d'utilisation complet
- [Modèle de sécurité]({{ '/docs/security' | relative_url }}) — TrustAnchor, menaces, checklist
- [ADR-001 TrustAnchor]({{ '/docs/adr/001-trust-anchor' | relative_url }}) — décision architecturale
- [Protocol V3]({{ '/PROTOCOL_V3' | relative_url }}) — format des messages