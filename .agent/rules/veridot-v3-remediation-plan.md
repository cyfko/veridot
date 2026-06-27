---
title: Veridot v3.0 — Plan de remédiation (rupture de confiance broker & défauts architecturaux)
status: draft for review
date: 2026-06-27
scope: java/veridot-core, java/veridot-kafka
out of scope: dverify (Node.js) — non maintenu par l'auteur, exclu de ce plan
---

# Veridot v3.0 — Plan de remédiation

## 0. Posture du plan

Pas de version de transition, pas de mode de compatibilité. v3.0 livre directement la correction
du défaut le plus grave (le broker comme racine de confiance implicite) avec toutes les
ruptures d'API que ça implique. On assume le breaking change plutôt que de le diluer sur
plusieurs mineures — la dette d'avoir deux comportements (legacy / sécurisé) coexistants dans la
même base de code serait elle-même un risque de sécurité (mauvaise configuration possible en
prod = retour silencieux au modèle vulnérable). Une seule vérité de comportement, imposée par le
compilateur partout où c'est possible.

Le fil conducteur de tout ce document : **le broker est un transport, jamais une autorité.**
Chaque constat ci-dessous est une instance particulière où le code actuel viole ce principe.

## 1. Synthèse des constats

| ID | Constat | Sévérité | Essence du problème |
|----|---------|----------|----------------------|
| F1 | Le broker fait office de racine de confiance | **Critique** | Authentification absente |
| F7 | Révocation/annonce rejouable (un attaquant "ressuscite" une session révoquée) | **Critique** | Conséquence directe de F1 |
| F4 | Révocation à cohérence éventuelle, fenêtre non bornée | Élevée | Latence de propagation |
| F6 | Stockage local non borné (pas de purge TTL) | Élevée | Fuite par omission |
| F2 | `salt` mort, API trompeuse sur ses propres garanties | Élevée | Dette de signature d'API |
| F8 | `.get(3min)` bloquant sur la voie chaude de `sign()` | Moyenne | Confusion sync/async |
| F5 | Race sign→verify entre nœuds (read-after-write) | Moyenne | Cohérence distribuée |
| F3 | Taille de clé RSA non explicitée (doc dit 2048, code ne l'impose pas) | Moyenne | Doc/code désynchronisés |

---

## 2. F1 + F7 — Ancrer la confiance hors du broker

### Pourquoi c'est le cœur du plan

Tous les autres défauts sont des bugs de robustesse ou de propreté. F1 est un **bug de modèle de
menace** : il rend Veridot incapable de tenir sa propre promesse ("n'importe quel service peut
vérifier n'importe quel token") dès que le canal de distribution des clés (le broker) n'est plus
parfaitement étanche. Or un topic Kafka partagé entre plusieurs équipes, ou une base relationnelle
accessible par plus de services que nécessaire, c'est l'état normal d'un système qui grossit — pas
un scénario exotique. Corriger F1, c'est rendre Veridot vrai même quand l'infra autour de lui ne
l'est pas totalement, ce qui est précisément ce qu'on attend d'une brique de sécurité.

### Flux de confiance, avant/après

```
AVANT
  Signataire --(clé publique en clair)--> Broker --(clé publique en clair)--> Vérificateur
                                              ^
                                  un attaquant qui écrit ici devient
                                  indistinguable du vrai signataire

APRÈS
  Identité long-terme du signataire (hors broker, KMS/HSM)
         |
         | certifie (signe) l'annonce de clé éphémère
         v
  Signataire --(annonce certifiée)--> Broker --(annonce certifiée)--> Vérificateur
                                                                          |
                                                                          v
                                                          TrustAnchor vérifie la certification
                                                          AVANT de faire confiance à la clé éphémère
```

Le broker reste exactement ce qu'il a toujours dû être : un tuyau. Compromettre le tuyau ne donne
plus accès à l'autorité — il faudrait en plus compromettre l'identité long-terme, qui vit ailleurs,
sous une garde différente (KMS/HSM, rotation lente, surface d'attaque réduite).

### Interfaces

Renommage assumé : `IdentityProbe` évoquait une vérification passive et ponctuelle ("sonder").
Ce qu'on construit est plus fort — une autorité que le code interroge pour fonder sa confiance.
`TrustAnchor` reprend le vocabulaire standard de la PKI (RFC 5280 : un trust anchor est exactement
"une autorité dont la légitimité est admise a priori, hors de toute chaîne à vérifier"), ce qui
décrit fidèlement le rôle de l'objet. De même, `PublicKeyRetrieval` ("retrieval" = une simple
opération de lecture) sous-représentait ce que fait l'implémentation : elle ne lit pas une donnée
neutre, elle **résout** une identité en une clé de confiance — `PublicKeyResolver` porte ce sens
actif. `VerificationDelegate` était déjà correct sémantiquement mais entrait en collision
conceptuelle avec `JwtVerifier` existant ; `DelegatedVerifier` clarifie que la vérification est
**déléguée hors processus** (KMS/HSM), pas une simple variante locale.

```java
public sealed interface TrustAnchor
        permits TrustAnchor.PublicKeyResolver, TrustAnchor.DelegatedVerifier {

    /** Résout l'identité {@code signerId} en la clé publique de confiance qui lui est associée.
     *  La vérification cryptographique de la signature reste à la charge de l'appelant. */
    non-sealed interface PublicKeyResolver extends TrustAnchor {
        PublicKey resolve(String signerId) throws TrustResolutionException;
    }

    /** Délègue la vérification complète à une frontière de confiance externe (KMS/HSM Transit) —
     *  la clé ne quitte jamais cette frontière, ni même la logique de vérification. */
    non-sealed interface DelegatedVerifier extends TrustAnchor {
        void verify(String signerId, byte[] canonicalMessage, byte[] signature)
                throws TrustResolutionException;
    }
}

public sealed class TrustResolutionException extends Exception
        permits TrustResolutionException.Unavailable, TrustResolutionException.SignatureRejected {

    /** Échec transitoire (KMS injoignable, timeout réseau) — relève du retry/alerting infra,
     *  ne doit jamais être interprété comme "non vérifié donc accepté". */
    public static final class Unavailable extends TrustResolutionException { /* ... */ }

    /** Échec définitif — la signature ne correspond pas à l'identité annoncée. C'est un
     *  événement de sécurité potentiel (tentative de forge), pas une simple erreur technique ;
     *  doit déclencher un log/alerte distinct de {@code Unavailable}. */
    public static final class SignatureRejected extends TrustResolutionException { /* ... */ }
}
```

Dispatch dans `GenericSignerVerifier` — switch exhaustif, aucune branche `else` morte possible :

```java
PublicKey effectiveKey = switch (trustAnchor) {
    case TrustAnchor.PublicKeyResolver r -> r.resolve(signerId);   // vérif crypto locale ensuite
    case TrustAnchor.DelegatedVerifier d -> {
        d.verify(signerId, canonicalAnnouncement, announcementSignature); // déjà tranché
        yield null;
    }
};
```

**Encodage canonique de l'annonce** (longueur-préfixée, jamais de concatenation brute — la classe
de bug que `ProtocolV2` évite déjà soigneusement pour les identifiants) :
`len(pubkeyDER) ‖ pubkeyDER ‖ timestamp(8B) ‖ ttl(8B) ‖ len(signerId) ‖ signerId`.

**Portée d'autorisation** : table statique `signerId → préfixes de groupId autorisés`, livrée par
la configuration de déploiement, jamais par le broker. Un signataire compromis reste confiné à ses
propres préfixes — un service "facturation" compromis ne peut pas se faire passer pour
`groupId=admin`.

**Révocation de l'identité long-terme elle-même** : liste de révocation distribuée par le même
canal hors-bande que les clés — surtout pas via `__REVOKE__`, qui retomberait dans le même
défaut circulaire qu'on corrige.

### F7 — Tombstones signés

Conséquence directe : les messages de révocation deviennent eux-mêmes des annonces signées par
l'identité long-terme, avec un timestamp. Une règle simple et totale tranche tout conflit :
**l'annonce la plus récente, par timestamp signé, prévaut toujours** — qu'elle soit une
publication ou une révocation. Republier l'annonce originale après une révocation ne fait plus
rien, parce que son timestamp est strictement antérieur au tombstone qui l'a suivie. Le replay
perd toute prise.

---

## 3. F4 — Révocation à cohérence éventuelle

`KafkaMetadataBrokerAdapter` consomme en boucle (`poll(Duration.ofSeconds(10))`), avec une fenêtre
de propagation aujourd'hui décrite de façon floue ("best effort sous 10s+") plutôt que bornée.

**Remédiation** : les messages de contrôle (révocation, config — désormais des tombstones signés
par F7) sont routés sur une partition Kafka dédiée, pollée à intervalle court (≈200ms) et
indépendamment du flux applicatif, qui peut rester sur un cycle plus lent. Un mode opt-in
`StrictRevocationMode` permet en plus une lecture point-à-point directe du broker pour les
opérations jugées critiques, au prix de la latence — sans l'imposer partout. Le SLA de
propagation résultant (objectif réaliste : p99 < 1s pour le canal de contrôle) devient une
métrique exposée (`revocation_propagation_lag`), pas une promesse vague dans la doc.

---

## 4. F6 — Stockage local non borné

Le commentaire actuel du code dit explicitement que la purge est "intentionnellement omise" —
c'est un choix de conception qui n'a plus sa place dans une lib qui prétend tourner en continu en
production. **Remédiation** : tâche de compaction périodique (`ScheduledExecutorService`)
parcourant RocksDB et purgeant toute entrée dont `timestamp + ttl < now - graceWindow`, avec
`graceWindow` non nul pour absorber la tolérance d'horloge ±5min déjà admise ailleurs dans le
protocole. Une métrique de taille de DB exposée permet de détecter en prod toute croissance non
linéaire — signe qu'une fuite résiduelle existe malgré la purge.

---

## 5. F2 — `salt` disparaît

Le paramètre n'a jamais eu d'effet cryptographique réel — c'est une promesse non tenue dans
l'API, pas juste un paramètre inutilisé. Il n'y a pas de remplacement à proposer : le besoin
qu'il prétendait couvrir (diversifier des clés par tenant) est déjà couvert correctement par
`signerId` dans le nouveau modèle F1. Suppression nette du constructeur en v3.0, sans relai de
compatibilité — laisser un vestige "au cas où" perpétuerait la confusion qu'on corrige.

---

## 6. F8 — Sync/async dans `sign()`

Le GC fire-and-forget reste tel quel ; c'est l'éviction de session (`enforceSessionLimit`) qui
remplace son `.get(3, TimeUnit.MINUTES)` bloquant par un timeout court et configurable (quelques
secondes, aligné sur un SLA producer Kafka raisonnable), avec échec explicite typé plutôt qu'un
blocage de plusieurs minutes sur la voie chaude de `sign()`. `sign()` garde sa signature publique
synchrone — seule l'implémentation interne change de comportement face à la lenteur du broker.

---

## 7. F5 — Race sign→verify entre nœuds

Le nœud signataire pré-peuple son propre cache RocksDB local de manière synchrone au moment du
`sign()`, avant même la confirmation Kafka — le cas "même nœud vérifie son propre token tout de
suite après" cesse de dépendre d'un aller-retour consumer. Les autres nœuds restent, eux,
légitimement soumis à la cohérence éventuelle décrite en F4 — ce n'est pas un bug à corriger
partout, juste un cas particulier facile à éliminer sans contorsion.

---

## 8. F3 — Taille de clé RSA explicite

```java
KeyPairGenerator generator = KeyPairGenerator.getInstance(Config.ASYMMETRIC_KEYPAIR_ALGORITHM);
generator.initialize(Config.ASYMMETRIC_KEY_SIZE, secureRandom); // 3072 par défaut
```
`docs/security.md` affirme déjà RSA-2048 sans que le code ne l'impose — corriger les deux à la
fois en visant 3072 directement plutôt que de patcher une seconde fois quand le NIST achèvera la
dépréciation programmée de 2048 (horizon 2030-2035).

---

## 9. Bascule Java 25

`veridot-core` passe directement à 25 (LTS courante). Le switch exhaustif sur `TrustAnchor`
(section 2) en dépend nativement — sans ça, une branche `else` défensive resterait nécessaire en
17, ce qui aurait affaibli la garantie "le compilateur impose de traiter les deux formes" qui fait
tout l'intérêt du sealed interface. Vérifier uniquement que les dépendances transitives critiques
(client Kafka, binding JNI RocksDB) supportent 25 — le protocole réseau V2 lui-même ne change pas,
donc aucune incompatibilité de communication entre une instance v3.0 et une instance antérieure
au niveau du wire format (l'incompatibilité est uniquement au niveau de l'API Java appelante).

---

## 10. Tests à ajouter

- **Forge test** : un producteur Kafka tiers écrit une entrée arbitraire sur le topic → `verify()`
  doit échouer (`SignatureRejected`), preuve que le broker compromis seul ne suffit plus.
- **Replay-unrevoke test** : révoquer puis republier l'annonce originale (timestamp antérieur) →
  la session reste révoquée, le tombstone signé prévaut par construction.
- **Contract tests `TrustAnchor`** : KMS simulé en panne → `Unavailable`, jamais une absence
  silencieuse d'erreur ; signature altérée d'un seul bit → `SignatureRejected`, jamais accepté.
- **Load test purge RocksDB** : taille de DB stable sous charge soutenue sur 24h (regression guard
  F6).
- **Test de portée** : un `signerId` autorisé pour `groupId=billing-*` tente de certifier
  `groupId=admin-*` → rejeté avant même la vérification cryptographique de l'annonce.

## 11. Critères d'acceptation

- [ ] Aucun chemin de code ne fait confiance à une clé publique lue depuis le broker sans
      passage par un `TrustAnchor`. Aucune option de configuration ne permet de revenir à
      l'ancien comportement.
- [ ] Aucune révocation rejouable en sens inverse (tombstone signé, ordre par timestamp).
- [ ] Taille RocksDB bornée en régime stationnaire, mesurée sur un test de charge 24h.
- [ ] `docs/security.md` réécrit pour décrire exactement le comportement du code v3.0 — taille de
      clé, modèle de cohérence de la révocation, rôle du broker comme transport pur, et le nouveau
      vocabulaire (`TrustAnchor`) remplaçant toute référence à l'ancien modèle implicite.
