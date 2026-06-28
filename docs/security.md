---
layout: page
title: Security Model & Best Practices
permalink: /docs/security/
nav_order: 5
---

# Security Model & Best Practices

Veridot est conçu avec la sécurité comme premier principe. Cette page documente le modèle de menace complet, l'architecture de confiance v3.0, et les recommandations de durcissement pour un déploiement en production.

---

## 1. Architecture de confiance (v3.0)

### 1.1 Principe fondamental : le broker est un transport, jamais une autorité

Avant v3.0, Veridot accordait une confiance implicite à toute annonce de clé reçue du broker. Tout nœud avec accès en écriture au topic Kafka pouvait injecter une clé frauduleuse et obtenir une vérification valide.

**v3.0 ferme cette faille.** Chaque annonce de clé est validée de manière indépendante par un `TrustAnchor` avant que la clé éphémère soit acceptée. Le broker est réduit à un transport binaire pur.

```
Nœud signataire
  ├─ génère paire de clés RSA-3072 éphémère
  ├─ signe l'annonce avec sa clé long-terme        ← v3.0
  └─ publie sur le broker (pk + sig + sid)

Nœud vérificateur
  ├─ reçoit l'annonce du broker
  ├─ résout la clé publique long-terme via TrustAnchor  ← NOUVEAU v3.0
  ├─ vérifie sig avec la clé long-terme                 ← NOUVEAU v3.0
  └─ vérifie le JWT avec la clé éphémère
```

**Voir** : [ADR-001 — TrustAnchor](adr/adr-001-trust-anchor/)

### 1.2 Implémentations TrustAnchor

#### Option A — Clé publique statique (fichier PEM / Vault KV)

```java
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId -> {
    Path keyFile = Paths.get("/etc/veridot/trust/" + signerId + ".pub.pem");
    return PemUtils.loadPublicKey(Files.readAllBytes(keyFile));
};

var sv = new GenericSignerVerifier(broker, anchor, "my-service-id", longTermPrivateKey);
```

**Profil de sécurité** : la clé privée long-terme est détenue par le service signataire (Vault-injected, Kubernetes Secret). La clé publique est distribuée en lecture seule aux vérificateurs — elle n'est pas sensible.

#### Option B — Vault Transit / KMS (la clé ne quitte jamais le périmètre)

```java
TrustAnchor anchor = (TrustAnchor.DelegatedVerifier) (signerId, canonical, sig) -> {
    boolean valid = vaultTransit.verify(signerId, canonical, sig);
    if (!valid) {
        throw new TrustResolutionException.SignatureRejected(
            "Vault Transit rejected signature for signerId=" + signerId);
    }
};
```

**Profil de sécurité** : la clé privée long-terme ne quitte jamais le KMS. Recommandé pour les environnements avec des contrôles stricts sur les matériaux cryptographiques (HSM, hardware-backed KMS).

### 1.3 Sémantique d'échec du TrustAnchor

La hiérarchie `sealed` de `TrustResolutionException` force un traitement explicite des deux modes d'échec :

| Sous-type | Signification | Réponse correcte |
|-----------|--------------|------------------|
| `Unavailable` | Infrastructure transitoirement injoignable (KMS down, réseau) | **Fail safe : rejeter le token.** Ne jamais accepter. Alerter les ops. |
| `SignatureRejected` | Rejet cryptographique définitif | **Événement de sécurité : rejeter + alerter.** Logger à SEVERE. |

> **⚠️ Critique** : une erreur `Unavailable` ne doit **jamais** être silencieusement catchée et traitée comme « impossible à vérifier → accepter quand même ». Ce pattern réduirait le TrustAnchor à un no-op sous une défaillance d'infrastructure — exactement la fenêtre qu'un attaquant exploiterait.

### 1.4 Implémenter TrustRoot de manière sécurisée (Invariants V4)

