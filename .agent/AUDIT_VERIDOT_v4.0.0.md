# Audit de sécurité — Veridot (io.github.cyfko/veridot)

| | |
|---|---|
| **Dépôt** | https://github.com/cyfko/veridot |
| **Commit audité** | `434e71a690aa0157594d898ad8c890b6d65cc9ed` (branche `main`) |
| **Version** | 4.0.0 (Protocol V4) |
| **Modules couverts** | `veridot-core` (intégralité des classes de production sous `io.github.cyfko.veridot.core` et `core.impl`) |
| **Méthode** | Revue de code statique exhaustive (lecture intégrale des classes listées en annexe, `Envelope`, `TrustRoot`, `CapabilityVerifier`, `LivenessChecker`/`Manager`, `EntryVerifier`, `GenericSignerVerifier`, `VersionWatermark`, `ReconciliationManager`, `KeyRotationService`, `FenceManager`, `Scope`/`EntryId`). Aucun test dynamique, aucun fuzzing, aucun pentest réseau n'a été réalisé. |
| **Référentiel antérieur** | `AUDIT_VERIDOT_v3.1.0.md` (10 constats F-01 à F-10 sur le modèle `TrustAnchor` v3.x) |
| **Auteur** | Audit assisté par Claude (Anthropic), à la demande de Frank KOSSI |
| **Date** | Voir métadonnées du document |

---

## 1. Synthèse exécutive

