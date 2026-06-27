---
layout: page
title: "ADR-003: Tombstones de révocation signés (replay-safe)"
permalink: /docs/adr/003-tombstone-signed/
nav_order: 3
parent: ADR
---

# ADR-003 — Tombstones de révocation signés (replay-safe)

**Statut** : Accepté  
**Date** : 2026-06-27  
**Auteurs** : Frank KOSSI (Kunrin SA)  
**Implémenté dans** : `veridot-core` v3.0.2

---

## Contexte

En v3.0.1, les messages de révocation `__REVOKE__` avaient le format suivant :

```
2:<groupId>:__REVOKE__|target:<b64>,timestamp:<b64>
```

Ces messages **n'étaient pas signés**. Cela exposait deux vecteurs d'attaque :

### Vecteur 1 : Forge de tombstone (Denial-of-Session)

Un attaquant avec accès en écriture au broker pouvait publier un tombstone arbitraire pour n'importe quel groupId/séquence, révoquant des sessions légitimes. Impact : déni de service ciblé au niveau session utilisateur.

### Vecteur 2 : Replay d'annonce après tombstone

Scénario : un opérateur révoque la session `user-123:session-A`. Le tombstone est publié. Un attaquant ayant capturé l'ancienne annonce de clé (`2:user-123:session-A|pubkey:...`) la republie sur le broker. Si le consommateur n'a pas encore reçu le tombstone (latence de propagation), il peut accepter la session révoquée.

Ce scénario est conditionnel (race condition étroite) mais constitue un risque de sécurité réel dans des déploiements à haute latence ou lors d'incidents réseau.

---

## Décision

Tout tombstone `__REVOKE__` porte désormais une **signature long-terme RSA** (`tombstoneSig`) et un `timestamp` monotone. La règle **latest-timestamp-wins** rend le replay inoffensif.

### Format du message tombstone V2 (v3.0.2)

```
2:<groupId>:__REVOKE__|target:<b64>,timestamp:<b64>,signerId:<b64>,tombstoneSig:<b64>
```

| Champ | Type | Description |
|-------|------|-------------|
| `target` | Base64url(String) | `sequenceId` révoqué, ou `__ALL__` pour révocation de groupe |
| `timestamp` | Base64url(String de epoch seconds) | Moment de l'émission du tombstone |
| `signerId` | Base64url(String) | Identité du signataire long-terme |
| `tombstoneSig` | Base64url(bytes) | Signature RSA long-terme sur les bytes canoniques |

### Bytes canoniques du tombstone

```
len(groupId) [4 octets, big-endian] ‖ groupId [UTF-8]
‖ len(target) [4 octets, big-endian] ‖ target [UTF-8]
‖ timestamp   [8 octets, big-endian, epoch seconds]
```

Même principe length-prefixed que l'annonce de clé (ADR-001) — pas de concaténation naïve.

### Règle latest-timestamp-wins

Si deux tombstones arrivent pour le même `groupId`/`target`, celui avec le `timestamp` le plus élevé est conservé. Cela rend le replay d'un ancien tombstone valide **inoffensif** : il sera ignoré car un tombstone plus récent existe déjà dans RocksDB.

---

## Alternatives considérées

### Option A : Numéros de séquence monotones

Nécessite un état partagé pour garantir la monotonie (compteur centralisé ou consensus distribué). Incompatible avec le modèle sans coordinateur de Veridot, où chaque nœud opère indépendamment.

### Option B : Timestamp seul, sans signature

Protège contre le **replay accidentel** (un consommateur lent qui reçoit une ancienne annonce après le tombstone). Ne protège **pas** contre un attaquant actif capable d'émettre de faux tombstones ou de rejouer des tombstones anciens avec des timestamps manipulés.

### Option C (choisie) : Timestamp + signature long-terme

- Le **timestamp** empêche le replay d'anciens tombstones valides (latest-timestamp-wins).
- La **signature** empêche la forge de tombstones arbitraires.
- Les deux combinés offrent une protection complète contre les deux vecteurs identifiés.

---

## Implémentation

```java
// Extrait de GenericSignerVerifier.buildSignedRevocationMessage()
private String buildSignedRevocationMessage(String groupId, String target) {
    long timestamp = Instant.now().getEpochSecond();

    // Bytes canoniques length-prefixed
    byte[] groupIdBytes = groupId.getBytes(StandardCharsets.UTF_8);
    byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buf = ByteBuffer.allocate(4 + groupIdBytes.length + 4 + targetBytes.length + 8);
    buf.putInt(groupIdBytes.length).put(groupIdBytes);
    buf.putInt(targetBytes.length).put(targetBytes);
    buf.putLong(timestamp);

    // Signature long-terme RSA SHA-256
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(longTermPrivateKey);
    signer.update(buf.array());
    byte[] sig = signer.sign();

    // Assemblage V2
    Map<String, String> props = new LinkedHashMap<>();
    props.put(ProtocolV2.PROP_TARGET, target);
    props.put(ProtocolV2.PROP_TIMESTAMP, String.valueOf(timestamp));
    props.put(ProtocolV2.PROP_SIGNER_ID, signerId);
    props.put(ProtocolV2.PROP_TOMBSTONE_SIG, Base64.getUrlEncoder().withoutPadding().encodeToString(sig));
    return ProtocolV2.buildMessage(groupId, ProtocolV2.SEQ_REVOKE, props);
}
```

---

## Conséquences

### Positives

- Un attaquant avec accès en écriture au broker **ne peut pas forger** un tombstone sans disposer de la clé long-terme du signataire.
- Le replay d'anciens tombstones valides est **sans effet** (latest-timestamp-wins).
- Les consommateurs qui ne vérifient pas encore `tombstoneSig` (implémentations legacy) traitent le tombstone normalement — la signature est un champ additionnel, ignoré par les parseurs qui ne la reconnaissent pas.
- `RevocationTest.revoke_publishes_structured_revocation_message()` et `revoke_replay_original_announcement_after_tombstone_has_no_effect()` documentent et régressent le comportement.

### Négatives / Coût

- Chaque appel à `revoke()` produit une signature RSA (~0.5ms sur CPU moderne). Acceptable : la révocation est un chemin rare (event-driven), pas le hot path de vérification.
- Le message tombstone est légèrement plus grand (+2 champs base64url).

---

## Références

- [GenericSignerVerifier.java — buildSignedRevocationMessage](../../java/veridot-core/src/main/java/io/github/cyfko/veridot/core/impl/GenericSignerVerifier.java)
- [ProtocolV2.java — PROP_TOMBSTONE_SIG](../../java/veridot-core/src/main/java/io/github/cyfko/veridot/core/impl/ProtocolV2.java)
- [RevocationTest.java](../../java/veridot-core/src/test/java/io/github/cyfko/veridot/core/impl/RevocationTest.java)
- [ADR-001 — TrustAnchor](adr-001-trust-anchor/)
- [CHANGELOG.md — v3.0.2](../../java/CHANGELOG.md)