Pour garantir la robustesse du protocole Veridot, toute implémentation personnalisée de `TrustRoot` doit se conformer strictement aux invariants suivants :

1. **Déterminisme et contrôle de `isRootIdentity(issuer)`** : La fonction déterminant si une identité est racine doit être basée sur des critères statiques, configurés au déploiement. Toute identité renvoyée comme racine contourne la chaîne d'autorisation `CapabilityVerifier` (profondeur de délégation 0). Une faille dans cette méthode expose l'intégralité du système.
2. **Fail-closed pour `resolve(issuer)`** : Dans `PublicKeyTrustRoot`, si un émetteur est inconnu, l'implémentation doit lever une exception plutôt que de retourner une valeur nulle ou par défaut. `SignatureVerifier` rejette le token en cas d'exception, garantissant ainsi l'isolation stricte des émetteurs.
3. **Fail-closed pour `verifySignature`** : Dans `DelegatedTrustRoot`, toute exception interne (timeout KMS, erreur réseau) doit se traduire par un retour `false` ou la propagation d'une exception non-interceptée. Ne renvoyez jamais `true` par défaut lors d'une défaillance d'infrastructure.
4. **Idempotence et double validation lors de la rotation** : La résolution de clé doit être idempotente pour un émetteur donné pendant la durée de vie du processus de vérification. Lors d'une rotation de clé long-terme, la configuration doit supporter temporairement l'ancienne et la nouvelle clé publique pour éviter toute rupture de service.
5. **Préférence pour les implémentations auditées** : Il est fortement recommandé d'utiliser une source de clés publiques statique signée (type JWKS) plutôt que de réimplémenter des mécanismes de transport réseau complexes sujet aux injections ou aux timeouts.

---

## 2. Fondations cryptographiques

### 2.1 Clés éphémères de signature

| Propriété | Valeur | Notes |
|-----------|--------|-------|
| Algorithme | RSA | `Config.ASYMMETRIC_KEYPAIR_ALGORITHM` |
| Taille | **3072 bits** | Hausse de 2048 implicite en v3.0.1 (NIST SP 800-57) |
| Rotation | 24h par défaut | Configurable via `VDOT_KEYS_ROTATION_MINUTES` |
| Persistance | **Jamais** | Clés en mémoire uniquement, jamais écrites sur disque |
| Niveau de sécurité | 128 bits équivalent | Identique à AES-128, ECDSA P-256 |

**Voir** : [ADR-002 — RSA-3072](adr/adr-002-rsa-3072/)

### 2.2 Clés d'identité long-terme

| Propriété | Valeur |
|-----------|--------|
| Algorithme | RSA (≥ 2048 bits ; 3072+ recommandé) |
| Signature d'annonce | `SHA256withRSA` sur les bytes canoniques universels |
| Signature de tombstone | `SHA256withRSA` sur les bytes canoniques universels |
| Stockage | Hors Veridot : Vault, KMS, HSM, Kubernetes Secret |

### 2.3 Encodage canonique universel

Les signatures utilisent un **encodage length-prefixed** universel pour éliminer toute ambiguïté (pas de concaténation naïve) :

```
len(messageId)  [4 octets, big-endian] ‖ messageId [UTF-8]
‖ pour chaque (clé, valeur) trié par ordre lexicographique (excluant 'sig' et 'token') :
    len(clé)    [4 octets, big-endian] ‖ clé [UTF-8]
    ‖ len(val)  [4 octets, big-endian] ‖ valeur [UTF-8]
```

---

## 3. Modèle de menaces

### 3.1 Menace : Accès en écriture au broker (injection de clé)

**Avant v3.0** : tout nœud avec accès en écriture Kafka pouvait forger une annonce de clé et passer la vérification.

**Mitigation v3.0** : validation `TrustAnchor`. Une annonce forgée échoue sauf si l'attaquant détient également la clé privée long-terme légitime.

