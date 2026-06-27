# Veridot — Plan de patch F9 : Authentification de la configuration distribuée

**Projet :** [cyfko/veridot](https://github.com/cyfko/veridot)
**Module concerné :** `veridot-core`
**Type :** Correctif de sécurité architecturale (continuité des fixes F1/F5/F7/F8 déjà présents dans le code)
**Cible de version :** `veridot-core 3.1.0` — changement de comportement de sécurité, à documenter `[SECURITY]` au changelog

---

## 0. Contexte — pourquoi ce patch

Veridot v3.0 a introduit un modèle de confiance à deux niveaux (`TrustAnchor`) pour empêcher qu'un attaquant ayant un accès **écriture** au broker (Kafka, base de données, etc.) puisse forger des annonces de clés éphémères ou des révocations. Le principe central, documenté dans `TrustAnchor.java` et appliqué dans `GenericSignerVerifier` :

> *« Le broker (Kafka, base de données, etc.) est un transport uniquement. Une partie qui peut écrire sur le broker ne doit pas automatiquement devenir digne de confiance. »*

Ce principe est correctement appliqué :
- aux **annonces de clé éphémère** (`validateTrustAnchor()`, signature `sid`+`sig` vérifiée via `TrustAnchor` avant toute confiance dans la clé publique reçue) ;
- aux **tombstones de révocation** (`buildSignedRevocationMessage()`, signature long-terme, règle « dernier timestamp gagne »).

**Mais il n'est PAS appliqué à un troisième canal qui existe dans le même fichier** : la configuration opérationnelle distribuée (`__CONFIG__` local / site / global — `maxSessions`, `EvictionPolicy`, `defaultTTL`), lue par `resolveFromBroker()` → `tryParseConfig()`. Ces messages sont lus et appliqués **sans aucune vérification de signature**.

### Impact concret

Un attaquant disposant d'un accès écriture au broker — exactement le threat model que le fix F1 a été conçu pour neutraliser — peut publier, sans aucune signature :

```
3:victim-group:__CONFIG__|max:<b64(1)>,pol:<b64(REJECT)>,ts:<b64(...)>,exp:<b64(...)>
```

Résultat : **déni de service silencieux** sur `victim-group` — plus aucun token ne peut être signé pour ce groupe — sans jamais avoir besoin de casser la moindre primitive cryptographique. C'est la même classe de vulnérabilité que F1, simplement réintroduite sur un canal différent du même composant.

### Objectif du patch F9

Appliquer à `__CONFIG__` exactement le même mécanisme d'authenticité que celui déjà en place pour les annonces de clé et les tombstones : signature long-terme vérifiée via `TrustAnchor`, message non signé ou signature invalide → traité comme **absent** (et non comme une erreur bloquante), avec cascade vers le niveau de priorité suivant (local → site → global → défaut constructeur).

### Hors périmètre de ce patch

L'**autorisation de scope** (un `sid` valide a-t-il le droit de publier une config pour *ce* groupe / site / global ?) est un problème distinct, déjà documenté comme délégué à l'implémenteur pour les `groupId` des tokens. Ce patch prépare un point d'extension pour ce problème mais ne le rend pas obligatoire (voir §2 et §8), afin de rester strictement additif et rétrocompatible côté implémenteurs existants de `TrustAnchor`.

---

## 1. Refactor préalable — extraire la logique commune sign/verify

**Constat :** le pattern « construire les props → canonicaliser → signer avec la clé long-terme / vérifier via `TrustAnchor` » est aujourd'hui dupliqué entre :
- `sign()` (annonce de clé éphémère, lignes ~336–366 de `GenericSignerVerifier.java`)
- `buildSignedRevocationMessage()` (tombstone)
- `validateTrustAnchor()` (vérification d'annonce)
- `validateNotRevoked()` (vérification de tombstone)

Avant d'ajouter une 3ᵉ variante (config), on factorise dans un nouveau fichier package-private.

### Nouveau fichier : `io/github/cyfko/veridot/core/impl/TrustedAnnouncement.java`

```java
package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.TrustAnchor;
import io.github.cyfko.veridot.core.exceptions.TrustResolutionException;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Logique commune de signature/vérification long-terme pour toute annonce
 * Protocol V3 (clé éphémère, tombstone de révocation, configuration).
 *
 * <p>Centralise le pattern déjà appliqué par sign() et buildSignedRevocationMessage()
 * afin qu'un seul point du code implémente "comment on signe/vérifie une annonce".</p>
 *
 * @since 3.1.0 (F9)
 */
final class TrustedAnnouncement {

    private TrustedAnnouncement() {}

    /** Signe les props (hors sig/token) avec la clé long-terme. Retourne la signature en b64url. */
    static String sign(String messageId, Map<String, String> props, PrivateKey longTermKey) {
        byte[] canonical = ProtocolV2.buildCanonicalBytes(messageId, props);
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(longTermKey);
            sig.update(canonical);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new RuntimeException(
                    "SECURITY: cannot sign announcement — refusing to publish unsigned", e);
        }
    }

    /**
     * Vérifie sid+sig d'une annonce via le TrustAnchor.
     *
     * @throws TrustResolutionException.SignatureRejected si sid/sig absents, signature invalide,
     *         ou sid inconnu/révoqué
     * @throws TrustResolutionException.Unavailable si le trust anchor est temporairement
     *         inaccessible (l'appelant décide comment réagir — voir §5 du plan F9)
     */
    static void verify(String messageId, Map<String, String> meta, TrustAnchor anchor)
            throws TrustResolutionException {
        String sid = meta.get(ProtocolV2.PROP_SID);
        String sigB64 = meta.get(ProtocolV2.PROP_SIG);
        if (sid == null || sigB64 == null) {
            throw new TrustResolutionException.SignatureRejected("Missing sid/sig in announcement");
        }

        Map<String, String> props = new LinkedHashMap<>(meta);
        props.remove(ProtocolV2.PROP_SIG);
        props.remove(ProtocolV2.PROP_TOKEN);
        byte[] canonical = ProtocolV2.buildCanonicalBytes(messageId, props);
        byte[] sig = Base64.getUrlDecoder().decode(sigB64);

        switch (anchor) {
            case TrustAnchor.PublicKeyResolver r -> {
                PublicKey ltKey = r.resolve(sid);
                verifySignature(canonical, sig, ltKey);
            }
            case TrustAnchor.DelegatedVerifier d -> d.verify(sid, canonical, sig);
        }
    }

    private static void verifySignature(byte[] canonical, byte[] signature, PublicKey ltKey)
            throws TrustResolutionException.SignatureRejected {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(ltKey);
            sig.update(canonical);
            if (!sig.verify(signature)) {
                throw new TrustResolutionException.SignatureRejected("Signature verification failed");
            }
        } catch (TrustResolutionException.SignatureRejected e) {
            throw e;
        } catch (Exception e) {
            throw new TrustResolutionException.SignatureRejected(
                    "Signature verification error: " + e.getMessage());
        }
    }
}
```

### Modifications dans `GenericSignerVerifier.java`

- `sign()` : remplacer le bloc « canonicaliser + `signCanonical()` » (étape 6) par `TrustedAnnouncement.sign(messageId, props, longTermPrivateKey)`.
- `buildSignedRevocationMessage()` : idem.
- `validateTrustAnchor()` : remplacer le corps par un appel à `TrustedAnnouncement.verify(messageId, propsFromMeta, trustAnchor)`, conserver le traitement des exceptions (`Unavailable` → log + `BrokerExtractionException`, `SignatureRejected` → log SEVERE + `BrokerExtractionException`).
- `validateNotRevoked()` : idem pour la partie vérification de tombstone.
- Supprimer les méthodes privées `signCanonical()` et `verifySignature()` devenues mortes (déplacées dans `TrustedAnnouncement`).

**Aucun changement de comportement à cette étape** — c'est un refactor pur, à valider par la suite de tests existante (`TrustAnchorSecurityTest`, `RevocationTest`, `SigningTest`, `VerificationTest`) qui doit passer sans modification.

---

## 2. Extension de `TrustAnchor` — point d'extension pour l'autorisation de scope

Ajout d'une méthode `default` sur l'interface scellée (ne casse pas le scellement : aucun nouveau sous-type n'est ajouté à la liste `permits`).

### Modification de `TrustAnchor.java`

```java
public sealed interface TrustAnchor
        permits TrustAnchor.PublicKeyResolver, TrustAnchor.DelegatedVerifier {

    // ... méthodes existantes inchangées (resolve / verify) ...

    /**
     * Vérifie que l'identité {@code sid} est autorisée à publier une configuration
     * pour le scope désigné par {@code scopeKey} (ex: {@code "3:user-123:__CONFIG__"},
     * {@code "3:__CONFIG__:eu-west"}, {@code "3:__CONFIG__:__ALL__"}).
     *
     * <p><strong>Comportement par défaut : permissif</strong> (retourne {@code true}),
     * afin de rester rétrocompatible avec les implémentations de {@code TrustAnchor}
     * écrites avant la version 3.1.0.</p>
     *
     * <p><strong>AVERTISSEMENT :</strong> sans surcharge de cette méthode en production,
     * toute identité signataire dont la clé long-terme est résolue par
     * {@link PublicKeyResolver#resolve} ou validée par {@link DelegatedVerifier#verify}
     * peut modifier la politique de session (limite de sessions, politique d'éviction)
     * de n'importe quel groupe, site, ou de la configuration globale. Ce comportement
     * permissif par défaut authentifie l'<em>origine</em> de la configuration mais pas
     * son <em>périmètre d'autorité</em>.</p>
     *
     * @param sid      l'identité du signataire de la configuration, déjà authentifiée
     *                 (signature vérifiée) au moment de l'appel
     * @param scopeKey la clé Protocol V3 du scope de configuration ciblé
     * @return {@code true} si {@code sid} est autorisé à publier pour ce scope
     * @since 3.1.0
     */
    default boolean isAuthorizedForScope(String sid, String scopeKey) {
        return true; // permissif par défaut — voir avertissement ci-dessus
    }
}
```

---

## 3. Nouveau format de message `__CONFIG__`

Ajout des propriétés `sid` et `sig` (déjà définies dans `ProtocolV2` : `PROP_SID`, `PROP_SIG`, réutilisées telles quelles) aux propriétés existantes (`max`, `pol`, `dttl`, `ts`, `exp`).

```
3:user-123:__CONFIG__|max:<b64>,pol:<b64>,dttl:<b64>,ts:<b64>,exp:<b64>,sid:<b64>,sig:<b64>
```

**Pas de changement de `PROTOCOL_VERSION`** (reste `3`) : l'ajout est additif et un ancien lecteur qui ignorerait `sid`/`sig` resterait fonctionnellement inchangé. Seul le comportement de **validation côté bibliothèque** change, ce qui justifie malgré tout un bump mineur de version de librairie (`3.0.x` → `3.1.0`) et une entrée `[SECURITY]` au changelog (cf. §6).

### Modification de `ProtocolV2.java`

Ajouter un overload de `buildMessage` acceptant directement une clé déjà composée (pour éviter de re-splitter `groupId`/`sequenceId` quand l'appelant a déjà la clé, ce qui sera le cas pour `buildGlobalConfigKey()` qui n'a pas de vrai `groupId` métier) :

```java
/**
 * Variante de {@link #buildMessage(String, String, Map)} acceptant un messageId
 * déjà composé (utile pour les clés réservées comme les clés de configuration).
 */
static String buildMessage(String messageId, Map<String, String> properties) {
    String metadata = properties.entrySet().stream()
            .map(e -> e.getKey() + FIELD_SEP + base64UrlEncode(e.getValue()))
            .collect(Collectors.joining(PROP_SEP));
    return messageId + META_SEP + metadata;
}
```

Aucun autre changement requis dans `ProtocolV2.java` (les helpers `buildLocalConfigKey`, `buildSiteConfigKey`, `buildGlobalConfigKey` existent déjà et restent inchangés).

---

## 4. API de publication — `publishConfig(...)`

**Constat important :** il n'existe aujourd'hui **aucune API publique** pour publier une configuration — les messages `__CONFIG__` sont écrits hors bibliothèque (outillage d'administration externe), ce qui explique en partie l'absence de signature. Ce patch introduit la première API officielle, signée par construction.

### Ajout dans `GenericSignerVerifier.java`

```java
/** Portée d'une configuration distribuée (§4 du protocole). */
public enum ConfigScope { LOCAL, SITE, GLOBAL }

/**
 * Publie une configuration signée pour le scope désigné.
 *
 * <p>La configuration est signée avec la clé long-terme de ce signataire et sera
 * validée par {@link TrustAnchor} chez tout vérificateur qui la lira. Un message
 * de configuration non signé ou dont la signature est invalide est ignoré par les
 * vérificateurs (§5 du plan F9) — il ne provoque jamais d'erreur bloquante côté lecture.</p>
 *
 * @param scope             LOCAL (par groupe), SITE, ou GLOBAL
 * @param scopeId           groupId si LOCAL, siteId si SITE, ignoré si GLOBAL
 * @param maxSessions       limite de sessions concurrentes, ou {@code -1} pour aucune limite
 * @param policy            politique d'éviction appliquée au-delà de {@code maxSessions}
 * @param defaultTtlSeconds TTL par défaut appliqué aux signatures de ce scope
 * @param validitySeconds   durée de validité de cette configuration elle-même
 *                          (passé ce délai, {@code tryParseConfig} l'ignore — §4.2.4
 *                          déjà spécifié, réutilisé tel quel)
 * @throws IllegalArgumentException si les paramètres sont invalides
 * @throws RuntimeException si la publication échoue (timeout broker, erreur de signature)
 * @since 3.1.0
 */
public void publishConfig(ConfigScope scope, String scopeId,
                           int maxSessions, EvictionPolicy policy,
                           long defaultTtlSeconds, long validitySeconds) {
    if (scope == null) throw new IllegalArgumentException("scope cannot be null");
    if (scope != ConfigScope.GLOBAL && (scopeId == null || scopeId.isBlank())) {
        throw new IllegalArgumentException("scopeId is required for scope=" + scope);
    }
    if (policy == null) throw new IllegalArgumentException("policy cannot be null");
    if (validitySeconds <= 0) throw new IllegalArgumentException("validitySeconds must be positive");

    String key = switch (scope) {
        case LOCAL  -> ProtocolV2.buildLocalConfigKey(scopeId);
        case SITE   -> ProtocolV2.buildSiteConfigKey(scopeId);
        case GLOBAL -> ProtocolV2.buildGlobalConfigKey();
    };

    long now = Instant.now().getEpochSecond();
    Map<String, String> props = new LinkedHashMap<>();
    props.put(ProtocolV2.PROP_MAX, String.valueOf(maxSessions));
    props.put(ProtocolV2.PROP_POL, policy.name());
    props.put(ProtocolV2.PROP_DTTL, String.valueOf(defaultTtlSeconds));
    props.put(ProtocolV2.PROP_TS, String.valueOf(now));
    props.put(ProtocolV2.PROP_EXP, String.valueOf(now + validitySeconds));
    props.put(ProtocolV2.PROP_SID, signerId);

    String sigB64 = TrustedAnnouncement.sign(key, props, longTermPrivateKey);
    props.put(ProtocolV2.PROP_SIG, sigB64);

    String message = ProtocolV2.buildMessage(key, props);
    try {
        metadataBroker.send(key, message).get(30, TimeUnit.SECONDS);
    } catch (Exception e) {
        logger.severe("Failed to publish config at " + key + ": " + e.getMessage());
        throw new RuntimeException("Config publication failed", e);
    }

    // Invalider le cache local pour que la nouvelle config soit reprise immédiatement
    configCache.remove(scope == ConfigScope.LOCAL ? scopeId : key);
}
```

> Note d'implémentation : vérifier l'invalidation exacte du cache — `configCache` est aujourd'hui indexé par `groupId`, pas par clé de scope complète. À ajuster selon la structure réelle de `CachedConfig`/`configCache` au moment du patch (site/global ne sont pas actuellement cachés par clé distincte — voir si une mini-refonte du cache est nécessaire pour invalider correctement les 3 niveaux).

---

## 5. Côté lecture — durcissement de `tryParseConfig`

### Modification dans `GenericSignerVerifier.java`

```java
private EffectiveConfig tryParseConfig(String configKey) {
    try {
        String msg = metadataBroker.get(configKey);
        if (msg == null || msg.isBlank()) return null;
        Map<String, String> meta = ProtocolV2.parseMetadata(msg);

        // F9 — authenticité avant toute confiance dans le contenu de la config
        try {
            TrustedAnnouncement.verify(configKey, meta, trustAnchor);
        } catch (TrustResolutionException.SignatureRejected e) {
            logger.warning("F9: Rejected unsigned/forged config at " + configKey
                    + " — falling back to next priority level: " + e.getMessage());
            return null;
        } catch (TrustResolutionException.Unavailable e) {
            // Choix délibéré (voir justification ci-dessous) : ne PAS bloquer sign().
            logger.warning("F9: TrustAnchor unavailable for config at " + configKey
                    + ", falling back to next priority level: " + e.getMessage());
            return null;
        }

        // Point d'extension §2 — désactivé tant que isAuthorizedForScope n'est pas
        // surchargé (comportement par défaut permissif documenté dans TrustAnchor)
        String sid = meta.get(ProtocolV2.PROP_SID);
        if (!trustAnchor.isAuthorizedForScope(sid, configKey)) {
            logger.severe("SECURITY: sid=" + sid + " not authorized for config scope " + configKey);
            return null;
        }

        // ── reste du parsing inchangé ────────────────────────────────────────
        String tsStr = meta.get(ProtocolV2.PROP_TS);
        String vuStr = meta.get(ProtocolV2.PROP_EXP);
        if (tsStr == null || vuStr == null) return null;
        long validUntil = Long.parseLong(vuStr);
        if (Instant.now().getEpochSecond() > validUntil) return null;

        int ms = defaultConfig.maxSessions();
        if (meta.containsKey(ProtocolV2.PROP_MAX)) {
            int brokerMs = Integer.parseInt(meta.get(ProtocolV2.PROP_MAX));
            if (brokerMs == -1 || brokerMs > 0) ms = brokerMs;
            else logger.warning("Ignoring invalid broker maxSessions=" + brokerMs);
        }
        EvictionPolicy pol = meta.containsKey(ProtocolV2.PROP_POL)
                ? EvictionPolicy.valueOf(meta.get(ProtocolV2.PROP_POL))
                : defaultConfig.policy();
        long dttl = defaultConfig.defaultTTL();
        if (meta.containsKey(ProtocolV2.PROP_DTTL)) {
            long brokerTtl = Long.parseLong(meta.get(ProtocolV2.PROP_DTTL));
            if (brokerTtl > 0) dttl = brokerTtl;
            else logger.warning("Ignoring invalid broker defaultTTL=" + brokerTtl);
        }

        return new EffectiveConfig(ms, pol, dttl);
    } catch (Exception e) {
        return null; // malformé → ignoré, comportement inchangé
    }
}
```

### Justification de l'asymétrie `Unavailable` (à documenter en commentaire dans le code)

Ce comportement diffère **volontairement** de `validateTrustAnchor()` pour les tokens :

| Canal | `TrustResolutionException.Unavailable` |
|---|---|
| Vérification de **token** | Bloque (`BrokerExtractionException`) — laisser passer un token non vérifié serait catastrophique |
| Vérification de **config** | Dégrade vers le niveau de priorité suivant (local→site→global→défaut) — ne bloque jamais `sign()` |

Si l'indisponibilité du `TrustAnchor` bloquait aussi la lecture de config, il suffirait à un attaquant de rendre le KMS/Vault temporairement inaccessible pour empêcher toute émission de token via un canal non critique — un nouveau vecteur de déni de service serait introduit par le correctif lui-même. Ce commentaire doit être inscrit explicitement dans le code pour qu'un futur audit ne confonde pas ce choix avec un oubli.

---

## 6. Migration et compatibilité

- **Comportement après mise à jour :** tout message `__CONFIG__` existant sans `sid`/`sig` est désormais **ignoré silencieusement** → la résolution retombe sur le niveau de priorité suivant, jusqu'au défaut du constructeur (`EffectiveConfig(maxSessions=-1, FIFO, -1)` si rien n'est configuré explicitement).
- **Conséquence opérationnelle :** une limite de sessions en place *avant* la mise à jour **disparaît silencieusement** si elle n'est pas republiée avec la nouvelle API. C'est un changement de comportement notable, à documenter en gros caractères :
  - Entrée de CHANGELOG `[SECURITY] [BREAKING BEHAVIOR]` explicite.
  - Section dédiée dans le guide de migration : *« Toute configuration `__CONFIG__` publiée avant 3.1.0 doit être republiée via `publishConfig(...)` après la mise à jour, sous peine de perdre silencieusement les limites de session en vigueur. »*
  - Optionnel : log `WARNING` au démarrage de `GenericSignerVerifier` si une clé `__CONFIG__` existe dans le broker pour un scope donné mais échoue la validation de signature (signal explicite plutôt que silence total) — à évaluer en fonction du bruit de log généré en環境 multi-tenant.
- **Pas de bump de `PROTOCOL_VERSION`** (reste `3`), uniquement bump de version de bibliothèque.

---

## 7. Plan de tests

Nouveau fichier : `src/test/java/io/github/cyfko/veridot/core/impl/ConfigTrustSecurityTest.java`, en miroir de `TrustAnchorSecurityTest.java` existant.

| Test | Comportement attendu |
|---|---|
| `forged_config_without_signature_is_ignored` | `tryParseConfig` retourne `null` ; `sign()` utilise le niveau de priorité suivant ou le défaut |
| `forged_config_with_random_signature_is_ignored` | idem |
| `config_with_unavailable_trust_anchor_falls_back_without_blocking_sign` | aucune exception propagée à `sign()` ; config par défaut appliquée |
| `valid_signed_local_config_overrides_site_and_global` | la priorité local > site > global est respectée une fois la signature valide |
| `expired_signed_config_is_ignored_even_with_valid_signature` | `exp` dépassé → `null` malgré une signature correcte |
| `rogue_broker_writer_cannot_lower_max_sessions` *(intégration)* | un message `__CONFIG__` non signé écrit directement dans `InMemoryMetadataBroker` (simulateur d'attaquant côté broker) n'affecte pas `maxSessions` effectif |
| `isAuthorizedForScope_default_is_permissive_and_documented` | garde-fou anti-régression : échoue si le comportement par défaut change sans mise à jour du Javadoc associé |
| `publishConfig_then_verify_round_trip` | publication via la nouvelle API puis lecture → valeurs identiques, signature validée |

**Test d'intégration côté `veridot-kafka` (module séparé) :** un producteur Kafka brut (hors bibliothèque) injecte un enregistrement non signé sur le topic de configuration ; vérifier que le consommateur RocksDB + `GenericSignerVerifier` ignore ce message et conserve la configuration précédente ou le défaut.

**Non-régression :** la suite existante (`TrustAnchorSecurityTest`, `RevocationTest`, `SigningTest`, `VerificationTest`, `SessionCapacityTest`) doit passer sans modification après le refactor de l'étape 1, et avec un comportement inchangé pour tout ce qui ne touche pas `__CONFIG__`.

---

## 8. Risques résiduels après ce patch

1. **`isAuthorizedForScope` reste permissif par défaut.** Ce patch authentifie l'*origine* de la configuration (qui l'a signée), pas son *périmètre d'autorité* (a-t-il le droit de la signer pour ce scope précis). Un opérateur qui ne surcharge pas cette méthode reste exposé à une identité légitime mais mal scopée qui modifierait une configuration hors de son périmètre prévu. Un futur **F10** pourrait :
   - rendre la méthode abstraite plutôt que `default` (breaking change majeur, à réserver à une version `4.0.0`) ;
   - ou émettre un `WARNING` une seule fois au démarrage si l'implémentation effective de `TrustAnchor` n'a pas surchargé `isAuthorizedForScope` (détectable par réflexion ou par un flag explicite), pour rendre le risque visible sans casser la compatibilité.
2. **La clé long-terme reste le secret racine unique** de tout l'édifice : annonces de clé, tombstones, et désormais configuration sont tous signés par la même clé long-terme par signataire. Aucune rotation n'est prévue dans la bibliothèque pour cette clé (elle est fournie par le `TrustAnchor`, donc hors scope direct) — à documenter comme limite connue dans le guide de sécurité (`docs/security`).
3. **Cache de configuration (`configCache`)** : à vérifier précisément lors de l'implémentation — l'invalidation après `publishConfig()` doit couvrir les trois niveaux (local/site/global), pas seulement le `groupId` courant, sous peine de fenêtre de staleness jusqu'à expiration du TTL de cache (`CONFIG_CACHE_TTL_SECONDS = 60`).

---

## 9. Checklist d'implémentation (ordre recommandé)

- [ ] Créer `TrustedAnnouncement.java` (§1) avec tests unitaires propres avant tout branchement
- [ ] Refactorer `sign()`, `buildSignedRevocationMessage()`, `validateTrustAnchor()`, `validateNotRevoked()` pour utiliser `TrustedAnnouncement` — vérifier que la suite de tests existante passe inchangée
- [ ] Ajouter `isAuthorizedForScope` (défaut permissif) à `TrustAnchor.java` (§2)
- [ ] Ajouter `ProtocolV2.buildMessage(String messageId, Map props)` (§3)
- [ ] Implémenter `ConfigScope` + `publishConfig(...)` dans `GenericSignerVerifier.java` (§4)
- [ ] Durcir `tryParseConfig(...)` avec `TrustedAnnouncement.verify` + commentaire de justification de l'asymétrie `Unavailable` (§5)
- [ ] Résoudre le point d'invalidation de cache multi-niveaux (§8.3)
- [ ] Écrire `ConfigTrustSecurityTest.java` (§7)
- [ ] Test d'intégration `veridot-kafka` avec producteur Kafka brut (§7)
- [ ] Rédiger l'entrée CHANGELOG `[SECURITY] [BREAKING BEHAVIOR]` + section migration dans la doc (§6)
- [ ] Publier `veridot-core 3.1.0`

---

*Document généré pour servir de spécification d'implémentation autonome, exploitable avec n'importe quel assistant IA ou directement par un développeur, sans dépendance au reste de la conversation d'origine.*
