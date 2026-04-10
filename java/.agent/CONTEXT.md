# VERIDOT — Contexte Architectural

> **But de ce document** : Référence stable et exhaustive de l'architecture Veridot. Ne contient que ce qui existe déjà dans le code.
> Rédigé le : 2026-04-10 | Versions analysées : `veridot-core:2.0.2`, `veridot-kafka:2.0.1`, `veridot-databases:2.0.2`

---

## 1. Vision & Objectif Général

Veridot est une **bibliothèque MIT** destinée à simplifier la **signature et la vérification de tokens dans les systèmes distribués** (microservices, architectures événementielles, etc.).

La philosophie centrale repose sur :
- **Clés asymétriques éphémères (RSA)** : pas de secret partagé entre services.
- **Un broker de métadonnées** : toute signature publie sa clé publique associée via un canal découplé (Kafka, DB…), de sorte que n'importe quel service peut vérifier n'importe quel token.
- **L'extensibilité** : les contrats sont définis dans `veridot-core` ; les implémentations vivent dans des modules séparés branchables.

---

## 2. Structure du Projet (Multi-Module Maven)

```
veridot/                         ← POM parent (packaging=pom, Java 21)
├── veridot-core/                ← Contrats + implémentation générique (Java 17)
├── veridot-kafka/               ← Implémentation MetadataBroker via Kafka + RocksDB
├── veridot-databases/           ← Implémentation MetadataBroker via JDBC (SQL)
└── veridot-tests/               ← Tests d'intégration (Testcontainers)
```

---

## 3. Module `veridot-core`

### 3.1 Interfaces publiques (contrats)

#### `DataSigner` (`@FunctionalInterface`)
```
String sign(Object data, Configurer configurer)
    throws DataSerializationException, BrokerTransportException
```
- Prend un objet `data` (payload) et un `Configurer` qui précise le mode, la durée, le tracker, et le sérialiseur.
- Retourne un token (JWT complet ou identifiant court selon le `TokenMode`).

**`DataSigner.Configurer`** (inner interface) :

| Méthode | Rôle |
|---|---|
| `getMode()` → `TokenMode` | Mode de tokenisation (`jwt` ou `id`) |
| `getTracker()` → `long` | Identifiant métier de traçabilité |
| `getDuration()` → `long` | Durée de vie en secondes |
| `getSerializer()` → `Function<Object,String>` | Sérialiseur du payload |

---

#### `TokenVerifier` (`@FunctionalInterface`)
```
<T> T verify(String token, Function<String,T> deserializer)
    throws BrokerExtractionException, DataDeserializationException
```
- Vérifie la signature cryptographique + l'expiration du token.
- Désérialise et retourne le payload typé.

---

#### `TokenRevoker`
```
void revoke(Object target)
```
- `target` peut être : un `Long` (tracker ID) ou un `String` (le token lui-même).
- Mécanisme : publie `""` dans le broker pour le `keyId` correspondant → toute vérification ultérieure lèvera `BrokerExtractionException`.

---

#### `MetadataBroker`
```
CompletableFuture<Void> send(String key, String message)  throws BrokerTransportException
String get(String key)  throws BrokerExtractionException
```
- Abstraction découplée du transport.
- `key` = `keyId` (empreinte dérivée du `tracker`).
- `message` = métadonnée encodée (voir §3.2).

---

#### `DataTransformer`
```
String serialize(Object data)  throws DataSerializationException
Object deserialize(String data)  throws DataDeserializationException
```
Interface de sérialisation générique (peu utilisée directement).

---

#### `TokenMode` (enum)

| Valeur | Comportement |
|---|---|
| `jwt` | Le token **est** le JWT complet signé. |
| `id` | Le token **est** un identifiant court (22 chars Base64url). Le JWT réel est stocké dans le broker. |

---

### 3.2 Implémentation — `GenericSignerVerifier`

Classe centrale qui implémente simultanément **`DataSigner`**, **`TokenVerifier`** et **`TokenRevoker`**.

**Dépendances internes :**
- `MetadataBroker` (injectable via constructeur).
- `JwtMaker` / `JwtVerifier` : utilitaires internes (package-private) pour construction/parsing JWT RS256 (sans bibliothèque externe).
- `Config` : constantes (algo RSA, durée de rotation des clés via env `VDOT_KEYS_ROTATION_MINUTES`, défaut 1440 min = 24h).

#### Dérivation du `keyId`
```java
keyId = SHA-256(salt + "-" + tracker)[0..22]   // Base64url sans padding, 22 chars
```
**Le `keyId` est déterministe** : un même `tracker` + un même `salt` donnent toujours le même `keyId`. C'est le lien entre un identifiant métier et la clé du broker.

#### Flux de signature (`sign`)
```
1. Calculer keyId = SHA-256(salt + "-" + tracker)[0..22]
2. Construire JWT :
     header  : { alg: RS256, typ: JWT }
     payload : { sub: keyId, data: serializedPayload, iat, exp }
   Signer avec la clé RSA privée éphémère courante.
3. Construire la métadonnée broker :
     mode=jwt  →  "jwt:<pubKeyBase64>:<expiryMillis>:"
     mode=id   →  "id:<pubKeyBase64>:<expiryMillis>:<signedJWT>"
4. broker.send(keyId, metadata).get(3, MINUTES)
5. Retourner :
     mode=jwt → le JWT complet
     mode=id  → le keyId (22 chars)
```