**Risque résiduel** : si la clé long-terme est compromise, toutes les annonces signées avec elle sont rétroactivement attaquables. Rotation immédiate de la clé long-terme + invalidation de tous les tokens actifs.

### 3.2 Menace : Replay de tombstone

**Avant v3.0** : un tombstone non signé pouvait être rejoué ou forgé.

**Mitigation v3.0** : tombstones signés (ADR-003). Chaque message `__REVOKE__` porte un `sig` (signature RSA long-terme) et un `ts` monotone. La règle latest-timestamp-wins rend le replay d'anciens tombstones valides inoffensif.

**Voir** : [ADR-003 — Tombstones signés](adr/adr-003-tombstone-signed/)

### 3.3 Menace : Race read-after-write (même nœud)

**Avant v3.0** : un appel `verify()` immédiatement après `sign()` sur la même JVM pouvait échouer si la boucle consommateur Kafka n'avait pas encore traité les métadonnées signées.

**Mitigation v3.0** : `MetadataBroker.sendLocal()` pré-alimente le RocksDB local avant l'envoi Kafka asynchrone. La vérification sur le même nœud est désormais instantanée.

### 3.4 Menace : Croissance illimitée de RocksDB

**Avant v3.0** : les entrées de métadonnées expirées n'étaient supprimées que de manière lazy lors des appels `enforceSessionLimit`. Un groupe à faible trafic pouvait accumuler des entrées périmées indéfiniment.

**Mitigation v3.0** : tâche de compaction périodique (toutes les 5 min) qui purge toutes les entrées où `timestamp + ttl + 300 < now`.

### 3.5 Menace : Blocage indéfini de sign() par un broker lent

**Avant v3.0** : l'envoi d'éviction dans `enforceSessionLimit` utilisait un `CompletableFuture.get()` non borné, pouvant bloquer le hot path de signature indéfiniment.

**Mitigation v3.0** : timeout de **10 secondes** sur tous les envois d'éviction. Un broker lent produit un log d'avertissement, pas un blocage.

### 3.6 Menace : Dérive d'horloge

**Depuis v2.1** : les annonces avec un `timestamp` supérieur de plus de 5 minutes à l'heure locale sont rejetées (§9.1). Cela prévient qu'une mauvaise configuration NTP fasse accepter silencieusement des tokens expirés.

### 3.7 Menace : Replay de token après expiration

**Mitigations** (inchangées depuis v2.x) :
- Claim JWT `exp` vérifié au moment de la vérification.
- `timestamp + ttl` vérifié dans les métadonnées broker.
- La révocation permet une invalidation immédiate avant l'expiration du TTL.

---

## 4. Durcissement en production

### 4.1 Gestion des clés long-terme

```yaml
# Kubernetes Secret pour la clé privée long-terme
apiVersion: v1
kind: Secret
metadata:
  name: veridot-signer-key
  namespace: my-service
type: Opaque
data:
  private.key: <base64-encoded PKCS#8 RSA-3072 private key>
  signer.id: <base64-encoded service identifier>
```

```java
// Chargement depuis un secret monté en fichier
byte[] pkcs8 = Files.readAllBytes(Paths.get("/var/secrets/veridot/private.key"));
KeyFactory kf = KeyFactory.getInstance("RSA");
PrivateKey longTermKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
String signerId = Files.readString(Paths.get("/var/secrets/veridot/signer.id")).strip();

TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) id -> loadLongTermPublicKey(id);
var sv = new GenericSignerVerifier(broker, anchor, signerId, longTermKey);
```

### 4.2 Sécurité Kafka

