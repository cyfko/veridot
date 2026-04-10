# Propositions de correction — PROTOCOL_V2

> Chaque proposition adresse un problème concret identifié dans la spec. Classées par nature : **sous-spécifié**, **sur-spécifié**, **incohérent**.

---

## A. SOUS-SPÉCIFIÉ

---

### A1. La révocation n'est pas spécifiée

**Problème** : `__REVOKE__` est listée comme "séquence réservée future" (§5.3), mais la révocation est une fonctionnalité **déjà implémentée** dans V1 (`TokenRevoker.revoke()`). Le protocole V2 ne dit pas comment révoquer une session, ni un groupe entier.

**Proposition** : Promouvoir `__REVOKE__` de "réservée future" à "séquence définie" (§5.2), avec une spécification complète.

#### Format de révocation

**Révoquer une séquence spécifique** (une session) :
```
2:<groupId>:__REVOKE__|target:<base64(sequenceId)>,timestamp:<base64(ts)>
```

**Révoquer toutes les séquences d'un groupe** (toutes les sessions d'un utilisateur) :
```
2:<groupId>:__REVOKE__|target:<base64("__ALL__")>,timestamp:<base64(ts)>
```

**Propriétés de la métadonnée `__REVOKE__`** :

| Propriété | Type | Obligatoire | Description |
|---|---|---|---|
| `target` | `string` | OUI | Le `sequenceId` à révoquer, ou `__ALL__` pour révoquer tout le groupe |
| `timestamp` | `number` | OUI | Unix timestamp de la demande de révocation |

**Sémantique** :
- La réception d'un message `__REVOKE__` par un processeur DOIT entraîner la suppression immédiate de la métadonnée ciblée dans son store.
- Un `verify()` ultérieur sur une séquence révoquée DOIT échouer.
- La révocation est **irréversible** dans le scope du `sequenceId` : pour ré-autoriser, il faut créer une nouvelle séquence.

**Pourquoi pas un "message vide" comme en V1 ?** Parce que le modèle multi-sessions de V2 nécessite de distinguer "révoquer la session X de l'utilisateur Y" vs. "révoquer TOUTES les sessions de Y". Un message vide ne peut pas porter cette information.

---

### A2. Interaction `maxSessions` + `policy` en contexte distribué

**Problème** : La spec dit qu'un processeur DOIT "maintenir les compteurs de sessions par groupe" et "respecter la politique de session" (§7.1.3), mais ne dit pas :
- **OÙ** est maintenu le compteur (local ? broker ?)
- **QUAND** l'éviction est déclenchée
- **QUI** est responsable de l'éviction si plusieurs processeurs signent en parallèle

**Proposition** : Ajouter une section §3.6 "Gestion de la capacité de sessions".

#### Règles

1. **Le comptage est dérivé du broker, pas d'un compteur local.** Avant de signer une nouvelle séquence pour un groupe, le processeur DOIT interroger le broker pour connaître le nombre de séquences actives (non expirées, non révoquées) du groupe.

2. **L'éviction est déclenchée au moment de la signature**, par le processeur signataire. Si `count >= maxSessions`, le processeur émet un message `__REVOKE__` pour la séquence à évincer (selon la policy), **avant** de publier la nouvelle séquence.

3. **La cohérence est éventuelle.** Deux processeurs signant simultanément pour le même groupe peuvent temporairement dépasser `maxSessions`. Après convergence, le nombre effectif se stabilisera autour de la limite. C'est un choix délibéré : disponibilité > cohérence stricte (théorème CAP).

4. **Sans `maxSessions`** : pas de limite ; le processeur ne fait aucune vérification de capacité.

**Ce que cela implique pour `MetadataBroker`** : il faut pouvoir lister les séquences actives d'un groupe. L'interface actuelle (`send`/`get` par clé unique) ne suffit pas. Voir la proposition C1.

---

### A3. Le `messageId` ne spécifie pas comment l'objet signé le référence

**Problème** : §3.4 dit "L'objet à vérifier référence un `messageId`" (étape 1 de la vérification), mais ne dit pas **comment**. Dans V1, le `keyId` est embarqué dans le claim `sub` du JWT. La spec V2 devrait documenter ce mécanisme.

**Proposition** : Ajouter au §3.4 cette précision :

