# Plan de remédiation — Veridot v4.0.0

| | |
|---|---|
| **Dépôt** | https://github.com/cyfko/veridot |
| **Commit de référence** | `434e71a690aa0157594d898ad8c890b6d65cc9ed` (branche `main`) |
| **Document source** | `AUDIT_VERIDOT_v4.0.0.md` (constats V4-01 à V4-05) |
| **Module concerné** | `java/veridot-core` |
| **Destinataire** | Agent IA exécutant — exécution scrupuleuse, séquentielle, sans extrapolation |
| **Build de référence** | `cd java && ./mvnw -q -pl veridot-core -am test` doit passer (0 échec, 0 skip non justifié) avant **et** après chaque tâche |

---

## 0. Règles d'exécution (à respecter sans exception)

1. **Ne jamais modifier le format binaire de l'`Envelope`** (magic, `PROTO_VERSION = 0x04`, ordre des champs TLV existants). Aucune tâche de ce plan ne le requiert. Si une tâche semble l'exiger, **s'arrêter et signaler** plutôt que d'improviser.
2. **Une tâche = un commit.** Chaque tâche ci-dessous doit être implémentée, testée et committée indépendamment, dans l'ordre indiqué en §6. Ne pas regrouper plusieurs tâches dans un seul commit.
3. **Avant toute tâche** : exécuter la suite de tests existante et noter le résultat (nombre de tests, 0 échec attendu). **Après toute tâche** : ré-exécuter la suite complète + les nouveaux tests ajoutés par la tâche. Ne jamais committer si un test échoue, sauf si l'échec est explicitement attendu et documenté dans le commit (cas inexistant dans ce plan).
4. **Ne pas renommer, déplacer, ni supprimer** de classe ou de méthode publique (`public`/protégée par l'API) sans qu'une tâche ne l'exige explicitement et ne précise la compatibilité binaire/source attendue.
5. **Toute nouvelle constante de configuration** doit suivre le pattern déjà en place dans `Config.java`/`Env.java`/`ConstantDefault.java` (résolution via variable d'environnement avec valeur par défaut, log `WARNING` si valeur invalide).
6. **Les javadocs touchées doivent être réécrites entièrement**, pas commentées en plus de l'existant — pas de double discours dans la documentation publique.
7. En cas d'ambiguïté non résolue par ce document, **ne pas deviner** : produire une note `AMBIGUITY.md` décrivant le point bloquant et stopper la tâche concernée, sans toucher au code.

---

## 1. Tâche V4-01 — Activer la réconciliation périodique du `VersionWatermark`

**Objectif** : faire en sorte que `ReconciliationManager` (déjà implémenté, déjà testé pour ses primitives internes via le protocole §11.4) soit réellement instancié et planifié par `GenericSignerVerifier`, pour chaque scope de groupe actif.

### 1.1 Modifications — `core/impl/GenericSignerVerifier.java`

**1.1.1** Ajouter un champ délégué, au même endroit que les autres délégués (`entryPublisher`, `configResolver`, etc.) :

```java
private final ReconciliationManager reconciliationManager = new ReconciliationManager();
```

**1.1.2** Ajouter une constante de configuration dans `Config.java` (voir §1.2) : `RECONCILIATION_INTERVAL_MINUTES`, défaut **15 minutes**. Justification du défaut : suffisamment fréquent pour borner la fenêtre de risque décrite dans V4-01 à une valeur raisonnable (15 min max d'incohérence résiduelle après un redémarrage d'instance), sans saturer le broker avec des `snapshot()` trop fréquents sur des scopes à fort volume.

**1.1.3** Dans la méthode `sign(...)`, **à l'intérieur du bloc `synchronized (groupLock)`**, juste après la ligne :

```java
Object groupLock = groupLocks.computeIfAbsent(groupId, k -> new Object());
synchronized (groupLock) {
```

et avant la résolution du `siteId`, ajouter :

```java
    // V4-01: garantir qu'une réconciliation périodique tourne pour ce scope de groupe
    ensureReconciliationStarted(scope);
```

**1.1.4** Ajouter une méthode privée dédiée, juste avant `close()` :

```java
private final java.util.Set<Scope> reconciledScopes = java.util.concurrent.ConcurrentHashMap.newKeySet();

private void ensureReconciliationStarted(Scope scope) {
    if (reconciledScopes.add(scope)) {
        reconciliationManager.startPeriodicReconciliation(
            scope,
            java.time.Duration.ofMinutes(Config.RECONCILIATION_INTERVAL_MINUTES),
            scheduler,
            broker,
            watermark,
            trustRoot,
            entryPublisher,
            signerId,
            longTermPrivateKey,
            envelopeSigAlg
        );
    }
}
```

Justification du `Set` de garde : `startPeriodicReconciliation` appelle déjà `stopPeriodicReconciliation(scope)` en interne avant de replanifier — sans cette garde, chaque appel à `sign()` sur un groupe déjà connu redémarrerait inutilement la tâche planifiée (perte du delay initial déjà écoulé). Le `ConcurrentHashMap.newKeySet()` garantit l'idempotence thread-safe sans verrou supplémentaire.

**1.1.5** Dans `close()`, ajouter l'arrêt propre :

```java
@Override
public void close() {
    if (keyRotationService != null) {
        keyRotationService.close();
    }
    if (livenessManager != null) {
        livenessManager.stopAll();
    }
    reconciliationManager.close();   // <-- ajout
    scheduler.shutdownNow();
}
```

**1.1.6** Dans `revoke(...)` et `publishConfig(...)`, qui opèrent sur des `Scope` qui ne passent pas forcément par `sign()` au préalable (ex. révocation d'un groupe jamais signé localement par cette instance, ou config publiée au niveau `site`/`global`), appeler également `ensureReconciliationStarted(targetScope)` / `ensureReconciliationStarted(scope)` au début de chacune de ces deux méthodes, pour que tout scope manipulé par l'instance bénéficie de la réconciliation — pas seulement les scopes de signature.

### 1.2 Modifications — `core/impl/Config.java` et `core/impl/ConstantDefault.java`/`Env.java`

Suivre exactement le pattern existant pour `KEYS_ROTATION_MINUTES` :

- Dans `ConstantDefault` : ajouter `static final long RECONCILIATION_INTERVAL_MINUTES = 15;`
- Dans `Env` : ajouter `static final String RECONCILIATION_INTERVAL_MINUTES = "VDOT_RECONCILIATION_INTERVAL_MINUTES";`
- Dans `Config` : ajouter le champ public `RECONCILIATION_INTERVAL_MINUTES` avec javadoc dédiée (mentionner explicitement que réduire cette valeur réduit la fenêtre de risque de rollback inter-instances décrite en V4-01, au prix d'appels `broker.snapshot()` plus fréquents), et ajouter le bloc de résolution dans le bloc `static { ... }` existant, en miroir exact du bloc `KEYS_ROTATION_MINUTES`.
- Mettre à jour le tableau javadoc de variables d'environnement en haut de la classe `Config` pour ajouter la nouvelle ligne.

### 1.3 Tests à ajouter

Créer `core/impl/ReconciliationWiringTest.java` (package `io.github.cyfko.veridot.core.impl`, donc accès aux classes package-private) :

1. **`reconciliation_is_started_on_first_sign()`** : construire un `GenericSignerVerifier` avec un `InMemoryBroker`, appeler `sign(...)` une fois sur un `groupId` donné, puis vérifier — via un `ScheduledExecutorService` injecté en test ou via un délai d'attente court (`awaitility` si disponible, sinon `Thread.sleep` borné avec retry) — qu'un `SNAPSHOT_MARKER` apparaît dans `broker.snapshot(Scope.group(groupId))` après au moins un cycle de réconciliation. **Pour rendre ce test rapide**, ne pas utiliser le défaut de 15 minutes : exposer l'intervalle de réconciliation comme paramètre constructible pour les tests (voir 1.4 ci-dessous) et utiliser un intervalle de quelques centaines de millisecondes dans ce test uniquement.
2. **`reconciliation_is_idempotent_across_multiple_signs()`** : appeler `sign(...)` plusieurs fois sur le même `groupId`, vérifier qu'une seule tâche planifiée existe (via un compteur d'invocations instrumenté, ou en vérifiant qu'il n'y a pas d'accumulation de tâches dans `ReconciliationManager.tasks` — nécessite éventuellement un getter de test package-private `tasksCountForTest()` à ajouter à titre strictement interne sur `ReconciliationManager`, annoté `@VisibleForTesting`-style en commentaire).
3. **`close_stops_all_reconciliation_tasks()`** : appeler `close()` sur le `GenericSignerVerifier`, vérifier qu'aucune nouvelle entrée `SNAPSHOT_MARKER` n'apparaît après un délai supérieur à l'intervalle de réconciliation configuré pour le test.

### 1.4 Modification additionnelle requise pour la testabilité

Le constructeur principal de `GenericSignerVerifier` n'expose pas l'intervalle de réconciliation ni le `ScheduledExecutorService` interne. Pour permettre le test 1.3.1 sans attendre 15 minutes réelles :

- Ajouter un constructeur **package-private** supplémentaire (visibilité strictement limitée au package `io.github.cyfko.veridot.core.impl`, donc inaccessible à l'API publique) qui accepte un `long reconciliationIntervalMinutesOverride` en dernier paramètre, utilisé uniquement par les tests situés dans le même package. Ne **pas** exposer ce paramètre dans l'API publique (`public`) — cela romprait l'encapsulation de la configuration opérationnelle.
- Tous les constructeurs publics existants doivent déléguer vers ce nouveau constructeur avec `Config.RECONCILIATION_INTERVAL_MINUTES` comme valeur, pour ne rien changer au comportement par défaut.

### 1.5 Critères d'acceptation

- [ ] `mvn test` passe intégralement, y compris les 3 nouveaux tests.
- [ ] Aucune classe ni méthode publique n'a changé de signature (vérifier avec `mvn -pl veridot-core compile` puis comparer la javadoc générée si un outil de diff d'API est disponible ; à défaut, relecture manuelle de tous les `public` modifiés dans `GenericSignerVerifier.java`).
- [ ] Un appel à `sign()` sur un groupe déclenche, après l'intervalle configuré, au moins une entrée `SNAPSHOT_MARKER` visible via `broker.snapshot(scope)`.
- [ ] `close()` arrête bien toutes les tâches planifiées (pas de thread résiduel après `scheduler.shutdownNow()` + `reconciliationManager.close()` — vérifiable en inspectant qu'aucune nouvelle écriture broker n'intervient après `close()`).

---

## 2. Tâche V4-02 — Supprimer l'écrasement brut non signé du `KEY_EPOCH` lors de la révocation

**Objectif** : retirer l'opération hors-protocole de `revoke()` qui écrit `new byte[0]` directement sur la clé `KEY_EPOCH`, puisque la révocation est déjà pleinement effective et auditable via `LIVENESS=REVOKED` (`LivenessChecker.assertLive`, appelé à l'étape 7 de `EntryVerifier.verifyKeyEpoch`).

**Décision de conception retenue pour cette tâche (ne pas dévier)** : option **suppression pure**, pas de remplacement par un nouveau type de tombstone signé. Justification : le `LIVENESS=REVOKED` signé et versionné est déjà l'autorité de révocation (toute vérification de token passe obligatoirement par `livenessChecker.assertLive` — il n'existe aucun chemin de vérification qui contournerait cette étape). L'écrasement du `KEY_EPOCH` est donc redondant pour la sécurité, et son retrait élimine entièrement le risque de non-atomicité et l'hypothèse incorrecte de sémantique de tombstone Kafka décrites en V4-02, sans aucune perte de garantie.

### 2.1 Modifications — `core/impl/GenericSignerVerifier.java`, méthode `revoke(String, String)`

Remplacer le corps de la branche `if (sequenceId == null)` :

```java
// AVANT
for (SessionCounter.SessionInfo session : active) {
    EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, session.sessionKey());
    livenessManager.publishRevoked(liveEntryId, watermark);
    livenessManager.stopRenewalLoop(liveEntryId);

    // Physically delete KEY_EPOCH from broker
    EntryId keyEpochId = new EntryId(scope, EntryType.KEY_EPOCH, session.sessionKey());
    broker.put(keyEpochId.storageKey(), new byte[0]).join();
}
```

```java
// APRÈS
for (SessionCounter.SessionInfo session : active) {
    EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, session.sessionKey());
    livenessManager.publishRevoked(liveEntryId, watermark);
    livenessManager.stopRenewalLoop(liveEntryId);
    // V4-02: la suppression physique du KEY_EPOCH a été retirée — elle était redondante
    // et non conforme au modèle d'enveloppe signée du protocole. La révocation est
    // pleinement effective via LIVENESS=REVOKED, vérifié obligatoirement par
    // EntryVerifier.verifyKeyEpoch / LivenessChecker.assertLive avant toute acceptation.
}
```

Et de manière symétrique pour la branche `else` (révocation d'une session unique) :

```java
// AVANT
EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, sequenceId);
livenessManager.publishRevoked(liveEntryId, watermark);
livenessManager.stopRenewalLoop(liveEntryId);

// Physically delete KEY_EPOCH from broker
EntryId keyEpochId = new EntryId(scope, EntryType.KEY_EPOCH, sequenceId);
broker.put(keyEpochId.storageKey(), new byte[0]).join();
```

```java
// APRÈS
EntryId liveEntryId = new EntryId(scope, EntryType.LIVENESS, sequenceId);
livenessManager.publishRevoked(liveEntryId, watermark);
livenessManager.stopRenewalLoop(liveEntryId);
// V4-02: voir commentaire équivalent ci-dessus.
```

**Ne pas** retirer les imports ni la déclaration `EntryId keyEpochId` si elle est réutilisée ailleurs dans la méthode (vérifier par compilation — `EntryType.KEY_EPOCH` reste utilisé dans `sign()` et `verify()`, donc l'import de `EntryType` reste nécessaire ; seules les deux lignes locales `EntryId keyEpochId = ...` / `broker.put(...)` de la méthode `revoke()` doivent disparaître).

### 2.2 Tests à modifier/ajouter

1. Rechercher dans la suite de tests existante (`CoreUnitTest.java`, `ConfigTrustSecurityTest.java`, `MultiInstanceSessionTest.java`) toute assertion qui vérifierait explicitement que `broker.get(keyEpochStorageKey)` retourne un tableau vide ou `null` après `revoke()` — si une telle assertion existe, elle doit être **remplacée** par une assertion sur le statut `LIVENESS` (`LivenessPayload.isActive() == false`) et sur le fait que `verify()` lève bien une exception après révocation (le comportement observable ne doit pas changer).
2. Ajouter `core/impl/RevocationDoesNotTouchKeyEpochTest.java` :
   - **`revoke_session_leaves_key_epoch_envelope_intact_but_verify_still_fails()`** : signer un token, le révoquer (révocation de session unique), puis vérifier que `broker.get(keyEpochStorageKey)` retourne toujours l'enveloppe `KEY_EPOCH` originale signée (non vide, parseable), **et** que `verifier.verify(token, ...)` lève bien `BrokerExtractionException` (la révocation reste effective malgré la non-suppression du KEY_EPOCH).
   - **`revoke_group_leaves_all_key_epoch_envelopes_intact_but_all_verify_fail()`** : même test pour la révocation de groupe complet (`sequenceId == null`), avec au moins 2 sessions actives.

### 2.3 Critères d'acceptation

- [ ] Les deux occurrences de `broker.put(keyEpochId.storageKey(), new byte[0])` dans `revoke()` ont disparu du fichier.
- [ ] Aucun test existant ne dépendait de cette suppression physique pour passer (sinon, ces tests doivent avoir été corrigés selon 2.2.1, pas supprimés).
- [ ] Les deux nouveaux tests de 2.2.2 passent.
- [ ] `mvn test` passe intégralement.

---

## 3. Tâche V4-03 — Réduire et documenter la fenêtre de propagation de révocation de capability

**Objectif** : offrir une invalidation active du cache de `CapabilityVerifier` (symétrique à `ConfigResolver.invalidateCache()`, déjà existant), et documenter précisément les délais de propagation par défaut.

### 3.1 Modifications — `core/impl/CapabilityVerifier.java`

Ajouter, en miroir exact des méthodes `invalidateCache(Scope)` / `clearCache()` de `ConfigResolver` :

```java
/**
 * Invalidates all cached authorization results (positive and negative) for the given
 * (issuer, scope) pair, forcing the next assertAuthorized call to re-walk the
 * capability chain against the broker.
 *
 * <p>Use this immediately after revoking or modifying a CAPABILITY entry for {@code issuer}
 * to avoid waiting out the default cache TTL (see {@link Config#CAPABILITY_CACHE_TTL_SECONDS}).</p>
 */
public void invalidateAuthorization(String issuer, Scope scope) {
    if (issuer == null || scope == null) {
        return;
    }
    cache.keySet().removeIf(key -> key.startsWith(issuer + "\0" + scope.value() + "\0"));
}

/**
 * Invalidates every cached authorization result for the given issuer across all scopes.
 * Use this for a full deauthorization of a compromised or terminated issuer.
 */
public void invalidateAuthorizationsForIssuer(String issuer) {
    if (issuer == null) {
        return;
    }
    cache.keySet().removeIf(key -> key.startsWith(issuer + "\0"));
}

public void clearCache() {
    cache.clear();
}
```

Remplacer les valeurs littérales de TTL actuellement codées en dur :

```java
cache.put(cacheKey, new CacheEntry(true, now + 60000));
...
cache.put(cacheKey, new CacheEntry(false, now + 5000));
```

par :

```java
cache.put(cacheKey, new CacheEntry(true, now + Config.CAPABILITY_CACHE_TTL_SECONDS * 1000L));
...
cache.put(cacheKey, new CacheEntry(false, now + Config.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS * 1000L));
```

### 3.2 Modifications — `core/impl/Config.java`

Ajouter, en suivant le pattern déjà établi en §1.2 :

- `ConstantDefault.CAPABILITY_CACHE_TTL_SECONDS = 60` (valeur identique à l'actuelle, pour ne rien changer au comportement par défaut)
- `ConstantDefault.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS = 5`
- `Env.CAPABILITY_CACHE_TTL_SECONDS = "VDOT_CAPABILITY_CACHE_TTL_SECONDS"`
- `Env.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS = "VDOT_CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS"`
- Champs publics correspondants dans `Config`, avec javadoc précisant explicitement : *"Après révocation d'une CAPABILITY, un issuer déjà validé par une instance reste accepté par cette instance jusqu'à expiration de ce TTL, sauf appel explicite à `CapabilityVerifier.invalidateAuthorization(...)`."*
- Mise à jour du tableau récapitulatif des variables d'environnement en haut de `Config`.

### 3.3 Câblage de l'invalidation active

`CapabilityVerifier` est actuellement instancié localement (`new CapabilityVerifier()`) à chaque appel de `sign()`, `verify()`, `revoke()`, `publishConfig()`, `hasActiveToken()` dans `GenericSignerVerifier` — donc **le cache n'est aujourd'hui jamais partagé entre deux opérations**, ce qui signifie que le cache n'a, en l'état actuel du code, aucun effet observable inter-appels (chaque appel repart d'un cache vide). C'est un second défaut, non documenté dans l'audit initial, découvert pendant la préparation de cette tâche.

**Action corrective associée (à exécuter dans cette même tâche, périmètre étendu)** : promouvoir `CapabilityVerifier` en champ d'instance partagé de `GenericSignerVerifier`, au même titre que `configResolver` :

```java
private final CapabilityVerifier capabilityVerifier = new CapabilityVerifier();
```

puis remplacer **toutes** les occurrences locales `CapabilityVerifier capVerifier = new CapabilityVerifier();` dans `sign()`, `verify()` (via le passage à `entryVerifier.verifyKeyEpoch(...)`), `revoke()` (si applicable — vérifier si `revoke()` en instancie une), `publishConfig()`, et `hasActiveToken()`, par une référence au champ partagé `this.capabilityVerifier`.

Une fois ce câblage fait, appeler `capabilityVerifier.invalidateAuthorizationsForIssuer(issuer)` n'a de sens applicatif que si une méthode publique permet de révoquer une capability — **vérifier d'abord si une telle méthode existe** dans `GenericSignerVerifier` (recherche : `grep -n "CAPABILITY" GenericSignerVerifier.java`). Si aucune méthode de publication/révocation de `CAPABILITY` n'existe encore dans l'orchestrateur public, **ne pas en créer une dans le cadre de cette tâche** (hors périmètre de l'audit V4-03, qui porte sur la propagation de cache, pas sur l'ajout d'une fonctionnalité de gestion des capabilities) — se limiter à exposer les méthodes d'invalidation sur `CapabilityVerifier` lui-même pour un usage futur, et le documenter dans `docs/security.md` (§3.4).

### 3.4 Documentation — `docs/security.md`

Ajouter une sous-section "Délais de propagation des révocations" décrivant, pour chaque mécanisme :

| Mécanisme | Délai de propagation par défaut | Variable d'environnement |
|---|---|---|
| Révocation de session/token (`LIVENESS=REVOKED`) | Immédiat dès lecture broker à jour (sous réserve de cohérence du broker, cf. V4-01) | — |
| Révocation de capability d'un issuer | Jusqu'à `CAPABILITY_CACHE_TTL_SECONDS` (défaut 60 s) pour une autorisation déjà en cache positif | `VDOT_CAPABILITY_CACHE_TTL_SECONDS` |
| Configuration de scope (`CONFIG`) | Jusqu'à `CONFIG_CACHE_TTL_SECONDS` (défaut 60 s) | (variable existante à documenter si absente) |

### 3.5 Tests à ajouter

`core/impl/CapabilityCacheTest.java` :
1. **`invalidateAuthorization_forces_recheck()`** : autoriser un issuer (cache positif), modifier/supprimer son entrée `CAPABILITY` directement dans le broker de test pour simuler une révocation, vérifier que sans invalidation l'ancien résultat en cache reste retourné (`assertAuthorized` ne lève pas), puis appeler `invalidateAuthorization(issuer, scope)` et vérifier que `assertAuthorized` lève désormais `VeridotException(CAPABILITY_NOT_FOUND)`.
2. **`negative_cache_expires_before_positive_cache()`** : vérifier `CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS < CAPABILITY_CACHE_TTL_SECONDS` par construction (test de configuration, pas de comportement runtime).
3. **`capability_cache_is_shared_across_orchestrator_calls()`** : après le câblage de 3.3, instrumenter (ou inspecter via réflexion package-private) que deux appels successifs à `sign()` sur le même groupe ne déclenchent qu'une seule résolution effective de la chaîne de capability (le second doit être servi par le cache). Si l'instrumentation directe est trop intrusive, ce test peut être implémenté en comptant les appels à `broker.get()` sur les clés `CAPABILITY` via un `Broker` de test décorateur qui incrémente un compteur.

### 3.6 Critères d'acceptation

- [ ] `CapabilityVerifier` est un champ partagé unique de `GenericSignerVerifier`, plus aucune instanciation locale `new CapabilityVerifier()` dans les méthodes de l'orchestrateur (sauf si une raison documentée l'exige — à justifier explicitement en commentaire si c'est le cas).
- [ ] Les deux nouvelles méthodes d'invalidation sont publiques sur `CapabilityVerifier` et testées.
- [ ] Les TTL sont configurables via variables d'environnement selon le pattern existant.
- [ ] `docs/security.md` contient le tableau de §3.4.
- [ ] `mvn test` passe intégralement.

---

## 4. Tâche V4-04 — Mettre à jour la javadoc publique (V3 → V4)

**Objectif** : éliminer toute référence à "Protocol V3" / "MetadataBroker" dans la javadoc des interfaces publiques, et remplacer les affirmations de garantie inexactes par une description fidèle au modèle V4, incluant un renvoi explicite vers les délais documentés en §3.4 de ce plan.

### 4.1 Fichiers et remplacements exacts

**`core/TokenRevoker.java`** — réécrire intégralement le javadoc de classe et de méthode. Points obligatoires à couvrir dans la nouvelle version :
- Remplacer toute mention de "Protocol V3 identifiers" par la terminologie V4 réelle : `groupId` reste valide (toujours utilisé comme composant de `Scope.group(groupId)`), mais le mécanisme sous-jacent est `LIVENESS` signé/versionné sur un `EntryId(scope, EntryType.LIVENESS, sequenceId)`, pas un tombstone `MetadataBroker`.
- Retirer l'affirmation *"any service sharing the same MetadataBroker will immediately stop accepting the revoked token"* et la remplacer par une formulation exacte, par exemple : *"Revocation takes effect for any verifier instance as soon as it observes the published `LIVENESS=REVOKED` entry from the broker. This is not instantaneous in a distributed deployment: see {@link io.github.cyfko.veridot.core.impl.Config#RECONCILIATION_INTERVAL_MINUTES} for the bound on cross-instance watermark staleness, and note that broker read consistency is an operational assumption of this guarantee, not one enforced by this library alone."*
- Garder les exemples d'usage (`@code` blocks) tels quels s'ils restent syntaxiquement corrects vis-à-vis de l'API publique actuelle ; les corriger uniquement si l'exemple utilise une méthode/signature qui n'existe plus.

**`core/TokenTracker.java`** — même traitement : retirer la mention de "Protocol V3 messageId" en tant que concept distinct du modèle de `Scope`/`EntryId` actuel, et clarifier que `hasActiveToken` interroge en réalité l'entrée `LIVENESS` correspondante via `LivenessChecker.assertLive`.

**`core/TokenVerifier.java`** — même traitement, en particulier la liste numérotée du processus de vérification dans le javadoc de `verify(...)` doit refléter les 8 étapes réelles de `EntryVerifier.verifyKeyEpoch` + `verifyCryptographic` (résolution du format, récupération broker, validation structurelle, validation de confiance, validation de capability, validation de version/watermark, validation temporelle, validation de liveness, puis vérification cryptographique du JWT).

### 4.2 Contrainte de cohérence inter-documents

Après modification, vérifier par lecture croisée que `core/TokenRevoker.java`, `core/TokenTracker.java`, `core/TokenVerifier.java` et `docs/security.md` (mis à jour en tâche V4-03 §3.4) ne se contredisent pas sur les délais de propagation annoncés. En cas d'écart, c'est `docs/security.md` qui fait autorité (document de référence) — corriger les javadocs en conséquence, jamais l'inverse.

### 4.3 Critères d'acceptation

- [ ] Recherche `grep -rn "Protocol V3\|MetadataBroker" core/*.java` ne retourne plus aucune occurrence dans les trois fichiers concernés.
- [ ] `mvn -pl veridot-core javadoc:javadoc` (ou équivalent configuré dans le `pom.xml`) se génère sans nouvelle erreur/warning par rapport à l'état avant modification.
- [ ] Aucune signature de méthode publique n'a changé (cette tâche est documentation uniquement).

---

## 5. Tâche V4-05 — Documenter les invariants attendus d'une implémentation `TrustRoot`

**Objectif** : fournir, dans `docs/security.md`, une spécification claire des invariants qu'une implémentation `TrustRoot` (et en particulier `DelegatedTrustRoot`) doit respecter pour que les garanties du protocole tiennent. Cette tâche est documentaire uniquement ; **aucune modification de code de production n'est requise**, sauf l'ajout optionnel décrit en 5.2.

### 5.1 Contenu à ajouter dans `docs/security.md` (nouvelle section "Implementing TrustRoot safely")

Doit couvrir, au minimum, les points suivants, chacun avec une justification d'une à deux phrases reliée au code réel :

1. **`isRootIdentity(issuer)` doit être déterministe et basé sur une source de vérité statique ou fortement contrôlée** (ex. liste figée au déploiement, pas une requête réseau non authentifiée) — toute identité acceptée ici contourne entièrement `CapabilityVerifier.checkCapabilityChain` (retour immédiat à la profondeur 0).
2. **`resolve(issuer)` (pour `PublicKeyTrustRoot`) doit lever une exception plutôt que retourner `null` silencieusement** en cas d'issuer inconnu — `SignatureVerifier.verify` traite déjà une clé `null` comme une erreur `TRUST_RESOLUTION_FAILED`, mais une implémentation qui retournerait une clé par défaut/passe-partout romprait l'isolation entre issuers.
3. **`verifySignature(issuer, data, signature, sigAlg)` (pour `DelegatedTrustRoot`) doit être fail-closed** : toute exception interne (timeout KMS, erreur réseau) doit se traduire par `false`, jamais par `true`, et ne jamais être interceptée silencieusement avant de remonter à `SignatureVerifier` (qui, lui, traite déjà toute exception non `VeridotException` comme un échec — mais une implémentation qui avalerait l'exception et retournerait `true` par défaut contournerait cette protection).
4. **La résolution doit être idempotente pour un même `issuer` pendant la durée de vie du processus**, ou à défaut, toute rotation de clé long-terme côté `TrustRoot` doit être accompagnée d'une procédure de double-validité (ancienne et nouvelle clé acceptées pendant une fenêtre de transition), pour éviter une rupture de service lors d'une rotation — point à documenter même si le protocole actuel ne fournit pas de mécanisme natif de transition (limitation connue à signaler explicitement comme telle, pas à présenter comme résolue).
5. Recommandation explicite : **fournir une implémentation de référence auditée** dans un module séparé (ex. `veridot-trust-jwks`, implémentant `PublicKeyTrustRoot` à partir d'un jeu de clés JWK statique signé), plutôt que de laisser chaque intégrateur réimplémenter `TrustRoot` indépendamment.

### 5.2 Modification de code optionnelle (à exécuter seulement si le temps/périmètre alloué le permet, sans bloquer la clôture de la tâche si non faite)

Ajouter un test de contrat réutilisable (pas une implémentation de production) : `core/TrustRootContractTest.java` (ou `src/test/java/.../core/TrustRootContractTestKit.java`) sous forme de classe abstraite paramétrée par un fournisseur de `TrustRoot`, exposant des méthodes de test génériques (`resolve_unknown_issuer_throws()`, `verifySignature_on_internal_error_returns_false_not_true()`) que toute implémentation future de `TrustRoot` dans le projet (ou ses extensions) pourrait étendre pour valider automatiquement les invariants de 5.1. **Ce test ne doit instancier aucune implémentation réelle** — il reste un kit de contrat sans sujet concret tant qu'aucune implémentation de référence n'existe dans le dépôt.

### 5.3 Critères d'acceptation

- [ ] `docs/security.md` contient la section "Implementing TrustRoot safely" avec les 5 points de §5.1.
- [ ] Si 5.2 est réalisée : le fichier de contrat compile et n'introduit aucune dépendance de test non déjà présente dans `pom.xml`.
- [ ] Aucune régression sur `mvn test`.

---

## 6. Ordre d'exécution et dépendances

```
V4-04 (doc seule, aucune dépendance)
   │
   ├──► V4-05 (doc seule, aucune dépendance, peut être faite en parallèle de V4-04)
   │
V4-02 (code, indépendante des autres — à faire avant V4-01 pour limiter la taille des diffs simultanés sur GenericSignerVerifier.revoke())
   │
   ▼
V4-01 (code — modifie GenericSignerVerifier de façon plus étendue ; faire après V4-02 pour éviter les conflits de merge sur la même classe)
   │
   ▼
V4-03 (code — modifie également GenericSignerVerifier ; à faire en dernier car elle dépend d'un état stable de l'orchestrateur après V4-01/V4-02, et son périmètre étendu en §3.3 touche le câblage de CapabilityVerifier qui est utilisé par les méthodes déjà modifiées en V4-01)
```

Recommandation : traiter les tâches documentaires (V4-04, V4-05) en premier — elles ne comportent aucun risque de régression et permettent de valider l'environnement de build/javadoc avant d'attaquer le code.

## 7. Definition of Done globale

- [ ] Les 5 tâches sont committées séparément, dans l'ordre de §6.
- [ ] `mvn -pl veridot-core -am test` passe sans échec après le dernier commit.
- [ ] Aucune signature publique n'a changé, sauf documentation (javadoc).
- [ ] `docs/security.md` contient les deux nouvelles sections (délais de propagation, invariants `TrustRoot`).
- [ ] Un document `REMEDIATION_VERIDOT_v4.0.0_STATUS.md` est produit en fin d'exécution, listant pour chaque tâche (V4-01 à V4-05) : statut (Fait / Bloqué / Ambiguïté signalée), liste des commits associés, et résultat de la suite de tests après la tâche.
- [ ] Si une tâche n'a pu être terminée (cas d'ambiguïté non résolue, cf. règle 0.7), elle est clairement marquée "Bloqué" dans ce document de statut, avec le contenu du fichier `AMBIGUITY.md` correspondant, plutôt que d'être marquée "Fait" par approximation.