```java
Properties props = new Properties();
props.setProperty("bootstrap.servers", "kafka1:9092,kafka2:9092");
props.setProperty("security.protocol", "SASL_SSL");
props.setProperty("sasl.mechanism", "SCRAM-SHA-512");
props.setProperty("sasl.jaas.config",
    "org.apache.kafka.common.security.scram.ScramLoginModule required " +
    "username=\"veridot-svc\" password=\"${KAFKA_PASSWORD}\";");
props.setProperty("ssl.endpoint.identification.algorithm", "https");
props.setProperty("ssl.protocol", "TLSv1.3");
props.setProperty("ssl.truststore.location", "/etc/ssl/kafka.truststore.jks");
props.setProperty("ssl.truststore.password", System.getenv("TRUSTSTORE_PASSWORD"));
```

### 4.3 ACL Kafka recommandées

```bash
# Services signataires : produire uniquement
kafka-acls.sh --add --allow-principal User:veridot-signer \
  --operation Write --topic token-verifier

# Services vérificateurs : consommer uniquement
kafka-acls.sh --add --allow-principal User:veridot-verifier \
  --operation Read --topic token-verifier

# Aucun service ne doit avoir Describe sur les consumer groups d'autres services
```

### 4.4 Gestion des sessions

```java
// Refuser tout dépassement (1 session active max par utilisateur)
var sv = new GenericSignerVerifier(
    broker, anchor, signerId, longTermKey,
    1, EvictionPolicy.REJECT);

// Éviction FIFO (3 sessions max, la plus ancienne évincée)
var sv = new GenericSignerVerifier(
    broker, anchor, signerId, longTermKey,
    3, EvictionPolicy.FIFO);
```

---

## 5. Surveillance et alertes

### 5.1 Niveaux de log à surveiller

| Niveau | Message | Action requise |
|--------|---------|----------------|
| `SEVERE` | `SECURITY: Key announcement signature rejected for sid=X` | **Alerte immédiate** — tentative d'injection potentielle |
| `SEVERE` | `Compaction: purged N expired RocksDB entries` | Surveiller N > baseline attendu |
| `SEVERE` | `Failed to send metadata for messageId X to broker` | Problème de connectivité broker |
| `WARNING` | `TrustAnchor temporarily unavailable for sid=X` | Problème d'infrastructure KMS |
| `WARNING` | `Eviction send timed out for key X after 10s` | Broker sous pression |

### 5.2 Règles Prometheus

```yaml
groups:
  - name: veridot-security
    rules:
      - alert: VeridotTrustAnchorRejection
        expr: increase(veridot_trust_anchor_rejected_total[5m]) > 0
        labels:
          severity: critical
        annotations:
          summary: "Possible tentative d'injection de clé broker détectée"
          description: "Au moins une annonce de clé a été rejetée par le TrustAnchor"

      - alert: VeridotTrustAnchorUnavailable
        expr: increase(veridot_trust_anchor_unavailable_total[5m]) > 3
        labels:
          severity: warning
        annotations:
          summary: "Infrastructure TrustAnchor dégradée"

      - alert: VeridotBrokerDown
        expr: up{job="veridot-broker"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Le broker de métadonnées Veridot est indisponible"
```

---

## 6. Checklist d'audit (v3.0+)

### Configuration cryptographique

- [ ] Paire de clés RSA long-terme générée (≥ 3072 bits recommandé)
- [ ] Clé privée long-terme dans Vault, KMS, ou Kubernetes Secret — **jamais dans le code source ou les variables d'environnement**
- [ ] `TrustAnchor` configuré — pas le no-op par défaut
- [ ] `sid` est un identifiant stable, unique, non-devinable pour le service signataire
- [ ] Intervalle de rotation des clés éphémères configuré (24h par défaut ; envisager plus court pour les environnements haute sécurité)

### Sécurité broker

- [ ] TLS activé pour toutes les communications Kafka (`SASL_SSL`)
- [ ] ACL Kafka restreignent l'écriture aux seuls services signataires
- [ ] Consumer group IDs uniques par déploiement
- [ ] Chemin RocksDB sur un volume chiffré

### Sécurité applicative

