---
layout: page
title: "ADR-001: TrustAnchor — Le broker est un transport, jamais une autorité"
permalink: /docs/adr/001-trust-anchor/
nav_order: 1
parent: ADR
---

# ADR-001 — TrustAnchor : le broker est un transport, jamais une autorité

**Statut** : Accepté  
**Date** : 2026-06-27  
**Auteurs** : Frank KOSSI (Kunrin SA)  
**Implémenté dans** : `veridot-core` v3.0.2

---

## Contexte

Jusqu'en v3.0.1, la chaîne de confiance de Veridot était :

```
Signataire → publie annonce de clé → Broker → consommateur lit la clé → vérifie le JWT
```

Le consommateur accordait une confiance **implicite** à toute annonce de clé reçue du broker, sans validation cryptographique de la source. Cela signifiait qu'un attaquant disposant d'un **accès en écriture au broker** (topic Kafka world-writable, ACL mal configurées, pod compromis dans le même namespace Kubernetes) pouvait :

1. Générer sa propre paire de clés RSA éphémère.
2. Publier une annonce de clé `2:<groupId>:<sequenceId>|pubkey:<sa-clé>,...` sur le topic.
3. Signer un JWT arbitraire avec sa clé privée.
4. **Obtenir une vérification valide** depuis n'importe quel consommateur Veridot.

Cette vulnérabilité est de **classe CVE** : elle contourne l'intégralité du modèle cryptographique de Veridot sans nécessiter de compromettre le signataire légitime. L'attaquant n'a besoin que d'un accès en écriture au broker — qui est souvent moins bien protégé que les clés privées des services.

---

## Décision

Introduire une interface `TrustAnchor` qui **déssolidarise l'accès en écriture au broker de la capacité à se faire reconnaître comme signataire de confiance**.

**Principe** : chaque annonce de clé reçue du broker doit être validée par le `TrustAnchor` configuré avant que la clé éphémère soit utilisée pour vérifier un JWT. Le broker est réduit à un rôle de **transport binaire pur**.

### Interface

```java
public sealed interface TrustAnchor
        permits TrustAnchor.PublicKeyResolver, TrustAnchor.DelegatedVerifier {

    /**
     * Option A — Résolution locale : Veridot charge la clé long-terme et
     * effectue la vérification RSA en-process.
     */
    non-sealed interface PublicKeyResolver extends TrustAnchor {
        PublicKey resolve(String signerId) throws TrustResolutionException;
    }

    /**
     * Option B — Délégation KMS/HSM : la vérification est entièrement
     * déléguée à un système externe. La clé privée ne quitte jamais le périmètre.
     */
    non-sealed interface DelegatedVerifier extends TrustAnchor {
        void verify(String signerId, byte[] canonicalAnnouncement, byte[] signature)
                throws TrustResolutionException;
    }
}
```

### Format canonique de l'annonce (bytes signés)

La signature long-terme couvre les octets suivants, encodés en format **length-prefixed** (pas de concaténation naïve, qui serait vulnérable à des attaques de type `"AB"+"C"` vs `"A"+"BC"`):

```
len(pubkeyDER) [4 octets, big-endian] ‖ pubkeyDER
‖ timestamp    [8 octets, big-endian, epoch seconds]
‖ ttl          [8 octets, big-endian, secondes]
‖ len(signerId)[4 octets, big-endian] ‖ signerId [UTF-8]
```

### Exception sealed

```java
public sealed class TrustResolutionException extends Exception
        permits TrustResolutionException.Unavailable,
                TrustResolutionException.SignatureRejected {

    /** Infrastructure transitoire (KMS down, réseau). Fail safe : rejeter le token. */
    public static final class Unavailable extends TrustResolutionException { ... }

    /** Rejet cryptographique définitif. Événement de sécurité : alerter. */
    public static final class SignatureRejected extends TrustResolutionException { ... }
}
```

La hiérarchie `sealed` force le compilateur à traiter les deux cas distinctement. Il est impossible de les confondre en production.

---

## Alternatives considérées

### Option A : Signer les messages V2 avec un HMAC partagé

