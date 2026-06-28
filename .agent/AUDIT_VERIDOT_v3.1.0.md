# Audit de sécurité — Veridot (io.github.cyfko/veridot)

| | |
|---|---|
| **Dépôt** | https://github.com/cyfko/veridot |
| **Commit audité** | `434e71a690aa0157594d898ad8c890b6d65cc9ed` (branche `main`, à jour à la date de l'audit) |
| **Version** | 3.1.0 |
| **Modules couverts** | `veridot-core`, `veridot-kafka`, `veridot-databases` |
| **Méthode** | Revue de code statique exhaustive (lecture intégrale des classes de production listées en annexe). Aucun test dynamique, aucun fuzzing, aucun pentest réseau n'a été réalisé. |
| **Auteur** | Audit assisté par Claude (Anthropic), à la demande de Frank KOSSI |
| **Date** | Voir métadonnées du document |

---

## 1. Synthèse exécutive

Veridot v3.1.0 implémente un modèle de confiance à deux niveaux (`TrustAnchor`) qui corrige avec succès la faille structurelle de v2.x ("broker = autorité de confiance implicite"). La conception cryptographique du cœur (`Protocol`, `TrustedAnchor`, `JwtVerifier`) est saine et ne présente pas de défaut classique (pas de confusion d'algorithme, encodage canonique anti-substitution correct, séparation stricte transport/confiance).

Cependant, l'audit révèle **une régression de sécurité critique** sur le mécanisme de révocation (F-01/F-02), qui permet à l'acteur précisément neutralisé par le modèle `TrustAnchor` — un *writer* du broker sans clé de confiance — de **supprimer l'effet d'une révocation légitime**. Plusieurs défauts de robustesse opérationnelle (concurrence inter-nœuds, perte silencieuse de messages, défaut d'autorisation par périmètre) aggravent ou prolongent la fenêtre d'exploitation de ce problème central.

### 1.1 Tableau de synthèse

| ID | Titre | Composant | Sévérité | Statut |
|---|---|---|---|---|
| F-01 | Révocation effaçable par écrasement de la clé tombstone | `veridot-core` (protocole) | 🔴 **Critique** | Confirmé |
| F-02 | Persistance inconditionnelle d'un tombstone non vérifié | `veridot-kafka` | 🟠 **Élevée** | Confirmé (aggrave F-01) |
| F-03 | `isAuthorizedForScope` permissif par défaut | `veridot-core` | 🟠 **Élevée** (selon déploiement) | Confirmé, documenté par l'auteur |
| F-04 | Race condition sur la rotation de clé éphémère | `veridot-core` | 🟡 **Moyenne** | Confirmé |
| F-05 | Fail-open du `RevocationManager` sur erreur broker | `veridot-core` | 🟡 **Moyenne** | Confirmé |
| F-06 | Bypass du `TrustAnchor` dans le comptage de sessions actives | `veridot-core` | 🟡 **Moyenne** | Confirmé |
| F-07 | TOCTOU sur l'application des quotas de session (inter-nœuds) | `veridot-core` / `veridot-kafka` | 🟠 **Élevée** (à l'échelle) | Confirmé |
| F-08 | Perte silencieuse de messages par auto-commit Kafka | `veridot-kafka` | 🟡 **Moyenne** | Confirmé |
| F-09 | Pression GC du parsing texte du protocole à haut débit | `veridot-core` | ⚪ **Faible / Informatif** | Confirmé, impact limité |
| F-10 | Hypothèses non vérifiées écartées (séquences monotones, typage des clés SQL) | — | — | **Infirmé** — voir §6 |

---

## 2. Constats détaillés

### F-01 — 🔴 CRITIQUE — Révocation effaçable par écrasement de la clé tombstone

**Composant :** `veridot-core/impl/RevocationManager.java`, conception du protocole (`Protocol.buildRevocationKey`)

**Description**

Chaque groupe possède une unique clé broker mutable pour ses révocations : `3:<groupId>:__REVOKE__`. C'est un *upsert* — chaque nouvelle écriture remplace la précédente, sans historique ni versionnage.

Lors de la vérification (`RevocationManager.validateNotRevoked`), si le tombstone lu échoue la vérification de signature via `TrustAnchor`, le code **ignore silencieusement le tombstone et traite le token comme non révoqué** :

```java
try {
    TrustedAnnouncement.verify(revokeKey, tombstoneMeta, trustAnchor);
    if (tombstoneTs > announcementTs) { ... throw "Session revoked" ... }
} catch (TrustResolutionException e) {
    // Tombstone signature invalide — ignorer le tombstone (pourrait être forgé)
    logger.warning(...);
}
```
*(RevocationManager.java, lignes 63-81)*

**Scénario d'attaque**

1. Un service légitime révoque une session : un tombstone signé est publié sur `3:user-42:__REVOKE__`.
2. Un acteur disposant d'un accès en écriture au transport (topic Kafka ou table SQL partagée) — **précisément l'acteur que le modèle `TrustAnchor` est censé neutraliser** — publie n'importe quel message arbitraire (non signé, ou signé par une identité bidon) sur cette même clé.
3. Comme c'est un upsert sur clé unique, ce message **remplace** le tombstone légitime.
4. À la prochaine vérification : le tombstone lu échoue la vérification de signature → traité comme "absence de tombstone" → **le token révoqué est de nouveau accepté comme valide**.

**Cause racine**

Asymétrie de conception entre la résolution de configuration et la résolution de révocation :
- Configuration (`ConfigurationResolver`) : signature invalide → *fallback* vers un niveau **moins permissif** (Local → Site → Global → défaut).
- Révocation (`RevocationManager`) : signature invalide → *fallback* vers l'état **le plus permissif** ("non révoqué").

Le broker est explicitement non fiable (c'est la prémisse de tout le modèle v3), mais l'état de révocation repose sur un slot mutable unique sans mécanisme anti-rollback (pas de cache local du dernier tombstone valide connu, pas de numéro de version monotone signé).

**Impact**

Compromission totale de la garantie de révocation pour quiconque dispose d'un accès en écriture au transport — sans avoir besoin de casser la moindre clé cryptographique.

---

### F-02 — 🟠 ÉLEVÉE — Persistance inconditionnelle d'un tombstone non vérifié (Kafka)

**Composant :** `veridot-kafka/KafkaMetadataBrokerAdapter.saveKafkaMessagesOnEmbeddedDatabase`

**Description**

```java
} else if (key.contains(":__REVOKE__")) {
    processRevocationMessage(key, message, db);   // peut refuser de supprimer les annonces
    db.put(key.getBytes(StandardCharsets.UTF_8), message.getBytes(StandardCharsets.UTF_8)); // écrit quand même
}
```
*(lignes 505-507)*

`processRevocationMessage` refuse correctement de supprimer les annonces de clé si la signature du tombstone est invalide (bonne protection anti-DoS, déjà en place dans cette version). **Mais la ligne `db.put` qui suit s'exécute sans condition**, persistant le message rejeté comme nouvelle valeur de la clé `__REVOKE__` dans RocksDB.

**Impact**

C'est le mécanisme d'écriture concret qui rend F-01 exploitable côté transport Kafka : c'est cette ligne qui permet à un message forgé d'écraser un tombstone légitime préexistant.

**Relation avec F-01** : ce défaut aggrave F-01 mais n'en est pas la cause unique — le module `veridot-databases` (qui n'a pas cette logique de traitement spécial des révocations) est vulnérable à la même classe d'attaque via un simple `UPSERT` SQL sur la clé `message_key`.

---

### F-03 — 🟠 ÉLEVÉE (selon déploiement) — `isAuthorizedForScope` permissif par défaut

**Composant :** `veridot-core/TrustAnchor.java`, lignes 116-139

```java
default boolean isAuthorizedForScope(String sid, String scopeKey) {
    return true; // Permissive by default — see warning above
}
```

**Description**

Toute identité dont la clé long-terme est résolue/vérifiée par l'ancre de confiance peut publier une configuration pour **n'importe quel scope** (un autre groupe, un site, ou la configuration globale), sauf si l'implémentant surcharge explicitement cette méthode. L'auteur documente le risque dans la Javadoc (`WARNING`), ce qui est une bonne pratique, mais le défaut par défaut reste permissif — un piège classique de "secure by default" non respecté pour rétrocompatibilité (raison explicite : compat avec implémentations pré-3.1.0).

**Impact**

Dans un déploiement multi-tenant où plusieurs identités de signature légitimes coexistent sous une même `TrustAnchor`, une identité compromise ou simplement mal intentionnée peut modifier les quotas de sessions, politiques d'éviction ou TTL par défaut de **groupes ou sites qu'elle ne devrait pas administrer** — escalade de privilèges horizontale.

**Constat positif** : c'est la seule des failles listées qui a été anticipée et documentée par l'auteur lui-même au moment de l'implémentation (avertissement explicite dans la Javadoc) — signe de maturité dans la démarche, même si le défaut reste à durcir.

---

### F-04 — 🟡 MOYENNE — Race condition sur la rotation de clé éphémère

**Composant :** `veridot-core/impl/GenericSignerVerifier.sign()` + `KeyRotationService`

**Description**

```java
jwt = JwtMaker.builder()
        ...
        .signWith(keyRotationService.getPrivateKey())   // lecture #1
        .compact();
...
metadataPublisher.publishKeyAnnouncement(groupId, sequenceId, ...,
        keyRotationService.getPublicKey(), ...);         // lecture #2
```

`currentKeyPair` est un champ `volatile KeyPair` unique. Les deux lectures (`getPrivateKey()` puis, plus tard, `getPublicKey()`) ne sont **pas atomiques entre elles**. Si une rotation de clé (par défaut toutes les 24h, configurable via `VDOT_KEYS_ROTATION_MINUTES`) se produit entre les deux appels, le JWT est signé avec l'**ancienne** clé privée mais la clé publique **nouvellement générée** est publiée — la vérification échouera systématiquement pour ce token, sans message d'erreur explicite côté signataire.

**Impact**

Échec de vérification silencieux et difficile à diagnostiquer (incident intermittent, non reproductible facilement, corrélé à l'heure de rotation). Pas de faille de confidentialité/intégrité, mais un défaut de disponibilité/fiabilité dont l'origine est non triviale à tracer en production.

---

### F-05 — 🟡 MOYENNE — Fail-open du `RevocationManager` sur erreur broker

**Composant :** `veridot-core/impl/RevocationManager.validateNotRevoked`, lignes 30-35

```java
try {
    tombstoneMsg = metadataBroker.get(revokeKey);
} catch (Exception e) {
    // No tombstone found → not revoked, proceed normally
    return;
}
```

**Description**

*Toute* exception levée par `metadataBroker.get()` — pas seulement "clé absente" — est interprétée comme "aucun tombstone, le token n'est pas révoqué". Une erreur transitoire spécifique à la lecture de cette clé (timeout réseau, erreur RocksDB locale, etc.) — qui n'affecterait pas nécessairement la lecture de l'annonce de clé déjà récupérée avec succès quelques lignes plus haut — produit le même résultat permissif qu'une absence légitime de révocation.

**Impact**

Combiné à F-01, ceci ouvre une seconde voie (non malveillante cette fois, purement liée à la fiabilité du transport) vers le même état final : un token qui devrait être rejeté est accepté.

---

### F-06 — 🟡 MOYENNE — Bypass du `TrustAnchor` dans le comptage de sessions actives

**Composant :** `veridot-core/impl/SessionManager.isMessageIdActive`, utilisée par `enforceSessionLimit` et `GenericSignerVerifier.hasActiveToken`

**Description**

```java
public boolean isMessageIdActive(String messageId) {
    String message = metadataBroker.get(messageId);
    Map<String, String> meta = Protocol.parseMetadata(message);
    // lecture brute de ts/ttl — AUCUN appel à TrustedAnnouncement.verify / TrustAnchor
    ...
}
```

Cette méthode lit directement les champs `ts`/`ttl` du broker sans jamais passer par le `TrustAnchor`. Elle est utilisée pour (a) la décision d'éviction de session (`enforceSessionLimit`), et (b) l'API publique `hasActiveToken`.

**Scénario d'attaque**

Un *writer* non fiable du broker (le même acteur que F-01/F-03) peut injecter de fausses entrées "actives" sous le `groupId` d'une victime, sans avoir besoin de forger une signature valide :
- gonfler artificiellement le compteur de sessions actives pour déclencher l'éviction forcée de sessions légitimes (déni de service ciblé) ;
- ou fausser le résultat de `hasActiveToken()` côté applicatif consommateur de cette API.

**Impact**

Déni de service ciblé sur la gestion de quota de sessions, sans interaction avec la couche cryptographique. Sévérité moindre que F-01 (pas de bypass d'authentification du *contenu* du token), mais même catégorie de cause racine : confiance accordée à des métadonnées brutes du broker hors du chemin `TrustAnchor`.

---

### F-07 — 🟠 ÉLEVÉE (à l'échelle) — TOCTOU sur l'application des quotas de session

**Composant :** `veridot-core/impl/GenericSignerVerifier.sign()` + `SessionManager.enforceSessionLimit`

**Description**

```java
Object groupLock = groupLocks.computeIfAbsent(groupId, k -> new Object());
synchronized (groupLock) {
    sessionManager.enforceSessionLimit(groupId);   // lecture du compteur
    metadataPublisher.publishKeyAnnouncement(...); // décision + écriture
}
```

Le verrou `synchronized` est porté par un champ `ConcurrentHashMap<String, Object>` **local à l'instance JVM** de `GenericSignerVerifier`. Il protège contre la concurrence de threads sur **un seul nœud**, mais n'offre **aucune coordination inter-nœuds**.

**Aggravation spécifique au backend Kafka**

Chaque instance de `KafkaMetadataBrokerAdapter` maintient son propre RocksDB local, alimenté de façon asynchrone par un consumer Kafka indépendant (poll ≈1s). Même en l'absence de concurrence stricte "au même instant", la **vue de l'état de quota** sur un nœud B peut être en retard de plusieurs centaines de millisecondes à quelques secondes par rapport à l'état réellement publié par un nœud A — la fenêtre de course est donc plus large qu'une simple collision temporelle.

**Impact**

Si plusieurs instances du service signataire émettent pour le même `groupId` (déploiement horizontalement scalé typique), la politique `REJECT` peut laisser passer plus de sessions que `maxSessions`, et les politiques `FIFO`/`LIFO`/`LRU` peuvent évincer la mauvaise session ou évincer en double.

---

### F-08 — 🟡 MOYENNE — Perte silencieuse de messages par auto-commit Kafka

**Composant :** `veridot-kafka/PropertiesUtil.java` (`ENABLE_AUTO_COMMIT_CONFIG = "true"`) + `KafkaMetadataBrokerAdapter.saveKafkaMessagesOnEmbeddedDatabase`

**Description**

L'auto-commit Kafka commite les offsets consommés **indépendamment du succès du traitement applicatif**. Si `db.put`/`db.delete` lève une `RocksDBException` pour un enregistrement donné (erreur transitoire RocksDB, disque plein, etc.), l'exception est capturée et journalisée — mais l'offset du message sera tout de même committé au prochain `poll()`. **Ce message ne sera jamais retraité.**

**Impact**

Pour une annonce de clé ordinaire : perte de disponibilité (le token concerné devient invérifiable). Pour un message de révocation ou de configuration : perte silencieuse et non malveillante d'un changement d'état de sécurité — une variante "accidentelle" du problème décrit en F-01/F-05.

---

### F-09 — ⚪ FAIBLE / INFORMATIF — Pression GC du parsing texte du protocole

**Composant :** `veridot-core/impl/Protocol.java` (`parseMessageId`, `parseMetadata`, encodage Base64url)

**Description**

Le protocole V3 est un format texte structuré (`3:<groupId>:<sequenceId>|name:b64val,...`). Chaque vérification implique : découpage de chaînes, sous-chaînes, décodage Base64url — donc des allocations d'objets éphémères sur le tas.

**Nuance importante** : les délimiteurs utilisés (`:`, `,`) sont des caractères uniques non spéciaux en regex ; `String.split()` bénéficie dans le JDK d'un chemin rapide qui évite la compilation d'un `Pattern` complet dans ce cas précis. La partie la plus sensible aux performances — la construction des octets canoniques à signer/vérifier (`buildCanonicalBytes`) — évite déjà la concaténation de `String` en écrivant directement dans un `ByteArrayOutputStream`.

**Impact**

Coût réel mais modéré, intrinsèque au choix protocolaire (texte structuré vs binaire). Pertinent seulement à très haut débit (plusieurs milliers de vérifications/seconde par nœud) ; pas un défaut d'implémentation négligente, plutôt un compromis de conception à garder à l'esprit si le débit cible augmente significativement.

---

## 3. Constats transversaux positifs (à ne pas perdre dans le bruit)

Pour équilibrer l'audit :

- `TrustAnchor` est `sealed` (`PublicKeyResolver` | `DelegatedVerifier`) — empêche l'ajout incontrôlé d'un 3ᵉ modèle de confiance.
- `Protocol.buildCanonicalBytes` inclut le `messageId` complet dans l'encodage signé → empêche une attaque de relocalisation/substitution d'annonce entre groupes/séquences.
- `JwtVerifier` ignore totalement l'`alg` déclaré dans le JWT et force `SHA256withRSA` côté vérificateur → élimine par construction les attaques classiques de confusion d'algorithme.
- `TrustedAnnouncement.verify` est centralisé et appelé de façon cohérente par `MetadataVerifier`, `RevocationManager` (chemin nominal) et `ConfigurationResolver`.
- Le nom de table SQL dans `DatabaseMetadataBroker` est validé par regex stricte et toutes les requêtes utilisent des `PreparedStatement` paramétrés — pas d'injection SQL.
- La clé primaire SQL est déjà un `BIGINT IDENTITY` séquentiel (pas la chaîne métier) — bon choix d'indexation dès la conception.
- `F-03` est le seul défaut que l'auteur a lui-même anticipé et documenté avec un avertissement explicite au moment de l'écrire.

---

## 4. Annexe — Fichiers revus intégralement

```
veridot-core/src/main/java/io/github/cyfko/veridot/core/
  TrustAnchor.java, MetadataBroker.java, DataSigner.java, TokenVerifier.java,
  TokenRevoker.java, TokenTracker.java, VerifiedData.java, EvictionPolicy.java,
  ConfigScope.java, DistributionMode.java
  exceptions/ (TrustResolutionException.java, BrokerExtractionException.java, ...)
  impl/ (GenericSignerVerifier.java, Protocol.java, TrustedAnnouncement.java,
         MetadataVerifier.java, MetadataPublisher.java, RevocationManager.java,
         SessionManager.java, ConfigurationResolver.java, KeyRotationService.java,
         JwtMaker.java, JwtVerifier.java, Config.java, BasicConfigurer.java)

veridot-kafka/src/main/java/io/github/cyfko/veridot/kafka/
  KafkaMetadataBrokerAdapter.java, PropertiesUtil.java, Constant.java,
  SignerConfig.java, VerifierConfig.java

veridot-databases/src/main/java/io/github/cyfko/veridot/databases/
  DatabaseMetadataBroker.java
```

---

## 5. Synthèse des sévérités (méthodologie simplifiée)

| Sévérité | Critère |
|---|---|
| 🔴 Critique | Contournement complet d'une garantie de sécurité fondamentale (authenticité/révocation), exploitable sans compromission cryptographique |
| 🟠 Élevée | Contournement partiel ou conditionnel (selon topologie de déploiement) d'une garantie de sécurité ou de disponibilité significative |
| 🟡 Moyenne | Défaut de robustesse pouvant dégrader la disponibilité ou l'intégrité opérationnelle sans bypass direct de la confiance |
| ⚪ Faible / Informatif | Compromis de conception ou optimisation potentielle sans impact sécurité direct |

---

## 6. Hypothèses externes examinées et écartées

Dans le cadre de cet audit, deux hypothèses soumises par une analyse tierce ont été vérifiées par grep/lecture exhaustive du code et **infirmées** :

1. **« Le `sequenceId` doit être monotone et un cache local en compare la valeur »** — Aucune trace de ce mécanisme dans le code. Le `sequenceId` par défaut est un `UUID.randomUUID()` (non séquentiel) ; la protection anti-rejeu réelle de Veridot repose sur TTL + drift d'horloge + comparaison de timestamp des tombstones, pas sur une monotonie de séquence.
2. **« Les identifiants sont stockés comme clé primaire textuelle, fragmentant les B-Tree »** — Le schéma réel de `DatabaseMetadataBroker` utilise un `BIGINT IDENTITY` comme clé primaire ; la chaîne métier n'a qu'une contrainte `UNIQUE` secondaire, ce qui est l'usage attendu pour un accès par clé métier dans une base relationnelle.

Ces points sont consignés ici pour la traçabilité de la démarche d'audit, mais ne doivent pas être traités comme des défauts confirmés.