- [ ] `TrustResolutionException.Unavailable` **ne jamais** être silencieusement acceptée comme « token valide »
- [ ] `TrustResolutionException.SignatureRejected` loggée à SEVERE et déclenche une alerte sécurité
- [ ] TTL des tokens configurés correctement (≤ 15 min pour les opérations sensibles, ≤ 1h pour l'usage normal)
- [ ] Limites de sessions configurées pour les tokens utilisateurs (`maxSessions` + `EvictionPolicy`)
- [ ] Validation des entrées sur tous les `groupId` et `sequenceId`

### Sécurité opérationnelle

- [ ] Logs d'événements de sécurité acheminés vers SIEM
- [ ] Règles d'alerte configurées pour les événements `SignatureRejected`
- [ ] Procédure de réponse aux incidents documentée pour la compromission d'une clé long-terme
- [ ] Procédure de rotation des clés long-terme testée

---

## 7. Conformité réglementaire

### RGPD

- Les payloads des tokens doivent contenir des références (IDs utilisateurs), pas des données personnelles directement.
- Implémenter la minimisation des données : signer uniquement ce dont le vérificateur a besoin.
- `revoke(groupId, null)` implémente le « droit à l'oubli » au niveau session pour tous les tokens actifs d'un utilisateur.
- Politiques de rétention des logs d'événements de sécurité à définir.

### SOC 2 Type II

- La ligne de log `SECURITY: Key announcement signature rejected` constitue une piste d'audit pour les tentatives d'injection non autorisées.
- Les événements de rotation de clés peuvent être instrumentés avec Micrometer pour la journalisation d'audit.

### FIPS 140-2

- RSA-3072 avec `SHA256withRSA` satisfait les exigences FIPS 140-2 Level 1.
- Pour Level 2+, utiliser `DelegatedVerifier` avec un HSM validé FIPS.

---

## 8. Réponse aux incidents

### Compromission d'une clé long-terme

```bash
# 1. Révoquer immédiatement toutes les sessions actives pour tous les groupes
#    gérés par la clé compromise (par groupe connu) :
for GROUP_ID in $(list-all-active-groups); do
  veridot-admin revoke --group "$GROUP_ID" --all-sessions
done

# 2. Générer une nouvelle paire de clés RSA-3072
openssl genrsa -out new-private.pem 3072
openssl rsa -in new-private.pem -pubout -out new-public.pem

# 3. Mettre à jour le trust store (Vault, KMS) avec la nouvelle clé publique
vault kv put secret/veridot/trust/my-service-id public_key=@new-public.pem

# 4. Mettre à jour le secret de la clé privée dans le service signataire
kubectl create secret generic veridot-signer-key \
  --from-file=private.key=new-private.pem \
  --dry-run=client -o yaml | kubectl apply -f -

# 5. Redémarrer les instances du service signataire
kubectl rollout restart deployment/my-auth-service

# 6. Surveiller les logs SignatureRejected (les anciennes signatures échoueront gracieusement)
```

### Compromission du broker

1. Rotation des credentials Kafka et certificats TLS.
2. Révision des ACL Kafka — ajouter des DENY explicites pour les principals compromis.
3. Surveiller les logs `SECURITY: Key announcement signature rejected` — un attaquant niveau broker déclenchera ces entrées.
4. Aucun token Veridot n'a besoin d'être révoqué : le `TrustAnchor` empêche toute annonce forgée de passer la vérification.

---

## Feuille de route sécurité

- **Signatures post-quantiques** : migration vers CRYSTALS-Dilithium (FIPS 204) pour les clés long-terme, remplaçant RSA pour la signature d'annonces.
- **Révocation globale par signerId** : révoquer toutes les sessions associées à un signerId compromis en une seule opération.
- **Clés éphémères ECDSA P-384** : offrir ECDSA comme alternative à RSA pour la génération de clés éphémères (signatures plus courtes, même niveau de sécurité).
- **Attestation matérielle** : lier les clés long-terme à TPM 2.0 ou à l'attestation SGX enclave.