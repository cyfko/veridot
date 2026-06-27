# VERIDOT — Contexte Architectural

> **But de ce document** : Référence stable et exhaustive de l'architecture Veridot. Ne contient que ce qui existe déjà dans le code.
> Rédigé le : 2026-04-10 | Mis à jour : 2026-06-27 | Versions analysées : `veridot-core:3.0.2`, `veridot-kafka:3.0.2`, `veridot-databases:3.0.2`

---

## 1. Vision & Objectif Général

Veridot est une **bibliothèque MIT** destinée à simplifier la **signature et la vérification de tokens dans les systèmes distribués** (microservices, architectures événementielles, etc.).

La philosophie centrale repose sur :
- **Clés asymétriques éphémères (RSA-3072)** : pas de secret partagé entre services.
- **Un broker de métadonnées** : toute signature publie sa clé publique associée via un canal découplé (Kafka, DB…), de sorte que n'importe quel service peut vérifier n'importe quel token.
- **TrustAnchor** : le broker n'est **pas** une racine de confiance. Chaque annonce de clé est certifiée (signée par une clé long terme) et validée côté vérificateur via un `TrustAnchor`.
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
- Prend un objet `data` (payload) et un `Configurer` qui précise le mode de distribution, la durée, le groupId, le sequenceId, et le sérialiseur.
- Retourne un token (JWT complet en mode `DIRECT` ou `messageId` en mode `INDIRECT`).

**`DataSigner.Configurer`** (inner interface) :

| Méthode | Rôle |
|---|---|
| `getDistribution()` → `DistributionMode` | Mode de distribution (`DIRECT` ou `INDIRECT`) |
| `getGroupId()` → `String` | Identifiant métier de regroupement (mappe au `groupId` du Protocol V2 `messageId`) |
| `getSequenceId()` → `String` | Identifiant de séquence (mappe au `sequenceId` du Protocol V2 `messageId`) |
| `getDuration()` → `long` | Durée de vie en secondes |
| `getSerializer()` → `Function<Object,String>` | Sérialiseur du payload |

---

#### `TokenVerifier` (`@FunctionalInterface`)
```
<T> T verify(String token, Function<String,T> deserializer)
    throws BrokerExtractionException, DataDeserializationException
```
- Vérifie la signature cryptographique + l'expiration du token.
- Valide l'annonce de clé via `TrustAnchor` avant de faire confiance à la clé publique éphémère.
- Désérialise et retourne le payload typé.

---

#### `TokenRevoker`
```
void revoke(Object target)
```
- `target` peut être : un `String` (groupId pour révocation de groupe, messageId pour révocation ciblée, ou token JWT).
- Mécanisme : publie un tombstone signé dans le broker pour le `messageId` correspondant → toute vérification ultérieure lèvera `BrokerExtractionException`.

---

#### `TokenTracker`
```
boolean hasActiveToken(String target)
```
- `target` peut être : un `groupId`, un `messageId` Protocol V2, ou un token signé.
- Retourne `true` si un token valide (non révoqué, non expiré) existe pour la cible donnée.

---

#### `MetadataBroker`
```
CompletableFuture<Void> send(String key, String message)  throws BrokerTransportException
String get(String key)  throws BrokerExtractionException
void sendLocal(String key, String message)  // F5 : écriture locale sans propagation réseau
```
- Abstraction découplée du transport.
- `key` = `messageId` Protocol V2 (format : `<version>:<groupId>:<sequenceId>`, ex: `2:user-123:session-A`).
- `message` = métadonnée encodée Protocol V2 (voir §3.2).
- `sendLocal` (F5) : écrit directement dans le cache local (RocksDB pour Kafka) sans passer par le réseau. Utilisé par le signataire pour rendre le token immédiatement vérifiable localement.

---

#### `TrustAnchor` (sealed interface)

Autorité de validation des annonces de clés. Le broker n'est **pas** une racine de confiance — tout message reçu est validé via le `TrustAnchor` avant utilisation.

Deux variantes (permits) :

| Variante | Rôle |
|---|---|
| `TrustAnchor.PublicKeyResolver` | Résout `signerId → PublicKey` long terme, la vérification RSA est faite localement |
| `TrustAnchor.DelegatedVerifier` | Délègue la vérification complète à un service externe (KMS, HSM…) |

---

#### `DataTransformer`
```
String serialize(Object data)  throws DataSerializationException
Object deserialize(String data)  throws DataDeserializationException
```
Interface de sérialisation générique (peu utilisée directement).

