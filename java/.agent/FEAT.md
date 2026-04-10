# FEAT — Tracker Validation : `hasActiveToken(tracker)`

> **Objectif** : Déterminer si des tokens valides (non révoqués, non expirés) ont été signés pour un `tracker` donné.
> Rédigé le : 2026-04-10 | Dépend de : [CONTEXT.md](./CONTEXT.md)

---

## 1. Demande

> **"Déterminer si des tokens valides ont été signés étant donné leur `tracker`."**

En d'autres termes : étant donné un `tracker` (identifiant métier `long`), est-ce qu'une métadonnée de signature **active** (non révoquée, non expirée) existe dans le broker pour ce tracker ?

---

## 2. État des lieux — Ce qui existe

| Capacité | Statut |
|---|---|
| `sign(data, configurer)` — signer avec un tracker | ✅ `GenericSignerVerifier.sign()` |
| `revoke(tracker)` — révoquer par tracker | ✅ `GenericSignerVerifier.revokeByTrackingId()` |
| `verify(token)` — vérifier un token | ✅ `GenericSignerVerifier.verify()` |
| **`hasActiveToken(tracker)` — existence active** | ❌ **ABSENT** |

---

## 3. Analyse de faisabilité

### 3.1 Propriété exploitable : déterminisme du `keyId`

Le `keyId` est calculé de manière **déterministe et irréversible** :
```java
keyId = SHA-256(salt + "-" + tracker)[0..22]   // Base64url sans padding
```

Conséquence : à partir d'un `tracker` et du `salt` (connu du `GenericSignerVerifier`), on peut **recalculer le `keyId` à tout moment** et interroger le broker.

### 3.2 Sémantique de `broker.get(keyId)`

| Résultat de `broker.get(keyId)` | Interprétation |
|---|---|
| `BrokerExtractionException` | Jamais signé, ou révoqué (entrée absente) |
| `""` (blank) | Révoqué (entrée vidée) |
| `"<mode>:<pubKey>:<expiryMillis>:..."` | Métadonnée présente → vérifier `expiryMillis` |

### 3.3 Format de la métadonnée broker (rappel)
```
<mode>:<pubKeyBase64>:<expiryMillis>:[<jwtToken si mode=id>]
  [0]       [1]            [2]                 [3]
```
Le champ `[2]` (`expiryMillis`) est un timestamp en **millisecondes** epoch UTC. Il est suffisant pour déterminer si le token est toujours dans sa fenêtre de validité.

---

## 4. Design de la solution

### 4.1 Nouveau contrat dans `veridot-core`

Créer l'interface **`TokenTracker`** dans le package `io.github.cyfko.veridot.core` :

```java
package io.github.cyfko.veridot.core;

/**
 * Contract for querying the active signing state of a tracker.
 *
 * <p>A tracker is a caller-supplied long identifier that links a signed token
 * to a business entity. This interface allows any service to determine whether
 * a valid (non-revoked, non-expired) token has been issued for a given tracker,
 * without possessing the token itself.</p>
 *
 * @author Frank KOSSI
 * @since 2.1.0
 */
public interface TokenTracker {

    /**
     * Returns {@code true} if a valid token (non-revoked and not yet expired)
     * has been signed for the given tracker.
     *
     * @param tracker the tracking identifier supplied at signing time
     * @return {@code true} if an active token exists for this tracker, {@code false} otherwise
     */
    boolean hasActiveToken(long tracker);
}
```

### 4.2 Implémentation dans `GenericSignerVerifier`

`GenericSignerVerifier` doit implémenter `TokenTracker` en plus de ses interfaces actuelles.

```java
// GenericSignerVerifier.java — ajout d'implémentation
public class GenericSignerVerifier implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker {

    // ... (code existant inchangé) ...

    @Override
    public boolean hasActiveToken(long tracker) {
        try {
            String keyId = generateId(tracker, generatedIdSalt);
            String message = metadataBroker.get(keyId);
            if (message == null || message.isBlank()) {
                return false; // révoqué (entrée vidée)
            }
            String[] parts = message.split(":");
            long expiryMillis = Long.parseLong(parts[2]);
            return System.currentTimeMillis() < expiryMillis;
        } catch (BrokerExtractionException e) {
            return false; // absent → aucun token actif
        } catch (Exception e) {
            logger.severe("Error checking active token for tracker " + tracker + ": " + e.getMessage());
            return false;
        }
    }
}
```

> **Aucune modification des brokers requise.** La logique repose entièrement sur le comportement déjà existant de `MetadataBroker.get()`.

---

## 5. Points de vigilance

| Risque | Détail | Mitigation |
|---|---|---|
| **Délai de propagation Kafka** | Post `sign()`, la métadonnée peut mettre quelques secondes à être consommée dans RocksDB. Un `hasActiveToken()` immédiat peut transitoirement retourner `false`. | Documenter ce comportement dans la Javadoc. |
| **Un seul `keyId` par tracker** | Deux `sign()` successifs avec le même `tracker` écrasent la même entrée (upsert). Seule la dernière métadonnée est prise en compte. | Comportement cohérent avec la révocation existante, pas de changement nécessaire. |
| **Expiration non purgée en DB** | `DatabaseMetadataBroker` ne supprime jamais automatiquement les entrées expirées. L'appel `hasActiveToken()` doit comparer `expiryMillis` côté applicatif après `get()`. | Déjà géré dans l'implémentation proposée (§4.2). |
| **Thread-safety de `keyPair`** | Le champ `keyPair` est muté par le scheduler sans `volatile`. (Pré-existant, hors scope.) | Signaler comme dette technique séparée. |
| **Cas `message.isBlank()` vs exception** | Les deux signifient "absent/révoqué". Les deux doivent retourner `false`. | Déjà pris en compte dans l'implémentation proposée. |

---

## 6. Tests à ajouter

Les tests doivent être ajoutés dans **`DatabaseTest`** (abstract) et **`KafkaSignerVerifierTest`**, paramétrés sur les deux `TokenMode`.

| Nom du test | Scénario | Résultat attendu |
|---|---|---|
| `hasActiveToken_after_sign_should_return_true` | `sign()` then `hasActiveToken(tracker)` | `true` |
| `hasActiveToken_for_unknown_tracker_should_return_false` | `hasActiveToken()` sans `sign()` préalable | `false` |
| `hasActiveToken_after_revoke_by_token_should_return_false` | `sign()` + `revoke(token)` + `hasActiveToken(tracker)` | `false` |
| `hasActiveToken_after_revoke_by_tracker_should_return_false` | `sign()` + `revoke(tracker)` + `hasActiveToken(tracker)` | `false` |
| `hasActiveToken_after_expiry_should_return_false` | `sign()` validity=1s + sleep(3s) + `hasActiveToken(tracker)` | `false` |

---

## 7. Plan d'implémentation

- [ ] Créer `TokenTracker.java` dans `veridot-core/src/main/java/.../core/`
- [ ] Faire implémenter `TokenTracker` par `GenericSignerVerifier`
- [ ] Ajouter `hasActiveToken()` dans `GenericSignerVerifier`
- [ ] Ajouter les 5 cas de test dans `DatabaseTest` (propagation automatique aux 4 sous-classes DB)
- [ ] Ajouter les même tests dans `KafkaSignerVerifierTest`
- [ ] Mettre à jour la Javadoc de `TokenTracker` pour documenter le délai Kafka