#### Format de la métadonnée broker
```
<mode>:<pubKeyBase64>:<expiryMillis>:[<jwtToken si mode=id>]
 [0]        [1]            [2]               [3]
```

#### Flux de vérification (`verify`)
```
1. Extraire keyId :
     token contient "." → JWT → lire claim "sub"
     token sans "."     → mode id → token IS le keyId
2. broker.get(keyId) → métadonnée
3. Parser métadonnée → [mode, pubKeyBase64, expiryMillis, ...]
4. Reconstruire PublicKey (X509EncodedKeySpec)
5. JwtVerifier.verifyWith(publicKey).parseSignedClaims(jwt)
     → vérifie signature RS256 + expiration (claim "exp")
6. Extraire claim "data" → invoquer deserializer → retourner payload
```

#### Flux de révocation (`revoke`)
```
By String (token)  → extraire keyId du claim "sub" → broker.send(keyId, "")
By Long  (tracker) → recalculer keyId              → broker.send(keyId, "")
```

#### Rotation des clés RSA
- Générée immédiatement à la construction.
- Tournée via `ScheduledExecutorService` toutes `Config.KEYS_ROTATION_MINUTES` (défaut 24h).
- Seule la clé privée **courante** signe ; les clés publiques passées restent disponibles dans le broker jusqu'à leur expiration.

---

### 3.3 `BasicConfigurer` (Builder Pattern)

```java
BasicConfigurer configurer = BasicConfigurer.builder()
    .useMode(TokenMode.jwt)        // optionnel, défaut = jwt
    .trackedBy(myTrackerId)        // OBLIGATOIRE : long
    .validity(3600)                // OBLIGATOIRE : durée en secondes
    .serializedBy(mySerializer)    // optionnel, défaut = Jackson ObjectMapper
    .build();
```

Factory method statique `deserializer(Class<T>)` : créé le deserializer Jackson correspondant.

---

### 3.4 Exceptions (package `core.exceptions`)

| Exception | Signification |
|---|---|
| `BrokerExtractionException` | Clé introuvable, token révoqué, ou erreur broker |
| `BrokerTransportException` | Échec de l'envoi vers le broker |
| `DataSerializationException` | Échec de la sérialisation du payload |
| `DataDeserializationException` | Échec de la désérialisation du payload |

Toutes sont des `RuntimeException`.

---

## 4. Module `veridot-kafka`

### `KafkaMetadataBrokerAdapter`

Implémente `MetadataBroker` via Kafka + cache local RocksDB.

| Composant | Rôle |
|---|---|
| `KafkaProducer` | Publie les métadonnées sur le topic Kafka |
| `KafkaConsumer` | Consomme le topic dès `earliest`; offset stable par instance |
| `RocksDB (embedded)` | Cache local de lookup par clé (Kafka ne permet pas de lecture directe par clé) |

#### Tâche planifiée (chaque seconde)
1. `removeOldEmbeddedDatabaseEntries()` : supprime les entrées RocksDB dont `expiryMillis < now`.
2. `saveKafkaMessagesOnEmbeddedDatabase()` : poll Kafka (max 10s) → écrit ou supprime dans RocksDB.

> Message Kafka vide (`""`) → suppression RocksDB → révocation propagée localement.

#### Clé de consumer group
Persistée dans RocksDB sous `"veridot_db"` (UUID généré une seule fois).

#### Configuration

| Constante / Propriété | Variable d'env | Défaut |
|---|---|---|
| `KAFKA_BOOSTRAP_SERVERS` | `VDOT_KAFKA_BOOSTRAP_SERVERS` | `localhost:9092` |
| `KAFKA_TOKEN_VERIFIER_TOPIC` | `VDOT_TOKEN_VERIFIER_TOPIC` | `token-verifier` |
| `EMBEDDED_DATABASE_PATH` | `VDOT_EMBEDDED_DATABASE_PATH` | `veridot_db_data` |
| `SignerConfig.BROKER_TOPIC_CONFIG` | prop. Java | `veridot.broker.topic` |
| `VerifierConfig.EMBEDDED_DB_PATH_CONFIG` | prop. Java | `veridot.embedded.db` |

#### Construction
```java
Properties props = new Properties();
props.put(BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
props.put(VerifierConfig.EMBEDDED_DB_PATH_CONFIG, "/tmp/rocks");
KafkaMetadataBrokerAdapter broker = KafkaMetadataBrokerAdapter.of(props);
```

---

## 5. Module `veridot-databases`

### `DatabaseMetadataBroker`

Implémente `MetadataBroker` via JDBC (`javax.sql.DataSource`).