---

#### `DistributionMode` (enum)

| Valeur | Comportement |
|---|---|
| `DIRECT` | Le token **est** le JWT complet signé. |
| `INDIRECT` | Le token retourné **est** un `messageId` Protocol V2 (format: `<version>:<groupId>:<sequenceId>`). Le JWT réel est stocké dans le broker. |

---

### 3.2 Implémentation — `GenericSignerVerifier`

Classe centrale qui implémente simultanément **`DataSigner`**, **`TokenVerifier`**, **`TokenRevoker`** et **`TokenTracker`**.

**Constructeurs :**

```java
// Constructeur standard (sans limite de sessions)
new GenericSignerVerifier(broker, trustAnchor, signerId, longTermPrivateKey)

// Avec gestion de capacité par groupe
new GenericSignerVerifier(broker, trustAnchor, signerId, longTermPrivateKey, maxSessions, EvictionPolicy.FIFO)
```

**Paramètres :**

| Paramètre | Rôle |
|---|---|
| `MetadataBroker broker` | Broker pour publier/récupérer les métadonnées |
| `TrustAnchor trustAnchor` | Autorité de validation des annonces de clés |
| `String signerId` | Identifiant stable du signataire (lié à la clé long terme) |
| `PrivateKey longTermPrivateKey` | Clé privée long terme RSA-3072 pour signer les annonces certifiées |
| `int maxSessions` | Limite de sessions actives par groupe (`-1` = illimité) |
| `EvictionPolicy policy` | Stratégie d'éviction quand `maxSessions` est dépassé (`FIFO`, `LIFO`, `LRU`, `REJECT`) |

**Dépendances internes :**
- `MetadataBroker` (injectable via constructeur).
- `TrustAnchor` (injectable via constructeur) — valide les annonces de clés.
- `JwtMaker` / `JwtVerifier` : utilitaires internes (package-private) pour construction/parsing JWT RS256 (sans bibliothèque externe).
- `Config` : constantes (algo RSA-3072, durée de rotation des clés via env `VDOT_KEYS_ROTATION_MINUTES`, défaut 1440 min = 24h).
- `ProtocolV2` : utilitaires pour le format de message V2 (construction/parsing de `messageId`, métadonnées structurées).

#### Dérivation du `messageId` (Protocol V2)
```
messageId = <version>:<groupId>:<sequenceId>
```
Exemples : `2:user-123:session-A`, `2:service-X:550e8400-e29b-41d4-a716-446655440000`

- **`version`** : version du protocole (actuellement `2`).
- **`groupId`** : identifiant métier de regroupement (fourni par le `Configurer`).
- **`sequenceId`** : identifiant de séquence dans le groupe (fourni ou auto-généré UUID).

Le `messageId` est la clé du broker : il sert à publier et récupérer la métadonnée.

#### Flux de signature (`sign`)
```
1. Valider groupId et sequenceId (ProtocolV2.validateIdentifier)
2. Construire messageId = ProtocolV2.buildMessageId(groupId, sequenceId)
3. Construire JWT :
     header  : { alg: RS256, typ: JWT }
     payload : { sub: messageId, data: serializedPayload, iat, exp }
   Signer avec la clé RSA-3072 privée éphémère courante.
4. Construire l'annonce certifiée :
     canonicalAnnouncement = [pubKeyDer ‖ timestamp ‖ ttl ‖ signerId ‖ messageId]
     announcementSig = RSA-SHA256(canonicalAnnouncement, longTermPrivateKey)
5. Construire la métadonnée broker Protocol V2 :
     props = { mode, pubkey, timestamp, ttl, signerId, announcementSig, [token si INDIRECT] }
     v2Message = ProtocolV2.buildMessage(groupId, sequenceId, props)
6. broker.sendLocal(messageId, v2Message)    ← F5 : écriture locale immédiate
7. broker.send(messageId, v2Message)         ← propagation réseau asynchrone
8. Retourner :
     mode=DIRECT   → le JWT complet
     mode=INDIRECT → le messageId
```

#### Format de la métadonnée broker (Protocol V2)
```
Format structuré clé-valeur avec les propriétés suivantes :
  mode          : DIRECT ou INDIRECT
  pubkey        : clé publique éphémère RSA-3072 encodée Base64
  timestamp     : timestamp de publication (epoch seconds)
  ttl           : durée de validité en secondes
  signerId      : identifiant du signataire
  announcementSig : signature de l'annonce certifiée (Base64)
  token         : JWT complet (uniquement en mode INDIRECT)
```