> L'objet signé DOIT référencer le `messageId` complet (`2:<groupId>:<sequenceId>`) de la métadonnée Véridot correspondante. Le mécanisme d'association est défini par l'implémentation (ex : claim `sub` d'un JWT, header HTTP, champ d'un document signé), mais l'identifiant DOIT être transmis intégralement et intact.

---

## B. SUR-SPÉCIFIÉ

---

### B1. Séquences réservées futures sans spécification

**Problème** : §5.3 réserve `__METRICS__`, `__STATUS__`, `__HEALTH__` sans les spécifier. C'est du design spéculatif qui :
- Bloque des noms sans raison concrète.
- Crée une dette documentaire (les implémenteurs pourraient attendre leur spec).
- Mélange les responsabilités : monitoring et health check sont des préoccupations **opérationnelles**, pas protocolaires.

**Proposition** : Supprimer la section §5.3 "Séquences Réservées Futures" entièrement. Garder uniquement la convention de nommage (§5.1) et le registre (§5.4) qui permettent d'ajouter de nouvelles séquences quand elles sont réellement spécifiées. Si un besoin opérationnel émerge, l'implémentation peut utiliser des séquences réservées hors protocole (convention locale) tant qu'elles suivent le pattern `__<NOM>__`.

**Séquences définies après correction** :
- `__CONFIG__` : messages de configuration (spécifié §4)
- `__ALL__` : identifiant universel (utilisé dans `__CONFIG__` et `__REVOKE__`)
- `__REVOKE__` : messages de révocation (spécifié via proposition A1)

---

### B2. La spec système de types JSON (§6.5) est superflue

**Problème** : §6.5 liste les types JSON autorisés (string, number, boolean, null, array, object avec profondeur max 3). Chaque propriété dans §6.4 a déjà son type défini. Lister les types JSON autorisés revient à re-spécifier JSON lui-même. La limite de profondeur 3 pour les objets est arbitraire et non justifiée.

**Proposition** : Supprimer §6.5. Les types sont déjà contraints par la définition de chaque propriété (§6.4). Si une future propriété nécessite un objet imbriqué, sa propre définition le précisera.

---

### B3. La propriété `payload` dans les messages normaux

**Problème** : §6.4.1 inclut `payload` (string, optionnel, "Payload signé pour API keys"). C'est un pattern différent du cœur de Véridot. Le protocole distribue des **métadonnées de vérification** (clé publique), pas le contenu signé lui-même. Embarquer le payload dans le message de métadonnée mélange deux responsabilités.

**Proposition** : Renommer `payload` en `token` et clarifier sa sémantique :

| Propriété | Type | Obligatoire | Description |
|---|---|---|---|
| `token` | `string` | NON | L'objet signé (JWT, API key…) quand le mode de distribution ne transmet pas le token directement au client (équivalent du `TokenMode.id` de V1) |

Cela correspond au mode `id` existant : le token n'est pas retourné au caller, il est stocké dans le broker et récupéré lors de la vérification. L'ancien nom `payload` était ambigu — ce n'est pas le payload du JWT, c'est le **JWT entier** stocké côté broker.

---

## C. INCOHÉRENT

---

### C1. Le format des valeurs dit "JSON" mais les exemples montrent des valeurs brutes

**Problème** : §6.2.2 dit "Contenu : JSON valide avant encodage", mais les exemples font :
```
timestamp:MTcwNjcxMjAwMA==  →  Base64("1706712000")     ← PAS du JSON (pas de guillemets, pas de wrapper)
mode:ZWNkc2E=               →  Base64("ecdsa")           ← PAS du JSON (pas de guillemets)
ttl:MzYwMA==                →  Base64("3600")             ← PAS du JSON
```