Veridot v4.0.0 remplace le modèle de confiance v3.x (`TrustAnchor` + révocation par tombstone mutable) par une architecture de **capabilities distribuées** : chaque action sensible (publication de config, de fence, d'epoch de clé) est soumise à une chaîne de délégation vérifiable (`CapabilityVerifier`), et la révocation repose désormais sur un modèle **positive-proof default-deny** (`LivenessChecker`) où un token n'est valide que s'il existe une attestation `LIVENESS=ACTIVE` signée et fraîche, protégée par un numéro de version strictement monotone (`VersionWatermark`).

Ce changement de modèle **corrige effectivement la régression critique F-01/F-02** de l'audit v3.1.0 (révocation annulable par écrasement non authentifié du tombstone) : un écrasement non signé de l'entrée `LIVENESS` est désormais rejeté par la vérification de signature avant même d'atteindre la logique de décision, et l'ancien fallback "signature invalide → traiter comme non révoqué" n'existe plus dans ce chemin. La race condition F-04 (rotation de clé éphémère) est également corrigée par la capture atomique `KeySnapshot`.

Cependant, l'audit révèle que **la garantie de cohérence distribuée sur laquelle repose tout l'édifice anti-rollback n'est pas effective dans l'implémentation actuelle** : le mécanisme de réconciliation périodique prévu par le protocole (§11.4, classe `ReconciliationManager`) existe mais **n'est instancié ni appelé nulle part** dans `GenericSignerVerifier`. Le `VersionWatermark` reste donc un cache strictement local et volatile par instance, sans aucune resynchronisation programmée contre l'état du broker. Par ailleurs, le chemin de révocation contient une opération d'écrasement brut hors-protocole (suppression non signée du `KEY_EPOCH`) qui rompt la cohérence du modèle "tout est une enveloppe signée et versionnée", et la documentation publique de l'API (javadoc) n'a pas été mise à jour pour refléter la sémantique réelle de V4, ce qui crée un risque de confiance excessive chez les intégrateurs.

### 1.1 Tableau de synthèse

| ID | Titre | Composant | Sévérité | Statut |
|---|---|---|---|---|
| V4-01 | `ReconciliationManager` non câblé — watermark local sans resynchronisation | `veridot-core/impl` | 🟠 **Élevée** | Confirmé |
| V4-02 | Révocation par écrasement brut non signé du `KEY_EPOCH` | `GenericSignerVerifier.revoke()` | 🟡 **Moyenne** | Confirmé |
| V4-03 | Cache de capability asymétrique (succès 60 s / échec 5 s) | `CapabilityVerifier` | 🟡 **Moyenne** | Confirmé, par conception |
| V4-04 | Dérive documentation/implémentation (javadoc V3 sur API V4) | `TokenVerifier`/`TokenRevoker`/`TokenTracker` | ⚪ **Faible / Informatif** | Confirmé |
| V4-05 | Sécurité du `DelegatedTrustRoot` entièrement déléguée à l'intégrateur | `TrustRoot` (interface) | ⚪ **Faible / Informatif** | Confirmé, par conception |

### 1.2 Suivi des constats v3.1.0

| ID v3.1.0 | Titre | Statut en v4.0.0 |
|---|---|---|
| F-01 | Révocation effaçable par écrasement de la clé tombstone | ✅ **Corrigé** — remplacé par `LIVENESS` signé + `VersionWatermark` monotone |
| F-02 | Persistance inconditionnelle d'un tombstone non vérifié | ✅ **Corrigé** — `Envelope.parse` + `signatureVerifier.verify` obligatoires avant toute lecture exploitée |
| F-03 | `isAuthorizedForScope` permissif par défaut | ✅ **Corrigé** — remplacé par `CapabilityVerifier` à chaîne de délégation bornée, default-deny |
| F-04 | Race condition sur la rotation de clé éphémère | ✅ **Corrigé** — `KeyRotationService.KeySnapshot` capturé atomiquement |
| F-05 | Fail-open du `RevocationManager` sur erreur broker | ⚠️ **Partiellement réévalué** — `LivenessChecker` est fail-closed (toute exception → rejet), mais voir V4-01 pour le risque résiduel de cohérence |
| F-06 | Bypass du `TrustAnchor` dans le comptage de sessions actives | Non revérifié dans cet audit (hors fichiers couverts) |
| F-07 | TOCTOU sur les quotas de session (inter-nœuds) | Non revérifié dans cet audit (`FenceManager`/`CapacityManager` à approfondir) |
| F-08 | Perte silencieuse de messages par auto-commit Kafka | Non applicable à `veridot-core` (module `veridot-kafka` non couvert ici) |
| F-09 | Pression GC du parsing texte à haut débit | Non revérifié — `Envelope`/`TlvCodec` v4 utilisent un encodage binaire TLV, le constat pourrait être caduc |

---

## 2. Constats détaillés

### V4-01 — 🟠 ÉLEVÉE — `ReconciliationManager` non câblé : watermark local sans resynchronisation

**Composant :** `core/impl/ReconciliationManager.java`, `core/impl/VersionWatermark.java`, `core/impl/GenericSignerVerifier.java`

**Description**

`VersionWatermark` est un registre **purement local et volatile** (`ConcurrentHashMap<String, Long>`, sans persistance). Toute la garantie anti-rollback du protocole (rejet d'une version `≤` watermark connu, utilisée par `LivenessChecker`, `EntryVerifier`, `ConfigResolver`, `CapabilityVerifier`) repose sur le fait que chaque instance a déjà vu, à un moment donné, la version la plus récente de chaque entrée pertinente.

Le protocole prévoit explicitement (§11.4) un mécanisme de réconciliation périodique : `ReconciliationManager.reconcile()` parcourt `broker.snapshot(scope)` pour rattraper le watermark local sur l'état réel du broker. Cette classe existe et est correctement implémentée, mais :

```
$ grep -rn "ReconciliationManager" core/impl/*.java
ReconciliationManager.java:20:final class ReconciliationManager implements AutoCloseable {
ReconciliationManager.java:25:    public void reconcile(...)
ReconciliationManager.java:111:        reconcile(...)   // appel interne récursif/planifié
```

Aucune occurrence de `new ReconciliationManager(...)` ni d'appel à `reconcile(...)` n'existe dans `GenericSignerVerifier`, qui est le seul point d'orchestration public de la librairie. La classe est donc **du code mort du point de vue de l'exécution réelle**.

**Scénario d'exploitation / dégradation**

1. Une instance de service A (vérificateur) démarre, ou redémarre après un incident. Son `VersionWatermark` est vide.
2. Une révocation a été émise précédemment par une instance B (watermark local de B mis à jour, mais jamais répliqué nulle part puisqu'aucune réconciliation ne tourne).
3. Si le broker sous-jacent (Kafka en production, selon la documentation produit) ne garantit pas une lecture strictement linéarisable de la dernière valeur — ce qui est le cas par défaut en cas de lag de réplication inter-partitions ou de partition réseau temporaire — l'instance A peut lire une version antérieure de l'entrée `LIVENESS` (encore `ACTIVE`) et l'accepter comme valide, puisqu'elle n'a aucune trace locale de la version `REVOKED` plus récente et qu'aucune resynchronisation programmée ne viendrait corriger cet état.
4. La fenêtre de risque n'est bornée par aucun mécanisme actif — elle persiste jusqu'à ce que l'instance A traite, par hasard, une opération qui la fait lire la bonne entrée à jour.

**Cause racine**

Écart entre la spécification du protocole (qui anticipe correctement le problème de cohérence distribuée et prévoit un correctif) et l'implémentation de référence (qui ne câble pas ce correctif dans le seul point d'entrée public).

**Impact**

Le watermark monotone — pièce centrale de la correction de l'ancienne faille F-01 — n'offre de garantie forte que **tant qu'une seule instance traite continuellement le trafic** d'un scope donné. Dès qu'on introduit plusieurs instances, des redémarrages, ou une consistance broker non strictement forte (cas réaliste avec Kafka), la protection anti-rollback redevient partiellement dépendante de la fraîcheur du broker plutôt que d'un mécanisme actif de la librairie.

**Recommandation**

Instancier `ReconciliationManager` dans `GenericSignerVerifier` (ou le rendre injectable) et planifier `reconcile()` à intervalle régulier pour chaque scope actif, dès la construction de l'orchestrateur. À défaut, documenter explicitement cette limite dans `docs/security.md` et exiger des garanties de cohérence forte côté implémentation de `Broker`.

---

### V4-02 — 🟡 MOYENNE — Révocation par écrasement brut non signé du `KEY_EPOCH`

**Composant :** `GenericSignerVerifier.revoke()`

**Description**

Le chemin de révocation effectue deux opérations distinctes et non atomiques :

```java
livenessManager.publishRevoked(liveEntryId, watermark);   // (A) signé, versionné
livenessManager.stopRenewalLoop(liveEntryId);
EntryId keyEpochId = new EntryId(scope, EntryType.KEY_EPOCH, sequenceId);
broker.put(keyEpochId.storageKey(), new byte[0]).join();  // (B) brut, non signé, non versionné
```

L'opération (A) est l'autorité réelle de révocation et fonctionne correctement (cf. correction de F-01). L'opération (B) écrit directement un tableau d'octets vide sur la clé `KEY_EPOCH`, en contournant entièrement `EntryPublisher`, donc sans signature ni passage par le `VersionWatermark`.

**Problèmes identifiés**

- **Non-atomicité** : si le processus s'interrompt entre (A) et (B), l'état est incohérent (LIVENESS révoqué mais KEY_EPOCH encore présent, ou l'inverse selon l'ordre d'exécution réel).
- **Incohérence de modèle** : c'est la seule écriture de tout le code base qui ne produit pas une `Envelope` signée — elle casse l'invariant "tout ce qui est lu et exploité par la logique de confiance est une enveloppe vérifiable", même si en pratique `Envelope.parse(new byte[0])` échouera proprement côté lecture (donc pas de risque d'acceptation d'un faux KEY_EPOCH).
- **Sémantique broker réelle non garantie** : si l'implémentation `Broker` cible Kafka, écrire un `byte[0]` n'est pas équivalent au tombstone natif de compaction Kafka (qui nécessite une valeur `null`, pas un tableau vide). Le nettoyage effectif du log de compaction n'est donc pas garanti par cette opération.

**Impact**

Risque limité en confidentialité/intégrité (la révocation reste effective grâce à (A) seule, puisque `LivenessChecker` est consulté indépendamment lors de la vérification), mais introduit une fenêtre d'incohérence opérationnelle et une dépendance implicite à une sémantique de broker non garantie par l'abstraction `Broker`.

**Recommandation**

Soit retirer l'opération (B) — elle est redondante puisque (A) suffit à invalider le token via `LivenessChecker` — soit la remplacer par une publication signée et versionnée d'un `KEY_EPOCH` "tombstone" explicite via `EntryPublisher`, cohérente avec le reste du modèle.

---

### V4-03 — 🟡 MOYENNE — Cache de capability asymétrique (succès 60 s / échec 5 s)

**Composant :** `CapabilityVerifier.assertAuthorized()`

**Description**

Les résultats positifs d'autorisation sont mis en cache 60 secondes, les négatifs seulement 5 secondes :

```java
cache.put(cacheKey, new CacheEntry(true, now + 60000));   // succès : 60s
cache.put(cacheKey, new CacheEntry(false, now + 5000));   // échec : 5s
```

Ce choix favorise la disponibilité (éviter de marteler le broker pour des refus répétés) mais signifie que si l'on révoque le **capability** d'un issuer déjà validé par une instance, cette instance continuera à l'accepter jusqu'à 60 secondes après la révocation — y compris pour signer de nouveaux tokens (`sign()` et `publishConfig()` appellent aussi `assertAuthorized`).

**Impact**

Fenêtre de 60 secondes pendant laquelle une autorisation révoquée reste effective localement, dans un scénario de compromission d'un issuer où l'urgence de coupure est justement maximale.

**Recommandation**

Ce n'est pas un défaut de conception en soi (c'est un choix explicite de cache TTL), mais il doit être documenté comme tel dans `docs/security.md`, avec une option de configuration pour réduire ce délai dans des contextes à exigence de révocation immédiate, ou une invalidation de cache active déclenchable par l'API (à l'image de `configResolver.invalidateCache()` qui existe déjà pour les configs mais pas pour les capabilities).

---

### V4-04 — ⚪ INFORMATIF — Dérive documentation/implémentation

**Composant :** Javadoc de `TokenVerifier`, `TokenRevoker`, `TokenTracker`

**Description**

Les javadocs publics de ces interfaces décrivent encore la sémantique du **Protocol V3** ("Protocol V3 messageId", "MetadataBroker", modèle de révocation simple groupId/sequenceId), alors que l'implémentation réelle (`GenericSignerVerifier`) repose sur le modèle de scopes/capabilities/liveness V4. Par exemple, le javadoc de `TokenRevoker` affirme :

> "ensuring that the revocation is broker-centric — any service sharing the same MetadataBroker will immediately stop accepting the revoked token"

Cette affirmation ne tient pas compte des deux constats ci-dessus (V4-01 : pas de garantie de cohérence active entre instances ; V4-03 : cache de capability pouvant retarder l'effet d'une révocation d'autorisation).

**Impact**

Risque de confiance excessive chez un intégrateur qui se base sur la javadoc plutôt que sur le code réel pour évaluer les garanties de la librairie.

**Recommandation**

Mettre à jour la javadoc de ces trois interfaces pour refléter le modèle V4 (scopes, capabilities, liveness) et qualifier précisément les garanties temporelles réelles (latence de cache, dépendance à la cohérence du broker).

---

### V4-05 — ⚪ INFORMATIF — Sécurité du `DelegatedTrustRoot` entièrement déléguée à l'intégrateur

**Composant :** `TrustRoot` / `DelegatedTrustRoot` (interfaces)

**Description**

`TrustRoot.isRootIdentity()` et `DelegatedTrustRoot.verifySignature()` sont des points d'extension fournis par l'intégrateur. Toute la chaîne de capability s'arrête dès qu'une identité est déclarée "root" (`checkCapabilityChain`, profondeur 0). La librairie ne fournit aucune protection structurelle contre une implémentation erronée de ces deux méthodes côté intégrateur (par exemple, une `isRootIdentity` trop permissive recréerait l'équivalent du F-03 historique au niveau applicatif).

**Impact**

Ce n'est pas un défaut du code Veridot lui-même — c'est inhérent à tout modèle de confiance pluggable — mais cela signifie que l'évaluation "secure by design" de Veridot doit être qualifiée : le **cœur cryptographique et le protocole** sont robustes, mais la sécurité globale d'un déploiement donné dépend entièrement de la justesse de l'implémentation de `TrustRoot` fournie par l'intégrateur, qui n'est pas auditable depuis ce dépôt.

**Recommandation**

Documenter explicitement dans `docs/security.md` les invariants attendus d'une implémentation correcte de `TrustRoot` (idempotence de la résolution, non-falsifiabilité de `isRootIdentity`, gestion des erreurs en fail-closed), et fournir si possible une implémentation de référence auditée (ex. JWK statique signé) plutôt que de laisser l'intégrateur repartir de zéro.

---

## 3. Points forts confirmés

- **Séparation de domaine cryptographique complète** : `Envelope.canonicalSigningBytes()` inclut magic, version de protocole, type d'entrée, scope, clé, version, timestamp, issuer et payload, tous préfixés en longueur — aucune ambiguïté de parsing, aucune possibilité de réutiliser une signature d'un type d'entrée vers un autre.
- **Modèle de révocation default-deny** : `LivenessChecker.assertLive` exige une preuve positive et fraîche (`LIVENESS=ACTIVE`) plutôt que l'absence d'un tombstone — inversion de logique correcte par rapport à v3.x, qui supprime la classe de vulnérabilité F-01/F-02.
- **Capture atomique de la rotation de clé éphémère** (`KeyRotationService.KeySnapshot`) — élimine la race condition F-04.
- **Chaîne de délégation de capability bornée et vérifiée** (profondeur max 10, vérification de signature et d'expiration à chaque saut).
- **Validation structurelle stricte** des identifiants (`Scope`, `EntryId` : longueurs, caractères de contrôle interdits, contraintes singleton) — bonne hygiène anti-injection au niveau du protocole.

## 4. Recommandations priorisées

1. **(Élevée)** Câbler `ReconciliationManager` dans `GenericSignerVerifier` ou documenter explicitement l'absence de garantie de cohérence multi-instances (V4-01).
2. **(Moyenne)** Remplacer l'écrasement brut du `KEY_EPOCH` dans `revoke()` par une publication signée et versionnée, ou le supprimer s'il est jugé redondant (V4-02).
3. **(Moyenne)** Documenter le délai d'effet réel d'une révocation de capability (jusqu'à 60 s) et envisager une invalidation de cache active (V4-03).
4. **(Faible)** Mettre à jour la javadoc publique pour refléter la sémantique V4 (V4-04).
5. **(Faible)** Documenter les invariants attendus d'une implémentation `TrustRoot` correcte (V4-05).
6. Étendre l'audit aux modules non couverts ici : `FenceManager`/`CapacityManager` (réévaluation de F-06/F-07 sous le nouveau modèle), et tout module de transport Kafka équivalent à l'ancien `veridot-kafka` si toujours maintenu.

## 5. Limites de l'audit

Revue de code statique uniquement, limitée au module `veridot-core` tel qu'il existe au commit audité. Aucune vérification dynamique (tests d'intrusion, fuzzing du décodeur TLV/Envelope, analyse de performance sous charge) n'a été effectuée. Les modules `veridot-kafka` et toute implémentation `Broker`/`TrustRoot` concrète utilisée en production ne sont pas couverts par ce document.

## Annexe — Fichiers revus

```
core/Broker.java
core/TrustRoot.java
core/PublicKeyTrustRoot.java
core/DelegatedTrustRoot.java
core/TokenRevoker.java
core/TokenTracker.java
core/TokenVerifier.java
core/impl/Envelope.java
core/impl/EntryId.java
core/impl/Scope.java
core/impl/SignatureVerifier.java
core/impl/EntryVerifier.java
core/impl/LivenessChecker.java
core/impl/LivenessManager.java
core/impl/CapabilityVerifier.java
core/impl/CapabilityPayload.java
core/impl/ConfigResolver.java
core/impl/FenceManager.java
core/impl/KeyRotationService.java
core/impl/VersionWatermark.java
core/impl/ReconciliationManager.java
core/impl/GenericSignerVerifier.java
```