#### Flux de vérification (`verify`)
```
1. Résoudre messageId et jwtToken :
     token contient "." → DIRECT → extraire messageId du claim "sub"
     token format V2    → INDIRECT → token IS le messageId
2. Extraire groupId et sequenceId depuis messageId (ProtocolV2.parseMessageId)
3. Vérifier la révocation (tombstone check)
4. broker.get(messageId) → métadonnée Protocol V2
5. Parser métadonnée → Map<String, String>
6. Valider l'annonce de clé via TrustAnchor (F1) :
     - Reconstruire le canonicalAnnouncement
     - Vérifier announcementSig avec la clé publique long terme du signerId
     - Si TrustAnchor rejette → BrokerExtractionException
7. Reconstruire PublicKey éphémère (X509EncodedKeySpec, RSA-3072)
8. JwtVerifier.verifyWith(publicKey).parseSignedClaims(jwt)
     → vérifie signature RS256 + expiration (claim "exp")
9. Extraire claim "data" → invoquer deserializer → retourner payload
```

#### Flux de révocation (`revoke`)
```
Par String (token JWT)  → extraire messageId du claim "sub" → publier tombstone signé
Par String (groupId)    → publier tombstone pour le groupe entier (target = __ALL__)
Par String (messageId)  → publier tombstone pour cette séquence spécifique
```
Les tombstones sont eux-mêmes signés par la clé long terme pour empêcher les fausses révocations.

#### Rotation des clés RSA-3072
- Générée immédiatement à la construction.
- Tournée via `ScheduledExecutorService` toutes `Config.KEYS_ROTATION_MINUTES` (défaut 24h).
- Seule la clé privée **courante** signe ; les clés publiques passées restent disponibles dans le broker jusqu'à leur expiration.
- Le champ `keyPair` est `volatile` pour la visibilité inter-threads.

---

### 3.3 `BasicConfigurer` (Builder Pattern)

```java
BasicConfigurer configurer = BasicConfigurer.builder()
    .distribution(DistributionMode.DIRECT)  // optionnel, défaut = DIRECT
    .groupId("user-123")                    // OBLIGATOIRE : identifiant de groupe
    .sequenceId("session-A")               // optionnel, auto-généré si absent
    .validity(3600)                         // OBLIGATOIRE : durée en secondes
    .serializedBy(mySerializer)             // optionnel, défaut = Jackson ObjectMapper
    .build();
```

Factory method statique `deserializer(Class<T>)` : créé le deserializer Jackson correspondant.

---

### 3.4 Exceptions (package `core.exceptions`)

| Exception | Signification |
|---|---|
| `BrokerExtractionException` | Clé introuvable, token révoqué, TrustAnchor rejette l'annonce, ou erreur broker |
| `BrokerTransportException` | Échec de l'envoi vers le broker |
| `DataSerializationException` | Échec de la sérialisation du payload |
| `DataDeserializationException` | Échec de la désérialisation du payload |
| `TrustResolutionException` | Échec de résolution/validation par le TrustAnchor |
| `SessionCapacityExceededException` | Nombre max de sessions atteint avec politique `REJECT` |

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

#### `sendLocal` (F5)
Écrit directement dans RocksDB sans passer par Kafka. Permet au signataire de vérifier immédiatement ses propres tokens sans attendre la propagation Kafka.

#### Tâche planifiée (chaque seconde)
1. `removeOldEmbeddedDatabaseEntries()` : supprime les entrées RocksDB dont `expiryMillis < now` (compaction F6).
2. `saveKafkaMessagesOnEmbeddedDatabase()` : poll Kafka (max 10s) → écrit ou supprime dans RocksDB.

> Message Kafka vide (`""`) → suppression RocksDB → révocation propagée localement.

#### Compaction RocksDB (F6)
Les entrées expirées sont purgées automatiquement lors de la tâche planifiée, évitant la croissance illimitée du cache local.

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

#### `send(messageId, message)`
- `message` vide → `DELETE` (révocation).
- `message` non vide → `DELETE` + `INSERT` (upsert manuel, contrainte `UNIQUE`).
- Exécution asynchrone via `CompletableFuture.runAsync()`.

