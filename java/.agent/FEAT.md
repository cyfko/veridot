# FEAT — Token Activity Tracking : `hasActiveToken(target)`

> **Objectif** : Déterminer si des tokens valides (non révoqués, non expirés) existent pour un groupId, un messageId, ou un token donné.
> Rédigé le : 2026-04-10 | Mis à jour : 2026-06-27 | Dépend de : [CONTEXT.md](./CONTEXT.md)

---

## 1. Demande

> **"Déterminer si des tokens valides ont été signés étant donné un groupId, un messageId, ou un token."**

En d'autres termes : étant donné un identifiant (groupId, messageId Protocol V2, ou token signé), est-ce qu'une métadonnée de signature **active** (non révoquée, non expirée) existe dans le broker ?

---

## 2. État des lieux — Ce qui existe

| Capacité | Statut |
|---|---|
| `sign(data, configurer)` — signer avec un groupId/sequenceId | ✅ `GenericSignerVerifier.sign()` |
| `revoke(target)` — révoquer par groupId, messageId, ou token | ✅ `GenericSignerVerifier.revoke()` |
| `verify(token)` — vérifier un token | ✅ `GenericSignerVerifier.verify()` |
| **`hasActiveToken(target)` — existence active** | ✅ `GenericSignerVerifier.hasActiveToken()` (implémenté via `TokenTracker`) |

---

## 3. Analyse de faisabilité

### 3.1 Propriété exploitable : `messageId` Protocol V2

Le `messageId` est construit de manière **déterministe et structurée** :
```
messageId = <version>:<groupId>:<sequenceId>
```
Exemple : `2:user-123:session-A`

Conséquence : à partir d'un `groupId` et d'un `sequenceId`, on peut **reconstruire le `messageId` à tout moment** et interroger le broker. De plus, le broker supporte l'énumération par préfixe (`keys(prefix)`) pour lister toutes les séquences d'un groupe.

### 3.2 Sémantique de `broker.get(messageId)`

| Résultat de `broker.get(messageId)` | Interprétation |
|---|---|
| `BrokerExtractionException` | Jamais signé, ou révoqué (entrée absente) |
| `""` (blank) | Révoqué (entrée vidée) |
| `"<Protocol V2 metadata>"` | Métadonnée présente → vérifier expiration via `timestamp + ttl` |

### 3.3 Format de la métadonnée broker (Protocol V2 — rappel)
```
Format structuré clé-valeur avec les propriétés suivantes :
  mode            : DIRECT ou INDIRECT
  pubkey          : clé publique éphémère RSA-3072 encodée Base64
  timestamp       : timestamp de publication (epoch seconds)
  ttl             : durée de validité en secondes
  signerId        : identifiant du signataire
  announcementSig : signature de l'annonce certifiée (Base64)
  token           : JWT complet (uniquement en mode INDIRECT)
```
Les champs `timestamp` et `ttl` sont suffisants pour déterminer si le token est toujours dans sa fenêtre de validité.

---

## 4. Design de la solution

### 4.1 Contrat dans `veridot-core`

Interface **`TokenTracker`** dans le package `io.github.cyfko.veridot.core` :

```java
package io.github.cyfko.veridot.core;

/**
 * Contract for querying the active signing state of a target.
 *
 * <p>A target can be a groupId, a Protocol V2 messageId, or a signed token.
 * This interface allows any service to determine whether valid (non-revoked,
 * non-expired) tokens exist for a given target, without possessing the
 * token itself.</p>
 *
 * @author Frank KOSSI
 * @since 2.1.0
 */
public interface TokenTracker {

    /**
     * Returns {@code true} if at least one valid token (non-revoked and not yet
     * expired) exists for the given target.
     *
     * <p>The target can be:
     * <ul>
     *   <li>A <strong>groupId</strong> — checks all sequences in the group</li>
     *   <li>A <strong>Protocol V2 messageId</strong> — checks the specific sequence</li>
     *   <li>A <strong>signed token</strong> — extracts the messageId and checks</li>
     * </ul>
     *
     * @param target the groupId, messageId, or token to check
     * @return {@code true} if an active token exists, {@code false} otherwise
     */
    boolean hasActiveToken(String target);
}
```