**Rejeté.** Introduit un secret partagé entre tous les signataires et vérificateurs — exactement le problème que Veridot résout. Si le HMAC est compromis sur un service, tous les autres sont exposés.

### Option B : Vérifier la provenance uniquement à l'émission

**Rejeté.** Ne protège pas le consommateur contre une injection post-émission. Un attaquant qui compromet le broker _après_ la publication légitime peut écraser l'entrée.

### Option C : ACL Kafka strictes uniquement

**Insuffisant.** Les ACL sont une défense en profondeur indispensable, mais elles ne constituent pas une garantie cryptographique. Un seul pod compromis dans le namespace applicatif, ou une erreur d'opérateur sur les ACL, suffit à contourner cette protection. Aucune défense réseau ne remplace une vérification cryptographique côté consommateur.

### Option D (choisie) : TrustAnchor côté vérificateur

Chaque vérificateur valide **indépendamment** la signature long-terme de l'annonce. Aucun secret partagé. Compatible avec tous les patterns de gestion de clés (fichier PEM, Vault KV, Vault Transit, AWS KMS, Google Cloud KMS, Azure Key Vault, HSM physique).

---

## Conséquences

### Positives

- L'accès en écriture au broker ne suffit **plus** pour forger une vérification valide.
- Deux modes de déploiement : résolution locale (clé publique statique) ou délégation KMS (clé privée confinée dans le KMS).
- La hiérarchie d'exceptions `sealed` (`Unavailable` vs `SignatureRejected`) empêche le swallowing silencieux des erreurs de sécurité — le compilateur Java force le traitement explicite.
- 7 tests de sécurité documentent et régressent les vecteurs d'attaque (`TrustAnchorSecurityTest`).
- Fail-safe garanti : une indisponibilité du TrustAnchor rejette le token, jamais ne l'accepte.

### Négatives / Coût

- **Breaking change** sur le constructeur de `GenericSignerVerifier` : le `salt` est remplacé par `TrustAnchor + signerId + longTermPrivateKey`.
- Chaque déploiement doit désormais gérer une paire de clés long-terme (stockée dans Vault, un fichier PEM protégé, ou un KMS).
- La première implémentation d'une `TrustAnchor` nécessite ~5–20 lignes de code selon le backend.

---

## Exemples d'implémentation

```java
// Option 1 : Clé publique statique depuis un fichier PEM
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
    Path keyFile = Paths.get("/etc/veridot/trust/" + signerId + ".pub.pem");
    return PemUtils.loadPublicKey(Files.readAllBytes(keyFile));
};

// Option 2 : Vault KV (secret/veridot/trust/<signerId>)
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
    VaultResponse resp = vaultTemplate.read("secret/veridot/trust/" + signerId);
    String pem = (String) resp.getData().get("public_key");
    return PemUtils.loadPublicKey(pem.getBytes(StandardCharsets.UTF_8));
};

// Option 3 : Vault Transit Engine (la clé ne quitte pas Vault)
TrustAnchor anchor = (TrustAnchor.DelegatedVerifier) (signerId, canonical, sig) -> {
    boolean ok = vaultTemplate.opsForTransit().verify(signerId, canonical, sig);
    if (!ok) throw new TrustResolutionException.SignatureRejected(
        "Vault Transit: signature rejected for signerId=" + signerId);
};

// Assemblage (Spring Bean)
@Bean
public GenericSignerVerifier veridot(MetadataBroker broker,
                                      TrustAnchor anchor,
                                      PrivateKey longTermKey,
                                      @Value("${veridot.signer-id}") String signerId) {
    return new GenericSignerVerifier(broker, anchor, signerId, longTermKey);
}
```

---

## Références

- [TrustAnchor.java](../../java/veridot-core/src/main/java/io/github/cyfko/veridot/core/TrustAnchor.java)
- [TrustResolutionException.java](../../java/veridot-core/src/main/java/io/github/cyfko/veridot/core/exceptions/TrustResolutionException.java)
- [TrustAnchorSecurityTest.java](../../java/veridot-core/src/test/java/io/github/cyfko/veridot/core/impl/TrustAnchorSecurityTest.java)
- [CHANGELOG.md — v3.0.2](../../java/CHANGELOG.md)