#### `get(messageId)`
- `SELECT message_value … WHERE message_key = ?`
- Lève `BrokerExtractionException` si absent.
- **Aucune purge automatique** des entrées expirées côté DB ; l'expiration est détectée uniquement à la vérification JWT (claim `exp`).

#### Validation SQL
Regex : `^[a-zA-Z][a-zA-Z0-9_]*$` pour le nom de table (prévention injection SQL).

---

## 6. Module `veridot-tests`

Framework : **JUnit 5 + Testcontainers** (Docker requis).

### `DatabaseTest` (classe abstraite)

Tests paramétrés sur les deux `DistributionMode` :

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
| `verify_revoked_token_by_tracked_id_should_throws_exception` | `revoke(groupId)` → échec |
| `verify_token_regeneration_should_returns_payload_pojo` | signe 2× → dernier token valide |

Sous-classes concrètes : `PostgresTest`, `MySQLTest`, `MariaDBTest`, `MSSQLServerTest`.

### Tests Kafka

- `KafkaUnitTest` : tests basiques sign/verify.
- `KafkaSignerVerifierTest` : suite complète (sign, verify, revoke par token et par groupId).

### Tests TrustAnchor (F1)

- `TrustAnchorSecurityTest` : tests de sécurité — injection de fausse annonce, signature falsifiée, signerId inconnu, KMS indisponible.

---

## 7. Diagramme d'Interaction (Flux Global — TrustAnchor)

```
Signataire (Signer)                     Broker                    Vérificateur (Verifier)
     |                                    |                             |
     |-- sign(data, configurer) -------->|                             |
     |   [1] messageId = 2:grp:seq      |                             |
     |   [2] JWT signé RSA-3072         |                             |
     |   [3] Annonce certifiée :        |                             |
     |       sig(longTermKey,           |                             |
     |           pubKey+ts+ttl+         |                             |
     |           signerId+messageId)    |                             |
     |   [4] sendLocal(messageId, meta) |                             |
     |   [5] send(messageId, meta) ---->|                             |
     |   [6] retourne token/messageId   |                             |
     |                                  |                             |
     |                            (propagation asynchrone)            |
     |                                  |                             |
     |                                  |<-- verify(token) -----------|
     |                                  |    [1] résoudre messageId   |
     |                                  |    [2] broker.get(messageId)|
     |                                  |    [3] TrustAnchor valide   |
     |                                  |        l'annonce certifiée  |
     |                                  |    [4] reconstruire pubKey  |
     |                                  |    [5] vérifier JWT RS256   |
     |                                  |    [6] retourner payload    |
```

> **Point clé** : Le broker n'est pas une racine de confiance. Même si un attaquant injecte une fausse clé publique dans le broker, le `TrustAnchor` du vérificateur rejettera l'annonce car la signature ne correspondra pas à la clé long terme du `signerId` déclaré.

---

## 8. Récapitulatif des Classes Clés

```
io.github.cyfko.veridot.core
├── DataSigner                [interface @FunctionalInterface]
│   └── Configurer            [inner interface]
├── TokenVerifier             [interface @FunctionalInterface]
├── TokenRevoker              [interface]
├── TokenTracker              [interface — hasActiveToken(String)]
├── MetadataBroker            [interface — send, get, sendLocal]
├── TrustAnchor               [sealed interface]
│   ├── PublicKeyResolver     [non-sealed — signerId → PublicKey]
│   └── DelegatedVerifier     [non-sealed — vérification externe]
├── DataTransformer           [interface]
├── DistributionMode          [enum: DIRECT | INDIRECT]
├── exceptions/
│   ├── BrokerExtractionException
│   ├── BrokerTransportException
│   ├── DataSerializationException
│   ├── DataDeserializationException
│   ├── TrustResolutionException
│   └── SessionCapacityExceededException
└── impl/
    ├── GenericSignerVerifier [DataSigner + TokenVerifier + TokenRevoker + TokenTracker]
    ├── BasicConfigurer       [DataSigner.Configurer, Builder pattern]
    ├── Config                [constantes + env vars — RSA-3072, Protocol V2]
    ├── ProtocolV2            [format de message V2 — messageId, métadonnées]
    ├── JwtMaker              [package-private, construit JWT RS256]
    └── JwtVerifier           [package-private, vérifie JWT RS256]

io.github.cyfko.veridot.kafka
├── KafkaMetadataBrokerAdapter [MetadataBroker — Kafka + RocksDB + sendLocal (F5)]
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
├── TrustAnchorSecurityTest
└── UserData
```