**Table gérée automatiquement à la construction :**
```sql
CREATE TABLE IF NOT EXISTS <tableName> (
    id            BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    message_key   VARCHAR(255) NOT NULL UNIQUE,
    message_value TEXT NOT NULL,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```
Compatible : PostgreSQL, MySQL, MariaDB, SQL Server, H2.

#### `send(keyId, message)`
- `message` vide → `DELETE` (révocation).
- `message` non vide → `DELETE` + `INSERT` (upsert manuel, contrainte `UNIQUE`).
- Exécution asynchrone via `CompletableFuture.runAsync()`.

#### `get(keyId)`
- `SELECT message_value … WHERE message_key = ?`
- Lève `BrokerExtractionException` si absent.
- **Aucune purge automatique** des entrées expirées côté DB ; l'expiration est détectée uniquement à la vérification JWT (claim `exp`).

#### Validation SQL
Regex : `^[a-zA-Z][a-zA-Z0-9_]*$` pour le nom de table (prévention injection SQL).

---

## 6. Module `veridot-tests`

Framework : **JUnit 5 + Testcontainers** (Docker requis).

### `DatabaseTest` (classe abstraite)

Tests paramétrés sur les deux `TokenMode` :

| Test | Description |
|---|---|
| `sign_method_with_valid_data_should_returns_token` | `sign()` retourne un token non-null |
| `sign_method_with_invalid_data_should_throws_exception` | `data=null` → `IllegalArgumentException` |
| `sign_method_with_expired_duration_should_throws_exception` | `validity=-5` → `IllegalArgumentException` |
| `verify_valid_token_should_returns_payload` | sign → verify (String) |
| `verify_valid_token_should_returns_payload_pojo` | sign → verify (POJO) |
| `verify_invalid_token_should_throws_exception` | token bidon → `BrokerExtractionException` |
| `verify_expired_token_should_throws_exception` | validity=1s, attente 3s → `BrokerExtractionException` |
| `verify_revoked_token_should_throws_exception` | `revoke(token)` → échec |
| `verify_revoked_token_by_tracked_id_should_throws_exception` | `revoke(trackerId)` → échec |
| `verify_token_regeneration_should_returns_payload_pojo` | signe 2× → dernier token valide |

Sous-classes concrètes : `PostgresTest`, `MySQLTest`, `MariaDBTest`, `MSSQLServerTest`.

### Tests Kafka

- `KafkaUnitTest` : tests basiques sign/verify.
- `KafkaSignerVerifierTest` : suite complète (sign, verify, revoke par token et par tracker).

---

## 7. Diagramme d'Interaction (Flux Global)

```
Service A (Signer)                    Broker                    Service B (Verifier)
     |                                   |                             |
     |-- sign(data, configurer) -------->|                             |
     |   [1] keyId = hash(tracker, salt)|                             |
     |   [2] JWT signé RSA              |                             |
     |   [3] broker.send(keyId, meta) ->|                             |
     |   [4] retourne token             |                             |
     |                                  |                             |
     |                            (propagation asynchrone)           |
     |                                  |                             |
     |                                  |<-- verify(token) -----------|
     |                                  |    [1] extraire keyId       |
     |                                  |    [2] broker.get(keyId)    |
     |                                  |    [3] reconstruire pubKey  |
     |                                  |    [4] vérifier JWT         |
     |                                  |    [5] retourner payload    |
```

---

## 8. Récapitulatif des Classes Clés

```
io.github.cyfko.veridot.core
├── DataSigner                [interface @FunctionalInterface]
│   └── Configurer            [inner interface]
├── TokenVerifier             [interface @FunctionalInterface]
├── TokenRevoker              [interface]
├── MetadataBroker            [interface]
├── DataTransformer           [interface]
├── TokenMode                 [enum: jwt | id]
├── exceptions/
│   ├── BrokerExtractionException
│   ├── BrokerTransportException
│   ├── DataSerializationException
│   └── DataDeserializationException
└── impl/
    ├── GenericSignerVerifier [DataSigner + TokenVerifier + TokenRevoker]
    ├── BasicConfigurer       [DataSigner.Configurer, Builder pattern]
    ├── Config                [constantes + env vars]
    ├── JwtMaker              [package-private, construit JWT RS256]
    └── JwtVerifier           [package-private, vérifie JWT RS256]

io.github.cyfko.veridot.kafka
├── KafkaMetadataBrokerAdapter [MetadataBroker — Kafka + RocksDB]
├── Constant / ConstantDefault / Env   [configuration]
├── SignerConfig               [prop: veridot.broker.topic]
├── VerifierConfig             [props: veridot.embedded.db, veridot.broker.topic]
└── PropertiesUtil             [utilitaire, package-private]

io.github.cyfko.veridot.databases
└── DatabaseMetadataBroker    [MetadataBroker — JDBC multi-DB]

io.github.cyfko.veridot.tests
├── DatabaseTest              [classe abstraite, Testcontainers, JUnit 5]
│   ├── PostgresTest
│   ├── MySQLTest
│   ├── MariaDBTest
│   └── MSSQLServerTest
├── KafkaUnitTest
├── KafkaSignerVerifierTest
└── UserData
```