### 4.2 Implémentation dans `GenericSignerVerifier`

`GenericSignerVerifier` implémente `TokenTracker` en plus de ses autres interfaces.

```java
// GenericSignerVerifier.java
public class GenericSignerVerifier implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker {

    // ... (code existant inchangé) ...

    @Override
    public boolean hasActiveToken(String target) {
        // Résolution polymorphe :
        // - Si JWT (contient ".") → extraire messageId du claim "sub"
        // - Si messageId Protocol V2 (format version:groupId:sequenceId) → vérifier directement
        // - Sinon traiter comme groupId → scanner toutes les séquences du groupe
        // ...
    }
}
```

> **Aucune modification des brokers requise.** La logique repose entièrement sur le comportement déjà existant de `MetadataBroker.get()` et `MetadataBroker.keys()`.

---

## 5. Points de vigilance

| Risque | Détail | Mitigation |
|---|---|---|
| **Délai de propagation Kafka** | Post `sign()`, la métadonnée peut mettre quelques secondes à être consommée dans RocksDB. Un `hasActiveToken()` immédiat peut transitoirement retourner `false`. | `sendLocal` (F5) écrit immédiatement dans RocksDB local, rendant le token vérifiable par le signataire sans attente. |
| **Plusieurs séquences par groupe** | Un même `groupId` peut avoir plusieurs `sequenceId` actifs. `hasActiveToken(groupId)` doit scanner toutes les séquences du groupe. | Implémenté via `broker.keys(prefix)` pour lister toutes les séquences. |
| **Expiration non purgée en DB** | `DatabaseMetadataBroker` ne supprime jamais automatiquement les entrées expirées. L'appel `hasActiveToken()` doit comparer `timestamp + ttl` côté applicatif après `get()`. | Déjà géré dans l'implémentation. Pour Kafka, la compaction RocksDB (F6) purge les entrées expirées automatiquement. |
| **TrustAnchor validation** | `hasActiveToken()` ne nécessite pas de validation TrustAnchor car il ne retourne pas de données sensibles — il vérifie uniquement l'existence et l'expiration. | Comportement attendu : vérification légère. |
| **Thread-safety de `keyPair`** | Le champ `keyPair` est `volatile` pour la visibilité inter-threads lors des rotations. | Correctement géré. |

---

## 6. Tests à ajouter

Les tests doivent être ajoutés dans **`DatabaseTest`** (abstract) et **`KafkaSignerVerifierTest`**, paramétrés sur les deux `DistributionMode`.

| Nom du test | Scénario | Résultat attendu |
|---|---|---|
| `hasActiveToken_after_sign_should_return_true` | `sign()` then `hasActiveToken(groupId)` | `true` |
| `hasActiveToken_for_unknown_group_should_return_false` | `hasActiveToken()` sans `sign()` préalable | `false` |
| `hasActiveToken_after_revoke_by_token_should_return_false` | `sign()` + `revoke(token)` + `hasActiveToken(groupId)` | `false` |
| `hasActiveToken_after_revoke_by_group_should_return_false` | `sign()` + `revoke(groupId)` + `hasActiveToken(groupId)` | `false` |
| `hasActiveToken_after_expiry_should_return_false` | `sign()` validity=1s + sleep(3s) + `hasActiveToken(groupId)` | `false` |

---

## 7. Plan d'implémentation

- [x] Créer `TokenTracker.java` dans `veridot-core/src/main/java/.../core/`
- [x] Faire implémenter `TokenTracker` par `GenericSignerVerifier`
- [x] Ajouter `hasActiveToken(String)` dans `GenericSignerVerifier`
- [ ] Ajouter les 5 cas de test dans `DatabaseTest` (propagation automatique aux 4 sous-classes DB)
- [ ] Ajouter les mêmes tests dans `KafkaSignerVerifierTest`
- [ ] Mettre à jour la Javadoc de `TokenTracker` pour documenter le délai Kafka et le bénéfice de `sendLocal`