En JSON strict, `1706712000` serait déjà valide (c'est un number literal), mais `ecdsa` sans guillemets ne l'est pas. Les exemples encodent des **valeurs brutes UTF-8**, pas du JSON.

**Proposition** : Aligner la spec sur les exemples (le comportement le plus simple et le plus utile) :

> **§6.2.2 Valeurs**
> - **Encodage** : Base64url (RFC 4648 §5) sans padding
> - **Contenu** : Représentation UTF-8 de la valeur. Pour les nombres : représentation décimale. Pour les chaînes : la chaîne brute. Pour les structures complexes : JSON sérialisé.
> - **Taille maximale** : 1024 octets par valeur (après décodage)

**Pourquoi Base64url plutôt que Base64 standard ?** L'alphabet Base64url (`A-Z`, `a-z`, `0-9`, `-`, `_`) n'utilise ni `+` ni `/`, ce qui est plus sûr dans les URLs et cohérent avec l'implémentation V1 qui utilise déjà `Base64.getUrlEncoder()`. La spec actuelle dit RFC 4648 (standard, avec `+`/`/`), mais devrait dire RFC 4648 §5 (URL-safe).

---

### C2. `policy` est OUI obligatoire dans les configs, mais `maxSessions` est NON

**Problème** : §6.4.2 rend `policy` **obligatoire** et `maxSessions` **optionnel**. Une politique d'éviction sans limite de sessions n'a aucun sens : FIFO/LIFO/LRU sont des stratégies de choix quand on dépasse un seuil. Sans `maxSessions`, la policy ne se déclenche jamais.

**Proposition** : Deux options :

**Option A (recommandée)** : Rendre `policy` optionnel, avec un défaut (`FIFO`). La policy n'est pertinente que si `maxSessions` est défini. Si `maxSessions` est absent, `policy` est ignorée.

| Propriété | Obligatoire | Défaut | Description |
|---|---|---|---|
| `policy` | NON | `FIFO` | Politique d'éviction, appliquée uniquement si `maxSessions` est défini |
| `maxSessions` | NON | ∞ (pas de limite) | Nombre max de séquences actives par groupe |

**Option B** : Rendre les deux obligatoires. Mais ça force tous les messages de configuration à déclarer une politique même quand on veut juste définir un `defaultTTL`.

---

### C3. Le `MetadataBroker` actuel est structurellement incompatible avec le protocole V2

**Problème** : L'interface core actuelle est :
```java
CompletableFuture<Void> send(String key, String message)
String get(String key)
```
Clé plate → valeur plate. Or le protocole V2 introduit :
- Des identifiants structurés (`groupId:sequenceId`) → le broker doit pouvoir lister toutes les séquences d'un groupe.
- L'éviction par politique (`maxSessions`) → le broker doit pouvoir énumérer les séquences actives d'un groupe, triées par `timestamp`.

L'interface `get(String key)` ne permet aucune de ces opérations.

**Proposition** : L'interface `MetadataBroker` doit évoluer pour supporter le modèle V2. Minimum requis :

```java
public interface MetadataBroker {
    CompletableFuture<Void> send(String key, String message) throws BrokerTransportException;
    String get(String key) throws BrokerExtractionException;
    
    // V2 : nécessaire pour maxSessions/policy et hasActiveToken
    List<String> getKeysForGroup(String groupId) throws BrokerExtractionException;
}
```

`getKeysForGroup(groupId)` retourne la liste des `messageId` (ou `sequenceId`) actifs pour un groupe donné. Cela permet :
- Le comptage de sessions pour la policy d'éviction.
- L'implémentation de `hasActiveToken(tracker)` avec feed-back riche (combien de sessions actives ?).
- La révocation totale d'un groupe (`__ALL__`).

**Impact sur les implémentations** :
- `DatabaseMetadataBroker` : `SELECT message_key FROM <table> WHERE message_key LIKE '<groupId>:%'` — trivial.
- `KafkaMetadataBrokerAdapter` : itération RocksDB avec prefix seek (`groupId:`) — supporté nativement par RocksDB.

---

## Résumé des actions

| # | Type | Action | Impact |
|---|---|---|---|
| A1 | Sous-spécifié | Spécifier `__REVOKE__` complètement | Nouveau §5 + modif §5.2 |
| A2 | Sous-spécifié | Spécifier l'interaction `maxSessions`/`policy` en distribué | Nouveau §3.6 |
| A3 | Sous-spécifié | Documenter le lien objet signé ↔ `messageId` | Ajout dans §3.4 |
| B1 | Sur-spécifié | Supprimer séquences réservées futures | Supprimer §5.3 |
| B2 | Sur-spécifié | Supprimer la section types JSON | Supprimer §6.5 |
| B3 | Sur-spécifié | Renommer `payload` → `token` et clarifier | Modif §6.4.1 |
| C1 | Incohérent | Aligner valeurs sur "UTF-8 brut" + Base64url | Modif §6.2.2 |
| C2 | Incohérent | Rendre `policy` optionnel (dépend de `maxSessions`) | Modif §6.4.2 |
| C3 | Incohérent | Étendre `MetadataBroker` avec `getKeysForGroup()` | Modif interface core |
