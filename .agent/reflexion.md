# Étude Architecturale : Module `veridot-trustroots`

## Architecture Decision Record — RFC-001

**Statut :** Proposition  
**Auteur :** Analyse architecturale  
**Périmètre :** Module optionnel d'implémentations de `TrustRoot` pour Veridot  
**Référence :** Code source GitHub `cyfko/veridot` + Document `PROTOCOL_V4`

---

## Note préliminaire méthodologique

Cette étude est fondée exclusivement sur l'analyse du code source disponible dans le dépôt `cyfko/veridot` et sur le document `PROTOCOL_V4`. Toute affirmation est ancrée dans des éléments concrets du code ou du protocole. Lorsque le code ou le protocole ne fournit pas de base suffisante pour une affirmation, cela est explicitement signalé. Les idées proposées dans le prompt sont soumises à examen critique et ne sont pas validées par défaut.

---

## Table des matières

1. Résumé exécutif
2. Analyse détaillée de l'architecture actuelle de `TrustRoot`
3. Identification des contraintes imposées par `PROTOCOL_V4`
4. Étude approfondie de l'architecture TAD
5. Étude détaillée de chaque mécanisme alternatif
6. Comparaison exhaustive
7. Analyse des risques et implications de sécurité
8. Analyse des performances et scalabilité
9. Recommandations argumentées
10. Proposition d'architecture du module `veridot-trustroots`
11. Conclusion motivée

---

## 1. Résumé exécutif

L'analyse du code source de Veridot révèle que `TrustRoot` est une interface minimaliste dont le rôle est strictement délimité par `PROTOCOL_V4` : elle constitue le canal hors bande permettant à un vérificateur de résoudre une clé publique long terme à partir d'un identifiant de sujet. Cette interface n'est pas une PKI, pas un service de découverte généraliste, et pas un composant de consensus distribué. Son périmètre est intentionnellement restreint.

L'étude démontre que :

1. Un module `veridot-trustroots` est **pertinent et faisable** sans violer les principes du protocole, à condition de respecter un invariant fondamental : toute implémentation de `TrustRoot` doit se comporter comme un **cache local avec synchronisation différée**, jamais comme un dépendant réseau synchrone sur le chemin critique.

2. L'architecture TAD proposée est **architecturalement cohérente** avec `PROTOCOL_V4` mais requiert des précautions précises pour éviter de devenir un SPoF ou d'introduire une latence sur le chemin de vérification. Ces précautions sont détaillées et formellement spécifiées dans ce document.

3. Les KMS cloud sont **incompatibles** avec les exigences de fonctionnement hors ligne et introduisent une latence permanente inacceptable sur le chemin de vérification, sauf dans un pattern de pré-chargement strict.

4. La recommandation principale est de créer un module organisé autour d'un **pattern Provider + LocalCache obligatoire**, où le cache local persistant est le seul composant sur le chemin critique, et où les providers distants (TAD, base de données, KMS) ne sont jamais consultés lors de la vérification d'un token actif.

---

## 2. Analyse détaillée de l'architecture actuelle de `TrustRoot`

### 2.1 Localisation et structure de l'interface

L'interface `TrustRoot` est définie dans le cœur de la bibliothèque. À partir du code source :

```java
// veridot-core / src/main/java/io/github/cyfko/veridot/TrustRoot.java
public interface TrustRoot {
    PublicKey resolve(String subject) throws TrustRootException;
}
```

L'interface expose exactement **une méthode** : `resolve(String subject)`. Cette unique méthode retourne une `PublicKey` correspondant à un sujet (identifiant de clé long terme) ou lève une `TrustRootException` en cas d'échec.

**Observation critique :** L'interface ne comporte aucune méthode de publication, d'enregistrement, de révocation ou de validation. Elle est **strictement read-only** du point de vue du vérificateur. Cela est cohérent avec le rôle assigné par `PROTOCOL_V4` : le vérificateur ne gère pas les clés, il les résout.

### 2.2 Utilisation dans le flux de vérification

En examinant l'implémentation du vérificateur (`Verifier` ou équivalent dans le code source) :

```java
// Pattern d'utilisation dans le flux de vérification
PublicKey ltk = trustRoot.resolve(subject);
// Utilisation de ltk pour vérifier la signature du token éphémère
```

Le `TrustRoot` est invoqué **dans le chemin critique de vérification**. Toute latence introduite par son implémentation se répercute directement sur la latence de vérification. C'est une contrainte architecturale fondamentale.

### 2.3 `TrustRootException`

```java
public class TrustRootException extends RuntimeException {
    // ...
}
```

La conception de `TrustRootException` comme `RuntimeException` indique que l'échec de résolution est considéré comme une condition exceptionnelle, non comme un flux nominal. Une implémentation qui échoue régulièrement (par exemple parce qu'elle interroge un service réseau instable) est architecturalement incorrecte.

### 2.4 Responsabilités actuelles de `TrustRoot`

En se basant exclusivement sur le code :

| Responsabilité | Présente | Justification |
|---|---|---|
| Résolution clé par sujet | ✅ | Méthode `resolve(String subject)` |
| Publication de clé | ❌ | Aucune méthode correspondante |
| Révocation | ❌ | Aucune méthode correspondante |
| Validation d'expiration | ❌ | Non dans l'interface (délégué au protocole) |
| Énumération des sujets | ❌ | Aucune méthode correspondante |
| Notification de changement | ❌ | Aucun mécanisme observable |

### 2.5 Limites de l'API actuelle

**Limite 1 : Absence de contexte temporel**  
`resolve(String subject)` ne prend pas de paramètre temporel. L'implémentation ne peut pas distinguer une requête pour « la clé valide à l'instant T » d'une requête pour « la clé actuellement active ». Dans un contexte de rotation de clés, cela peut poser problème si plusieurs clés ont été successivement valides pour un même sujet.

**Limite 2 : Absence de métadonnées retournées**  
La méthode retourne une `PublicKey` brute sans métadonnées associées (date d'expiration, algorithme déclaré, version, etc.). L'implémentation du cache ne peut pas déterminer intrinsèquement quand la clé doit être rafraîchie sans logique additionnelle.

**Limite 3 : Absence de callback de rafraîchissement**  
Il n'existe aucun mécanisme permettant à une implémentation de `TrustRoot` de notifier proactivement le vérificateur qu'une clé a changé. Le rafraîchissement est entièrement à la charge de l'implémentation, de manière opaque.

**Limite 4 : Sémantique d'échec ambiguë**  
`TrustRootException` est levée indistinctement pour « sujet inconnu », « service indisponible » et « clé expirée ». Cette ambiguïté complique la gestion des erreurs en aval.

### 2.6 Les responsabilités permettent-elles des implémentations génériques ?

**Réponse : oui, avec des nuances.**

La simplicité de l'interface est un avantage pour la généricité. N'importe quel mécanisme de stockage clé-valeur peut implémenter `resolve(String subject)`. Cependant, les limites identifiées (absence de TTL retourné, absence de distinction entre types d'erreur) imposent que chaque implémentation générique embarque sa propre logique de gestion du cycle de vie des clés, ce qui est une source de duplication et d'incohérences potentielles.

La conclusion est qu'un module `veridot-trustroots` devra **compléter l'API sans la modifier**, en introduisant une interface interne enrichie (`TrustRootProvider`) dont `TrustRoot` reste la façade publique.

---

## 3. Identification des contraintes imposées par `PROTOCOL_V4`

### 3.1 Canal hors bande

`PROTOCOL_V4` définit le `TrustRoot` comme un **canal hors bande** (out-of-band channel). La distribution des clés publiques long terme n'emprunte pas le même canal que les tokens. C'est une propriété de sécurité fondamentale : un attaquant qui compromet le canal de distribution des tokens ne peut pas, par ce seul fait, compromettre la résolution des clés de confiance.

**Implication architecturale :** Le TAD doit utiliser un canal distinct et authentifié indépendamment du canal applicatif.

### 3.2 Fonctionnement hors ligne

`PROTOCOL_V4` prévoit explicitement que la vérification doit fonctionner **hors ligne**. Une fois la clé publique long terme résolue et cachée localement, la vérification d'un token éphémère ne doit nécessiter aucune communication réseau.

**Implication architecturale :** Toute implémentation de `TrustRoot` qui effectue un appel réseau synchrone lors de `resolve()` viole ce principe, **sauf si** elle est garantie de ne jamais être invoquée avec une clé absente du cache local dans des conditions normales de fonctionnement.

### 3.3 Clé privée long terme non divulguée

`PROTOCOL_V4` stipule que la clé privée long terme d'un émetteur **ne quitte jamais** le microservice émetteur. La distribution hors bande ne concerne que la clé **publique**.

**Implication architecturale :** Toute solution (TAD, KMS, base de données) qui stockerait ou transmettrait la clé privée viole une propriété fondamentale du protocole. Les KMS de type HSM, qui génèrent et détiennent eux-mêmes les clés privées, sont donc **architecturalement incompatibles** avec ce principe tel qu'il est défini dans `PROTOCOL_V4`, sauf s'ils sont utilisés exclusivement pour la génération locale (ce qui n'est pas leur usage typique dans un contexte distribué).

### 3.4 Intégrité de la clé publique distribuée

`PROTOCOL_V4` exige que la clé publique reçue via le canal hors bande soit authentique. Une implémentation de `TrustRoot` qui distribue des clés non authentifiées ouvre la porte à des attaques de substitution.

**Implication architecturale :** Toute implémentation distante (TAD, service HTTP) doit intégrer un mécanisme d'authentification de l'origine de la clé. La clé publique reçue doit être liée à l'identité du microservice émetteur de manière vérifiable.

### 3.5 Absence de Single Point of Failure

`PROTOCOL_V4` est conçu pour des architectures distribuées hautement disponibles. Aucun composant unique ne doit pouvoir interrompre la vérification des tokens en production.

**Implication directe sur le TAD :** Le TAD ne peut être considéré comme correctement conçu que s'il est déployé de manière à garantir que sa défaillance n'impacte pas la vérification de tokens pour lesquels les clés sont déjà résolues localement.

### 3.6 Synthèse des invariants

| Invariant `PROTOCOL_V4` | Impact sur les implémentations `TrustRoot` |
|---|---|
| Canal hors bande | Le canal de résolution doit être distinct et authentifié indépendamment |
| Hors ligne après résolution initiale | Jamais d'appel réseau synchrone sur le chemin critique |
| Clé privée non divulguée | Aucun stockage distant de clé privée |
| Intégrité de la clé publique | Authentification cryptographique de la source |
| Pas de SPoF | Haute disponibilité et cache local obligatoire |

---

## 4. Étude approfondie de l'architecture TAD (Trust Authority Directory)

### 4.1 Nature du TAD au regard du protocole

Avant de concevoir le TAD, il est nécessaire de déterminer sa nature au regard de `PROTOCOL_V4`.

**Est-il un simple annuaire ?**  
Un annuaire se contente d'associer un identifiant à une valeur. Un TAD qui serait un simple annuaire ne validerait pas l'authenticité des clés publiées, ce qui violerait l'invariant d'intégrité de `PROTOCOL_V4`. **Insuffisant seul.**

**Est-il une autorité d'enregistrement (Registration Authority) ?**  
Une RA valide l'identité de l'entité qui publie une clé avant de l'enregistrer. C'est cohérent avec l'invariant d'intégrité. **Nécessaire.**

**Est-il un service de découverte (Discovery Service) ?**  
Un service de découverte permet de localiser des services. Le TAD distribue des clés, pas des localisations de services. La confusion des responsabilités serait une violation du principe de responsabilité unique. **Partiellement, mais ce n'est pas sa responsabilité principale.**

**Conclusion sur la nature du TAD :**  
Le TAD doit être conçu comme une **Registration Authority + Directory** : il valide l'authenticité des publications et référence les clés publiques. Il n'est pas un service de découverte au sens architectural du terme. Il n'est pas non plus une CA (Certificate Authority) car il n'émet pas de certificats signés par sa propre clé ; il atteste de la provenance par des mécanismes d'authentification distincts.

### 4.2 Architecture complète du TAD

#### 4.2.1 Vue d'ensemble

```
┌─────────────────────────────────────────────────────────────────┐
│                        TAD Cluster                              │
│                                                                 │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐                  │
│  │  TAD-1   │◄──►│  TAD-2   │◄──►│  TAD-3   │  (Raft/Gossip)  │
│  └────┬─────┘    └────┬─────┘    └────┬─────┘                  │
│       │               │               │                         │
│  ┌────▼───────────────▼───────────────▼────┐                   │
│  │         Distributed Store               │                   │
│  │   (clés publiques + métadonnées)        │                   │
│  └─────────────────────────────────────────┘                   │
└─────────────────────────────────────────────────────────────────┘
         ▲ Publication (HTTPS/mTLS)    │ Résolution (HTTPS/mTLS)
         │                             ▼
┌─────────────────┐           ┌─────────────────────────────────┐
│  Microservice   │           │  Microservice Vérificateur      │
│  Émetteur       │           │                                 │
│                 │           │  ┌──────────────────────────┐   │
│  [LTK privée]   │           │  │  Local Persistent Cache  │   │
│  [LTK publique] │           │  │  (TrustRoot impl)        │   │
└─────────────────┘           │  └──────────┬───────────────┘   │
                               │             │ miss uniquement   │
                               │             ▼                   │
                               │  [TAD consulté hors chemin      │
                               │   critique, en arrière-plan]    │
                               └─────────────────────────────────┘
```

#### 4.2.2 Modèle de données

```java
/**
 * Entrée canonique dans le TAD.
 * Justification : doit contenir toutes les informations nécessaires
 * pour que le vérificateur puisse mettre en cache avec un TTL précis,
 * conforme à l'exigence de fonctionnement hors ligne de PROTOCOL_V4.
 */
public record TrustEntry(
    String subject,           // Identifiant du microservice émetteur
    String publicKeyEncoded,  // Clé publique encodée (Base64, format spécifié par PROTOCOL_V4)
    String algorithm,         // Algorithme (ex: "Ed25519", "EC/P-256")
    Instant notBefore,        // Début de validité
    Instant notAfter,         // Fin de validité absolue
    long version,             // Numéro de version monotonique (pour détection de rotation)
    String fingerprint,       // Empreinte SHA-256 de la clé publique
    String issuerSignature,   // Signature de l'entrée par la clé privée du microservice émetteur
    Instant publishedAt,      // Horodatage de publication
    Map<String, String> metadata // Métadonnées extensibles
) {}
```

**Justification de `issuerSignature` :** `PROTOCOL_V4` requiert que la clé publique distribuée soit authentique. La signature de l'entrée par la clé privée de l'émetteur permet au vérificateur de contrôler que la clé n'a pas été substituée par le TAD lui-même ou par un attaquant sur le canal. Ce point est critique : **le TAD ne doit pas être implicitement de confiance pour l'intégrité des clés**.

**Justification de `notAfter` :** L'absence de durée d'expiration dans l'interface actuelle de `TrustRoot` est une limite identifiée en section 2.5. Le modèle de données du TAD comble ce manque et permet aux implémentations de `TrustRoot` d'établir un TTL de cache précis.

**Justification de `version` :** Permet de détecter une rotation de clé sans avoir à comparer les clés elles-mêmes. Le cache local peut invalider proactivement une entrée dont la version est inférieure à celle annoncée par le TAD.

#### 4.2.3 API de publication

```
POST /v1/trust-entries
Content-Type: application/json
Authorization: Bearer <service-token> (mTLS requis)

{
  "subject": "payments-service",
  "publicKeyEncoded": "...",
  "algorithm": "Ed25519",
  "notBefore": "2024-01-01T00:00:00Z",
  "notAfter": "2024-12-31T23:59:59Z",
  "issuerSignature": "...",
  "metadata": { "environment": "production", "region": "eu-west-1" }
}

Response 201 Created:
{
  "subject": "payments-service",
  "version": 42,
  "fingerprint": "sha256:...",
  "publishedAt": "2024-01-01T00:00:01Z"
}
```

```
PUT /v1/trust-entries/{subject}
// Même structure, pour rotation de clé
// Incrémente atomiquement la version
```

**Contrôle d'autorisation à la publication :**  
Le microservice ne peut publier que pour son propre `subject`. Le mapping entre le certificat mTLS du microservice (ou le service token) et le `subject` autorisé est contrôlé par une politique d'autorisation (ex: RBAC ou attribut dans le certificat).

**Vérification à la publication :**  
Avant d'enregistrer, le TAD doit :
1. Vérifier l'authentification mTLS du client
2. Vérifier que le `subject` correspond à l'identité authentifiée
3. Vérifier la `issuerSignature` (la clé publique soumise doit valider la signature de l'entrée entière)
4. Vérifier que `notAfter` est dans des limites raisonnables (ex: pas plus de 2 ans)
5. Vérifier la cohérence temporelle (`notBefore < notAfter`, `publishedAt ≈ now`)

#### 4.2.4 API de récupération

```
GET /v1/trust-entries/{subject}
// Retourne la dernière version active

GET /v1/trust-entries/{subject}?version=42
// Retourne une version spécifique (pour audit/debug)

GET /v1/trust-entries?subjects=svc-a,svc-b,svc-c
// Résolution par lot (batch) — réduction des allers-retours réseau

GET /v1/trust-entries?modifiedSince=2024-01-01T00:00:00Z
// Synchronisation incrémentale — permet aux caches de se mettre à jour

HEAD /v1/trust-entries/{subject}
// Retourne uniquement les headers (ETag, Last-Modified, version)
// Utilisé pour la validation de cache avec un aller-retour minimal
```

**Réponse standard :**
```json
{
  "subject": "payments-service",
  "publicKeyEncoded": "...",
  "algorithm": "Ed25519",
  "notBefore": "2024-01-01T00:00:00Z",
  "notAfter": "2024-12-31T23:59:59Z",
  "version": 42,
  "fingerprint": "sha256:...",
  "issuerSignature": "...",
  "publishedAt": "2024-01-01T00:00:01Z",
  "cacheControl": {
    "maxAge": 3600,
    "staleWhileRevalidate": 300
  }
}
```

**Justification de `cacheControl`:** Le TAD peut indiquer aux clients combien de temps conserver la clé en cache et pendant combien de temps une clé expirée peut encore être utilisée pendant la revalidation. Cette information permet à l'implémentation de `TrustRoot` de gérer son cache de manière cohérente avec la politique du TAD, sans hard-coder des durées.

#### 4.2.5 Mécanismes d'authentification

**Authentification côté publication (write path) :**
- mTLS obligatoire avec certificats émis par une PKI interne
- Le CN ou SAN du certificat client doit correspondre au `subject` déclaré
- Validation côté TAD du certificat contre la CA interne

**Authentification côté récupération (read path) :**
- mTLS recommandé, HTTPS avec authentification par service token acceptable
- La lecture est moins critique en termes d'autorisation (les données sont publiques par nature) mais doit être protégée contre les injections et le scraping non autorisé

**Authentification du TAD lui-même :**
- Le TAD présente un certificat TLS valide, émis par une CA connue des clients
- Les réponses du TAD peuvent être signées par une clé TAD (optionnel, pour une défense en profondeur)

#### 4.2.6 Haute disponibilité et anti-SPoF

**Problème fondamental :** Si le TAD est un service unique, il devient un SPoF pour l'initialisation des caches. Une fois les caches peuplés, sa défaillance n'impacte pas la vérification, mais les nouveaux microservices ou les démarrages à froid sont bloqués.

**Solution 1 : Cluster TAD avec consensus**
```
TAD-1, TAD-2, TAD-3 derrière un load balancer.
Consensus via Raft pour les opérations d'écriture.
Les lectures peuvent être servies par n'importe quel nœud (eventual consistency acceptable pour les clés).
```

**Solution 2 : TAD répliqué en lecture avec primaire unique en écriture**
```
TAD-primary (écriture) → réplication vers TAD-replica-1, TAD-replica-2
Les lectures sont distribuées sur tous les nœuds.
Si le primaire tombe, les lectures continuent. Les écritures (publications) sont bloquées mais non critiques en production stable.
```

**Solution 3 : Snapshots périodiques distribués**
```
Le TAD exporte périodiquement un snapshot signé de toutes les clés actives.
Ce snapshot est distribué sur un CDN ou un stockage objet (S3, GCS).
Les clients peuvent bootstrap leur cache depuis le snapshot en cas d'indisponibilité du TAD.
```

**Recommandation :** Combiner Solution 1 + Solution 3. Le cluster garantit la disponibilité normale. Le snapshot garantit le démarrage à froid même en cas de défaillance totale du TAD.

**Précision critique sur le SPoF :** Même avec un TAD hautement disponible, l'implémentation de `TrustRoot` dans le vérificateur **doit** fonctionner avec son cache local en cas d'indisponibilité du TAD. Le TAD n'est consulté que pour peupler le cache, pas pour chaque vérification. C'est l'invariant fondamental qui empêche le TAD d'être un SPoF opérationnel.

#### 4.2.7 Stratégie de cache dans l'implémentation `TrustRoot`

```
Phase 1 : Démarrage à froid
  → Charger le cache depuis le stockage local persistant (fichier, DB locale)
  → Si cache vide ou trop ancien : contacter le TAD de manière synchrone (acceptable au démarrage)
  → Si TAD indisponible au démarrage : charger depuis snapshot distribué

Phase 2 : Fonctionnement nominal
  → resolve(subject) consulte UNIQUEMENT le cache local (aucun appel réseau)
  → Si miss dans le cache : lever TrustRootException (sujet inconnu)
  → [Note critique : voir section 4.2.8 sur la gestion des misses]

Phase 3 : Rafraîchissement proactif (hors chemin critique)
  → Thread de fond vérifie périodiquement les clés proches de l'expiration
  → Consulte le TAD pour les clés dont TTL < seuil (ex: 20% de la durée totale restante)
  → Met à jour le cache local de manière atomique

Phase 4 : Invalidation sur rotation
  → Écoute des events de rotation via webhook ou polling (hors chemin critique)
  → Déclenche un rafraîchissement immédiat du cache pour les sujets concernés
```

#### 4.2.8 Gestion critique du cold-miss

**Problème :** Un miss dans le cache lors de `resolve()` peut avoir deux origines distinctes :
- a) Le sujet est réellement inconnu (jamais publié au TAD)
- b) Le sujet est connu mais n'a pas encore été chargé dans le cache local (démarrage, nouveau service)

Si l'implémentation ne fait **jamais** d'appel réseau lors de `resolve()`, le cas (b) provoque un échec de vérification incorrect. Si elle fait un appel réseau synchrone, elle viole le principe de fonctionnement hors ligne et introduit une latence.

**Solution correcte :**  
L'implémentation doit distinguer ces deux cas :
- Si le cache local a été peuplé récemment (depuis moins d'un intervalle configurable) et ne contient pas le sujet → cas (a), lever `TrustRootException` immédiatement
- Si le cache local est potentiellement incomplet (premier démarrage, dernier sync trop ancien) → cas (b), tenter une résolution réseau **uniquement si le timeout global le permet** et mettre en cache le résultat

Cette logique doit être configurable et clairement documentée dans le module.

#### 4.2.9 Politique d'expiration et rotation des clés

**Rotation initiée par l'émetteur :**
1. L'émetteur génère une nouvelle paire de clés
2. Il publie la nouvelle clé publique via `PUT /v1/trust-entries/{subject}`
3. Le TAD incrémente la version et marque l'ancienne entrée comme `superseded`
4. Les vérificateurs dont le cache contient l'ancienne clé continuent de fonctionner jusqu'à expiration du TTL de cache
5. Le thread de rafraîchissement détecte la nouvelle version et met à jour le cache

**Durée de recouvrement :** La politique doit garantir que pendant la rotation, les tokens émis avec l'ancienne clé restent vérifiables. Cela implique que le TAD conserve les anciennes versions pendant au moins la durée de vie maximale d'un token éphémère (TTL des tokens éphémères, défini dans `PROTOCOL_V4`).

**Révocation d'urgence :** Le TAD doit exposer une API de révocation qui invalide immédiatement une entrée. Cette révocation doit se propager aux caches via le mécanisme de polling court ou webhook. La révocation n'est pas dans la responsabilité de l'interface `TrustRoot` actuelle, ce qui est une limite identifiée.

#### 4.2.10 Garanties cryptographiques

| Garantie | Mécanisme |
|---|---|
| Authentification publication | mTLS avec certificat client |
| Intégrité de la clé publiée | `issuerSignature` (clé privée émetteur) |
| Confidentialité du transport | TLS 1.3 minimum |
| Intégrité des réponses TAD | Signature optionnelle des réponses (JWS ou similaire) |
| Non-répudiation | `issuerSignature` + journalisation immuable |
| Protection replay | Horodatage dans la signature + nonce optionnel |

#### 4.2.11 Scénarios de panne et stratégies de reprise

| Scénario | Impact | Stratégie de reprise |
|---|---|---|
| TAD indisponible (cache chaud) | Aucun impact sur la vérification | Cache local suffisant |
| TAD indisponible (cache froid) | Démarrage à froid impossible | Bootstrap depuis snapshot distribué |
| TAD corrompu (données) | Clés invalides distribuées | Validation `issuerSignature` détecte la corruption |
| TAD compromis (attaquant) | Injection de fausses clés | `issuerSignature` invalide, rejet par le vérificateur |
| Réseau partitionné | Sous-ensemble de vérificateurs sans accès TAD | Cache local, renouvellement bloqué mais vérification OK |
| Rotation de clé pendant une panne | Tokens émis avec nouvelle clé non vérifiables | Ancienne clé conservée en cache jusqu'à TTL |

#### 4.2.12 Stratégies de test

**Tests unitaires :**
```java
// Mock de TrustRoot pour tests unitaires du vérificateur
TrustRoot mockTrustRoot = subject -> mockPublicKey;

// Test de la logique de cache sans dépendance réseau
TrustRootCache cache = new TrustRootCache(mockProvider, Duration.ofSeconds(60));
assertThat(cache.resolve("svc-a")).isEqualTo(expectedKey);
```

**Tests d'intégration :**
- TAD embarqué (in-memory, démarré par les tests)
- Conteneur Docker TAD pour tests d'intégration end-to-end
- Test de la rotation de clé avec vérification des tokens en cours

**Tests de charge :**
- Simulation de milliers de vérificateurs avec caches locaux chauds
- Mesure de la latence de `resolve()` depuis le cache local : cible < 1ms
- Simulation d'un TAD défaillant avec caches chauds : 0% d'impact sur le débit de vérification

**Chaos engineering :**
- Arrêt aléatoire de nœuds TAD (vérification que les caches locaux absorbent la panne)
- Injection de latence sur le TAD (vérification que le chemin critique n'est pas impacté)
- Corruption de données TAD (vérification du rejet par validation des signatures)
- Partition réseau entre vérificateurs et TAD (vérification du fonctionnement hors ligne)

---

## 5. Étude détaillée de chaque mécanisme alternatif

### 5.1 Système de fichiers partagés (NFS, GlusterFS, etc.)

#### 5.1.1 Description

Un fichier partagé (ou un répertoire) contient les clés publiques des émetteurs, accessible en lecture par tous les vérificateurs. La structure peut être aussi simple que :

```
/trust-roots/
  payments-service.pub
  orders-service.pub
  ...
```

Ou un fichier JSON unique :
```json
{
  "payments-service": {
    "publicKey": "...",
    "notAfter": "2024-12-31T23:59:59Z",
    "version": 1
  }
}
```

#### 5.1.2 Implémentation de `TrustRoot`

```java
public class FilesystemTrustRoot implements TrustRoot {
    private final Path rootDirectory;
    private final Map<String, CachedKey> localCache = new ConcurrentHashMap<>();

    @Override
    public PublicKey resolve(String subject) throws TrustRootException {
        CachedKey cached = localCache.get(subject);
        if (cached != null && !cached.isExpired()) {
            return cached.key();
        }
        // Lecture depuis le FS partagé — potentiellement lente
        Path keyFile = rootDirectory.resolve(subject + ".json");
        try {
            TrustEntry entry = readAndValidate(keyFile);
            localCache.put(subject, new CachedKey(entry));
            return entry.publicKey();
        } catch (IOException e) {
            throw new TrustRootException("Cannot resolve: " + subject, e);
        }
    }
}
```

#### 5.1.3 Analyse

**Avantages :**
- Extrêmement simple à implémenter
- Aucune dépendance externe
- Fonctionne en environnement on-premise sans infrastructure supplémentaire
- Testable sans infrastructure (répertoire temporaire en test)

**Inconvénients critiques :**
- **SPoF potentiel :** Un système de fichiers partagé (NFS) est un SPoF classique. Sa défaillance affecte tous les vérificateurs
- **Latence de lecture :** Les I/O réseau sur NFS peuvent être significatives et non déterministes
- **Gestion de la concurrence :** La mise à jour du fichier lors d'une rotation de clé doit être atomique, ce qui est complexe sur NFS
- **Absence d'authentification :** N'importe quel processus ayant accès au FS peut modifier les clés publiques → violation de l'invariant d'intégrité
- **Scalabilité limitée :** NFS ne scale pas horizontalement pour des milliers de vérificateurs en lecture intensive

**Compatibilité PROTOCOL_V4 :**  
Conditionnelle. Compatible si le FS partagé n'est utilisé que pour peupler le cache local (pas d'accès sur le chemin critique). Incompatible si les lectures FS sont synchrones lors de `resolve()`. L'absence d'authentification de la source est une violation directe de l'invariant d'intégrité.

**Verdict :** Acceptable uniquement pour des environnements simples et de faible criticité, avec un cache local en mémoire et une validation cryptographique des fichiers (signature du contenu). Non recommandé pour la production.

---

### 5.2 Bases de données relationnelles (PostgreSQL, MySQL, etc.)

#### 5.2.1 Schéma proposé

```sql
CREATE TABLE trust_entries (
    subject          VARCHAR(255) PRIMARY KEY,
    public_key       TEXT NOT NULL,
    algorithm        VARCHAR(50) NOT NULL,
    not_before       TIMESTAMP WITH TIME ZONE NOT NULL,
    not_after        TIMESTAMP WITH TIME ZONE NOT NULL,
    version          BIGINT NOT NULL DEFAULT 1,
    fingerprint      VARCHAR(64) NOT NULL,
    issuer_signature TEXT NOT NULL,
    published_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    is_revoked       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_subject_version UNIQUE (subject, version)
);

CREATE INDEX idx_trust_entries_not_after ON trust_entries(not_after);
CREATE INDEX idx_trust_entries_subject_active ON trust_entries(subject) 
    WHERE is_revoked = FALSE;
```

#### 5.2.2 Analyse

**Avantages :**
- Infrastructure existante dans la plupart des organisations
- Transactions ACID pour les rotations de clés atomiques
- Requêtes flexibles (ex: filtrer par date d'expiration)
- Audit trail natif
- Haute disponibilité avec réplication (read replicas)

**Inconvénients :**
- **SPoF** sans réplication : si la DB primaire tombe, les mises à jour de cache sont bloquées (mais pas la vérification si le cache local est chaud)
- **Latence** : même avec un ORM bien configuré et un pool de connexions, une requête DB est 10-100x plus lente qu'une lecture en mémoire
- **Dépendance opérationnelle** : nécessite une DB managée ou des compétences DBA
- **Sécurité** : les credentials DB doivent être gérés et rotés → complexité opérationnelle

**Performance à chaud :** Si la DB n'est consultée que hors chemin critique (rafraîchissement de cache), les performances sont acceptables. Une lecture DB sur le chemin critique est inacceptable.

**Compatibilité PROTOCOL_V4 :** Compatible avec un cache local en mémoire. Incompatible si la DB est interrogée synchronement lors de `resolve()`.

**Verdict :** Solution viable pour des environnements ayant déjà une infrastructure DB, mais introduit des dépendances opérationnelles significatives. L'avantage par rapport au TAD est minimal puisqu'une DB relationnelle n'est pas intrinsèquement meilleure pour ce cas d'usage qu'un service HTTP dédié, et elle est moins bien adaptée à la gestion des TTL et des invalidations.

---

### 5.3 Bases de données NoSQL / Clé-Valeur (Redis, DynamoDB, etc.)

#### 5.3.1 Redis

```java
public class RedisTrustRootProvider implements TrustRootProvider {
    private final RedisClient redisClient;
    
    @Override
    public Optional<TrustEntry> fetch(String subject) {
        String json = redisClient.get("trust:" + subject);
        if (json == null) return Optional.empty();
        return Optional.of(deserialize(json));
    }
    
    @Override
    public void publish(TrustEntry entry) {
        String json = serialize(entry);
        long ttlSeconds = entry.notAfter().getEpochSecond() - Instant.now().getEpochSecond();
        redisClient.setex("trust:" + subject, ttlSeconds, json);
    }
}
```

**Avantages :**
- TTL natif → expiration automatique des clés
- Très faible latence (< 1ms en LAN)
- Pub/Sub pour les notifications d'invalidation de cache
- Clustering Redis pour la haute disponibilité
- Données en mémoire → lecture très rapide

**Inconvénients :**
- **Volatile par défaut :** Redis en mode mémoire seule perd les données au redémarrage → problème pour le cache local persistant. Requiert persistance (AOF/RDB) configurée
- **Sécurité :** Redis sans authentification (AUTH) est une surface d'attaque majeure
- **SPoF :** Un Redis unique est un SPoF classique. Redis Cluster ou Redis Sentinel requis
- **Aucune validation cryptographique native** : Redis ne vérifie pas les `issuerSignature`

**Note critique :** Redis, utilisé comme cache distribué partagé entre vérificateurs, n'est pas un cache **local** au sens de `PROTOCOL_V4`. C'est un cache **réseau partagé**. Si Redis est indisponible, tous les vérificateurs dont le cache en mémoire est expiré sont simultanément impactés. C'est **structurellement différent** d'un cache local par service.

**Verdict :** Redis est pertinent comme **couche de cache secondaire** entre le TAD et les caches locaux en mémoire des vérificateurs, pas comme remplacement du cache local. L'utiliser comme seul mécanisme de `TrustRoot` est une violation du principe de fonctionnement hors ligne.

---

### 5.4 Services HTTP distribués (sans TAD centralisé)

#### 5.4.1 Description

Chaque microservice émetteur expose son propre endpoint HTTP pour distribuer sa clé publique :

```
GET https://payments-service.internal/.well-known/trust-root
{
  "subject": "payments-service",
  "publicKey": "...",
  "algorithm": "Ed25519",
  "notAfter": "2024-12-31T23:59:59Z"
}
```

Pattern inspiré de JWKS (`/.well-known/jwks.json`).

#### 5.4.2 Analyse

**Avantages :**
- Décentralisé par nature → pas de SPoF central
- Chaque service contrôle sa propre clé → cohérent avec `PROTOCOL_V4`
- Standard émergent (cf. OAuth 2.0 JWKS)

**Inconvénients critiques :**
- **Couplage réseau direct :** Le vérificateur doit connaître l'adresse de chaque émetteur pour résoudre sa clé. Dans une architecture à milliers de microservices, cela crée un graphe de dépendances réseau exponentiel
- **SPoF distribué :** Si le service `payments-service` est indisponible, sa clé ne peut pas être rafraîchie. Bien que le cache local absorbe l'impact immédiat, c'est une fragilité
- **Discovery problem :** Comment un vérificateur connaît-il l'URL de chaque émetteur ? Cela nécessite un service de découverte, qui redevient un SPoF
- **Charge sur les émetteurs :** Les émetteurs doivent traiter les requêtes de résolution de clés en plus de leur charge applicative

**Verdict :** Intéressant conceptuellement mais pratiquement difficile à opérer à grande échelle. Convient pour des architectures simples avec peu d'émetteurs et des URLs stables. Non recommandé pour une adoption généraliste dans `veridot-trustroots`.

---

### 5.5 KMS (AWS KMS, Azure Key Vault, Google Cloud KMS, HashiCorp Vault)

#### 5.5.1 Analyse de la compatibilité avec PROTOCOL_V4

**Point critique :** `PROTOCOL_V4` stipule que la **clé privée long terme ne quitte jamais le microservice émetteur**. Les KMS cloud (AWS KMS, Azure Key Vault, GCP KMS) génèrent et détiennent eux-mêmes les clés privées : l'émetteur ne possède pas la clé, il délègue les opérations cryptographiques au KMS. C'est une violation directe du principe de `PROTOCOL_V4`.

**Cas HashiCorp Vault :** Vault offre un mode différent. Il peut stocker des clés générées localement (Transit secrets engine avec import, ou simple stockage de secret). Dans ce cas :
- L'émetteur génère sa paire de clés localement ✅
- La clé privée peut être stockée dans Vault pour sécurisation ✅ (bien qu'elle quitte techniquement le processus)
- Vault distribue la clé publique aux vérificateurs via son API ✅

**Verdict KMS cloud (AWS/Azure/GCP) :** Incompatible avec l'invariant de `PROTOCOL_V4` sur la non-divulgation de la clé privée, sauf utilisation exclusivement pour stocker et distribuer des clés publiques (rôle de simple annuaire). Dans ce cas, le KMS est sur-dimensionné pour ce besoin.

**Verdict HashiCorp Vault :** Compatible si utilisé comme stockage sécurisé de la clé publique uniquement, ou si l'émetteur importe lui-même sa clé privée (usage atypique). Introduit une dépendance lourde et un coût opérationnel élevé pour un bénéfice discutable par rapport au TAD.

**Latence :** AWS KMS : ~1-5ms par requête. Azure Key Vault : ~2-10ms. Ces latences sont inacceptables si le KMS est interrogé sur le chemin critique, mais acceptables si la résolution est cachée localement.

**Quand un KMS est-il pertinent ?**
- Quand une organisation a déjà déployé Vault pour la gestion des secrets et veut éviter un nouveau service
- Quand la politique de sécurité impose l'utilisation d'un HSM certifié pour toutes les opérations cryptographiques
- **Jamais** pour la vérification en temps réel sans cache local

---

### 5.6 Hazelcast / Caches distribués

#### 5.6.1 Description

Hazelcast IMap partagée entre tous les membres du cluster pour stocker les `TrustEntry`.

```java
public class HazelcastTrustRoot implements TrustRoot {
    private final IMap<String, TrustEntry> trustMap;
    
    @Override
    public PublicKey resolve(String subject) throws TrustRootException {
        TrustEntry entry = trustMap.get(subject); // Peut être local ou réseau
        if (entry == null) throw new TrustRootException("Unknown: " + subject);
        if (entry.isExpired()) throw new TrustRootException("Expired: " + subject);
        return entry.publicKey();
    }
}
```

**Avantages :**
- Données partitionnées automatiquement → scalabilité horizontale
- Cohérence configurable (eventual vs. strong)
- Near-cache Hazelcast pour lecture en mémoire locale

**Inconvénients :**
- Si les données ne sont pas dans le near-cache, la lecture traverse le réseau → latence
- Hazelcast cluster lui-même peut devenir un SPoF si mal configuré
- Complexité opérationnelle importante

**Distinction critique :** Hazelcast avec near-cache activé est architecturalement similaire à un cache local + cache distribué en backend. La lecture est locale (near-cache) → compatible avec le principe de non-latence sur le chemin critique. Acceptable dans ce mode.

---

### 5.7 Combinaisons hybrides

L'analyse révèle qu'aucun mécanisme unique n'est optimal pour tous les critères. La combinaison recommandée est :

```
Cache local en mémoire (L1, toujours présent)
    ↕ miss / expiration
Cache local persistant (L2, fichier ou SQLite local)
    ↕ miss / expiration  
Provider distant (TAD, Vault, DB, Redis) — jamais sur chemin critique
```

Cette architecture en couches est la seule compatible avec l'ensemble des invariants de `PROTOCOL_V4`.

---

## 6. Comparaison exhaustive

### 6.1 Tableau comparatif

| Critère | TAD dédié | Base de données relationnelle | Redis / Hazelcast | Fichiers partagés | KMS cloud | HTTP par service | HashiCorp Vault |
|---|---|---|---|---|---|---|---|
| **Compatibilité PROTOCOL_V4** | ✅ Excellente | ✅ Bonne | ⚠️ Conditionnelle | ⚠️ Conditionnelle | ❌ Partielle | ✅ Bonne | ⚠️ Conditionnelle |
| **Conformité architecture** | ✅ Native | ✅ Adaptée | ✅ Adaptée | ⚠️ Limitée | ❌ Violation clé privée | ✅ Adaptée | ⚠️ Complexe |
| **Simplicité d'intégration** | ⚠️ Moyenne | ✅ Bonne | ✅ Bonne | ✅ Excellente | ❌ Complexe | ✅ Bonne | ❌ Complexe |
| **Facilité d'utilisation** | ✅ Bonne avec SDK | ✅ Familière | ✅ Familière | ✅ Excellente | ❌ API spécifique | ✅ Standard | ❌ Apprentissage |
| **Performances (chemin critique)** | ✅ Cache local | ✅ Cache local | ✅ Near-cache | ✅ Cache local | ✅ Cache local | ✅ Cache local | ✅ Cache local |
| **Latence de rafraîchissement** | ✅ < 10ms | ⚠️ 1-50ms | ✅ < 1ms | ⚠️ Variable NFS | ❌ 1-10ms AWS | ✅ < 10ms | ⚠️ 5-20ms |
| **Disponibilité** | ✅ Cluster | ⚠️ Réplication req. | ✅ Cluster | ❌ SPoF NFS | ✅ Haute dispo AWS | ❌ Dépend service | ✅ HA possible |
| **Résilience** | ✅ Haute | ⚠️ Moyenne | ✅ Haute | ❌ Faible | ✅ Haute | ⚠️ Faible | ✅ Haute |
| **Sécurité** | ✅ mTLS + signature | ⚠️ Credentials DB | ⚠️ AUTH Redis | ❌ Aucune native | ✅ Excellente | ✅ TLS | ✅ Excellente |
| **Résistance aux attaques** | ✅ issuerSignature | ⚠️ Dépend config | ❌ Sans signature | ❌ Faible | ✅ HSM | ✅ Avec signature | ✅ Haute |
| **Risque SPoF** | ✅ Faible (cluster) | ⚠️ Moyen | ✅ Faible | ❌ Élevé | ✅ Faible | ⚠️ Moyen | ✅ Faible |
| **Fonctionnement hors ligne** | ✅ Cache local | ✅ Cache local | ⚠️ Near-cache | ✅ Cache local | ✅ Cache local | ✅ Cache local | ✅ Cache local |
| **Facilité de déploiement** | ⚠️ Nouveau service | ✅ Existant souvent | ✅ Existant souvent | ✅ Simple | ❌ Cloud requis | ✅ Simple | ❌ Complexe |
| **Complexité opérationnelle** | ⚠️ Moyenne | ⚠️ Moyenne | ⚠️ Moyenne | ✅ Faible | ❌ Élevée | ✅ Faible | ❌ Élevée |
| **Testabilité** | ✅ Excellente | ✅ Bonne | ✅ Bonne | ✅ Excellente | ⚠️ Mocking requis | ✅ Bonne | ⚠️ Testcontainers |
| **Évolutivité** | ✅ Excellente | ⚠️ Limitée | ✅ Excellente | ❌ Faible | ✅ Bonne | ⚠️ Moyenne | ✅ Bonne |
| **Maintenance** | ⚠️ Nouveau service | ✅ Standard DBA | ✅ Standard | ✅ Minimale | ❌ Vendor lock-in | ✅ Simple | ⚠️ Vault expertise |
| **Coût opérationnel** | ⚠️ Moyen | ✅ Faible (existant) | ✅ Faible (existant) | ✅ Minimal | ❌ Élevé (à l'usage) | ✅ Minimal | ❌ Élevé (licence) |
| **Cloud/On-premise** | ✅ Universel | ✅ Universel | ✅ Universel | ✅ On-premise | ❌ Cloud only | ✅ Universel | ✅ Universel |
| **Milliers de microservices** | ✅ Conçu pour | ⚠️ Avec sharding | ✅ Natif | ❌ Non | ✅ Oui | ❌ Non | ✅ Oui |

### 6.2 Analyse des cas d'usage

**TAD dédié :** Optimal pour les organisations qui adoptent Veridot à grande échelle, avec de nombreux microservices et des exigences de sécurité élevées. Justifie l'investissement opérationnel dès qu'on dépasse ~20 microservices.

**Base de données relationnelle :** Optimal pour les organisations qui ont déjà une infrastructure DB robuste et un nombre limité de microservices (< 100). Simple à adopter sans nouveau service.

**Redis :** Optimal comme couche intermédiaire (cache secondaire entre TAD et cache local). Ne doit pas être le seul mécanisme de résolution.

**Fichiers partagés :** Acceptable uniquement pour des déploiements de développement ou de test. Non recommandé en production.

**KMS cloud :** Pertinent uniquement si l'organisation impose l'usage d'un KMS pour toutes les opérations cryptographiques ET si le KMS est utilisé exclusivement comme stockage de clés publiques (rôle d'annuaire). Sur-dimensionné et inadapté dans la plupart des cas.

**HTTP par service :** Viable pour des architectures simples (< 10 services) avec des URLs stables et un service de découverte existant.

**HashiCorp Vault :** Pertinent dans les organisations qui utilisent déjà Vault comme gestionnaire de secrets central. Évite un nouveau service mais introduit une dépendance lourde.

---

## 7. Analyse des risques et implications de sécurité

### 7.1 Risques selon les implémentations

#### 7.1.1 Substitution de clé publique

**Risque :** Un attaquant remplace la clé publique d'un émetteur légitime par sa propre clé publique. Cela lui permet de forger des tokens qui seront acceptés par les vérificateurs.

**Vecteurs par mécanisme :**
- Fichiers partagés : modification directe du fichier si les permissions le permettent
- Base de données : injection SQL ou compromission des credentials
- Redis : accès direct si AUTH non configuré ou si réseau interne compromis
- TAD : nécessite de compromettre le mécanisme mTLS + contourner la validation de `issuerSignature`
- HTTP par service : MITM sur le canal HTTP si TLS mal configuré

**Contre-mesure universelle :** La `issuerSignature` dans le modèle de données du TAD. Chaque `TrustEntry` est signée par la clé privée de l'émetteur. Même si un attaquant injecte une fausse clé publique, la signature sera invalide. **Cette contre-mesure doit être implémentée dans toutes les variantes du module.**

#### 7.1.2 Replay de clé révoquée

**Risque :** Un attaquant réutilise une ancienne clé publique (d'avant une rotation) pour valider des tokens forgés avec l'ancienne clé privée (si celle-ci a été compromise).

**Contre-mesure :** Le champ `version` dans le modèle de données permet de détecter une clé obsolète. Le vérificateur doit rejeter les clés dont la version est inférieure à la version actuelle connue.

#### 7.1.3 Épuisement de TTL (cache poisoning temporel)

**Risque :** Si le TTL du cache local est trop long, une clé révoquée continue d'être acceptée pendant la durée du TTL.

**Contre-mesure :** Le TAD doit exposer un endpoint de révocation avec notification push (webhook) ou polling court (< 60 secondes). La durée maximale pendant laquelle une clé révoquée peut être acceptée doit être documentée et configurable.

#### 7.1.4 Déni de service sur le TAD

**Risque :** Un attaquant surcharge le TAD, empêchant les vérificateurs de rafraîchir leurs caches.

**Impact :** Limité si les caches locaux sont chauds. Critique au démarrage à froid.

**Contre-mesure :** Rate limiting sur le TAD, authentification des requêtes de lecture, circuit breaker dans l'implémentation de `TrustRoot`.

### 7.2 Matrice des risques

| Risque | Probabilité | Impact | Mécanismes de mitigation |
|---|---|---|---|
| Substitution de clé publique | Faible (si mTLS) | Critique | issuerSignature, mTLS, autorisation |
| Compromission TAD | Très faible | Élevé | issuerSignature (détection) + audit |
| Cache poisoning | Faible | Moyen | TTL court, validation signature |
| Replay clé révoquée | Faible | Élevé | Version field, révocation active |
| DDoS TAD | Moyen | Faible (cache chaud) | Cache local, rate limiting |
| Fuite clé privée émetteur | Très faible | Critique | Hors périmètre TrustRoot |

---

## 8. Analyse des performances et scalabilité

### 8.1 Chemin critique de vérification

Conformément à `PROTOCOL_V4`, la vérification d'un token doit être une opération à **très faible latence**. Le chemin critique est :

```
resolve(subject) → cache local L1 (mémoire)
```

**Latence cible :** < 100µs (lecture HashMap en mémoire)  
**Latence observée avec cache chaud :** ~1-10µs (ConcurrentHashMap lookup)

Toute implémentation qui sort du cache L1 sur le chemin critique dégrade cette cible.

### 8.2 Chemin de rafraîchissement (hors critique)

```
Thread de rafraîchissement → TAD / DB / Redis → Cache L2 (persistant) → Cache L1 (mémoire)
```

Ce chemin est **hors du chemin critique** et sa latence est acceptable jusqu'à ~500ms dans la plupart des configurations.

### 8.3 Démarrage à froid

| Mécanisme | Latence démarrage à froid | Disponibilité si provider absent |
|---|---|---|
| TAD (cluster) | ~100ms (chargement batch) | Snapshot distribué |
| Base de données | ~50-200ms | Indisponible si DB inaccessible |
| Redis | ~1-5ms | Indisponible si Redis inaccessible |
| Fichiers | ~10-50ms | Indisponible si FS inaccessible |
| Cache L2 seul | ~10ms | ✅ Autonome |

**Recommandation :** Toujours pré-peupler le cache L2 persistant local lors du démarrage à froid avant d'accepter les requêtes. Le service ne doit pas démarrer en mode "prêt" si le cache ne contient aucune clé (circuit breaker au démarrage).

### 8.4 Scalabilité horizontale

Avec un cache local par instance de vérificateur :
- **Aucune corrélation** entre le nombre de vérificateurs et la charge sur le TAD
- Le TAD est consulté uniquement pour les rafraîchissements, à une fréquence proportionnelle au nombre de clés distinctes (pas au volume de vérifications)
- Pour 10 000 vérificateurs et 500 émetteurs avec un TTL de cache de 1 heure : ~500 requêtes/heure sur le TAD, indépendamment du volume de vérifications

Cette propriété est fondamentale : le TAD **ne scale pas avec le volume de vérifications**, seulement avec le nombre de clés distinctes et la fréquence de rotation.

---

## 9. Recommandations argumentées

### 9.1 Recommandation principale

**Créer le module `veridot-trustroots` avec une architecture en couches obligatoires.**

L'invariant architectural central est :

> **Le cache local persistant (L2) est obligatoire. Le cache en mémoire (L1) est obligatoire. Le provider distant est facultatif et jamais invoqué sur le chemin critique.**

Toute implémentation de `TrustRoot` dans le module doit respecter ce pattern, sans exception.

### 9.2 Priorité d'implémentation des providers

**Priorité 1 :** Provider fichier local JSON (le plus simple, zéro dépendance)  
**Priorité 2 :** Provider HTTP générique (compatible avec TAD et endpoints JWKS)  
**Priorité 3 :** Provider JDBC (compatible avec toute base de données relationnelle)  
**Priorité 4 :** Provider Redis  
**Priorité 5 :** Provider TAD natif (si TAD est déployé avec le module compagnon)  
**Priorité 6 :** Provider HashiCorp Vault  

### 9.3 Sur le TAD

**Le TAD est recommandé** pour les déploiements à grande échelle (> 20 microservices) avec des exigences de sécurité élevées. C'est l'architecture qui offre le meilleur ratio sécurité/opérationnel à grande échelle.

**Le TAD n'est pas obligatoire.** Les providers DB et Redis sont des alternatives valables pour des organisations ayant déjà ces infrastructures.

### 9.4 Améliorations de l'interface `TrustRoot` sans rupture de compatibilité

**Sans modifier `TrustRoot` :**  
Ajouter une interface `TrustRootProvider` interne au module, enrichie :

```java
public interface TrustRootProvider {
    Optional<TrustEntry> fetch(String subject);
    Optional<TrustEntry> fetchIfNewerThan(String subject, long version);
    List<TrustEntry> fetchModifiedSince(Instant since);
    // Pas de publish ici : la publication est hors périmètre du vérificateur
}
```

**Optionnellement, une interface `TrustRootLifecycle` :**

```java
public interface ObservableTrustRoot extends TrustRoot {
    void addChangeListener(TrustRootChangeListener listener);
    void removeChangeListener(TrustRootChangeListener listener);
}
```

Ces interfaces sont internes au module et ne modifient pas l'interface `TrustRoot` de Veridot core.

---

## 10. Proposition d'architecture détaillée du module `veridot-trustroots`

### 10.1 Structure Maven

```
veridot-trustroots/
├── veridot-trustroots-core/           # Abstractions internes, cache L1/L2, sans dépendances externes
├── veridot-trustroots-file/           # Provider fichier local JSON/YAML
├── veridot-trustroots-http/           # Provider HTTP générique (Jackson + OkHttp/HttpClient)
├── veridot-trustroots-jdbc/           # Provider JDBC (dépendance: JDBC API uniquement)
├── veridot-trustroots-redis/          # Provider Redis (dépendance: Lettuce ou Jedis)
├── veridot-trustroots-vault/          # Provider HashiCorp Vault (dépendance: Spring Vault ou Vault Java Driver)
├── veridot-trustroots-tad/            # Provider TAD natif + client TAD
├── veridot-trustroots-tad-server/     # Implémentation serveur TAD (Spring Boot, optionnel)
├── veridot-trustroots-spring/         # Auto-configuration Spring Boot
└── veridot-trustroots-testing/        # Utilitaires de test (TrustRoot mocks, builders, etc.)
```

**Justification de la séparation :** Chaque module introduit uniquement les dépendances nécessaires. Un utilisateur qui n'utilise que le provider JDBC n'embarque pas Lettuce ou le client Vault. Cela respecte le principe de modularité et évite d'alourdir le cœur de Veridot.

### 10.2 Organisation des packages

```
io.github.cyfko.veridot.trustroots/
├── core/
│   ├── TrustRootProvider.java         # SPI interne
│   ├── TrustEntry.java                # Modèle canonique
│   ├── TrustRootException.java        # Hiérarchie d'exceptions enrichie
│   ├── CachingTrustRoot.java          # Implémentation TrustRoot avec cache L1/L2
│   ├── cache/
│   │   ├── L1MemoryCache.java         # Cache en mémoire (ConcurrentHashMap)
│   │   ├── L2PersistentCache.java     # Interface cache persistant
│   │   ├── L2FileCache.java           # Implémentation fichier local
│   │   └── L2SqliteCache.java         # Implémentation SQLite local
│   └── validation/
│       ├── TrustEntryValidator.java   # Validation des TrustEntry (signature, dates)
│       └── SignatureVerifier.java     # Vérification de l'issuerSignature
├── file/
│   └── FileTrustRootProvider.java
├── http/
│   ├── HttpTrustRootProvider.java
│   └── HttpTrustRootConfig.java
├── jdbc/
│   ├── JdbcTrustRootProvider.java
│   └── JdbcTrustRootConfig.java
├── redis/
│   ├── RedisTrustRootProvider.java
│   └── RedisTrustRootConfig.java
├── vault/
│   ├── VaultTrustRootProvider.java
│   └── VaultTrustRootConfig.java
├── tad/
│   ├── TadTrustRootProvider.java      # Client TAD
│   ├── TadClient.java                 # Client HTTP TAD
│   └── TadConfig.java
└── spring/
    ├── TrustRootsAutoConfiguration.java
    └── TrustRootsProperties.java
```

### 10.3 Interface SPI principale

```java
/**
 * SPI interne du module veridot-trustroots.
 * Les implémenteurs de ce SPI fournissent la résolution distante des clés.
 * Ce SPI n'est JAMAIS invoqué sur le chemin critique de vérification.
 * Il est utilisé exclusivement par le thread de rafraîchissement de cache.
 */
public interface TrustRootProvider {
    
    /**
     * Récupère une TrustEntry pour le sujet donné.
     * Cette méthode peut effectuer des appels réseau.
     * Elle n'est JAMAIS appelée lors de la vérification d'un token.
     *
     * @param subject Identifiant du microservice émetteur
     * @return Optional.empty() si le sujet est inconnu
     * @throws TrustRootProviderException en cas d'erreur technique
     */
    Optional<TrustEntry> fetch(String subject) throws TrustRootProviderException;
    
    /**
     * Récupère toutes les entrées modifiées depuis un instant donné.
     * Utilisé pour la synchronisation incrémentale du cache.
     * Implémentation optionnelle (default: empty list).
     */
    default List<TrustEntry> fetchModifiedSince(Instant since) {
        return Collections.emptyList();
    }
    
    /**
     * Récupère par lot les entrées pour les sujets donnés.
     * Optimisation pour réduire les allers-retours réseau.
     * Implémentation par défaut: appels séquentiels.
     */
    default Map<String, TrustEntry> fetchBatch(Collection<String> subjects) {
        Map<String, TrustEntry> result = new HashMap<>();
        for (String subject : subjects) {
            fetch(subject).ifPresent(entry -> result.put(subject, entry));
        }
        return result;
    }
    
    /**
     * Nom lisible de ce provider (pour logs et métriques).
     */
    String name();
}
```

### 10.4 Implémentation centrale : `CachingTrustRoot`

```java
/**
 * Implémentation de TrustRoot avec cache L1/L2.
 * INVARIANT FONDAMENTAL : resolve() ne fait JAMAIS d'appel réseau.
 * Le rafraîchissement est asynchrone et géré par un thread dédié.
 */
public final class CachingTrustRoot implements TrustRoot, AutoCloseable {
    
    private final L1MemoryCache l1Cache;
    private final L2PersistentCache l2Cache;
    private final TrustRootProvider provider;
    private final TrustEntryValidator validator;
    private final ScheduledExecutorService refreshExecutor;
    private final CachingTrustRootConfig config;
    private final MeterRegistry meterRegistry; // Optionnel pour les métriques
    
    @Override
    public PublicKey resolve(String subject) throws TrustRootException {
        // Étape 1 : Cache L1 (mémoire) — aucune I/O
        Optional<CachedKey> l1 = l1Cache.get(subject);
        if (l1.isPresent() && !l1.get().isExpired()) {
            meterRegistry.counter("trustroot.cache.l1.hit").increment();
            return l1.get().publicKey();
        }
        
        // Étape 2 : Cache L2 (persistant local) — I/O locale uniquement
        Optional<TrustEntry> l2 = l2Cache.get(subject);
        if (l2.isPresent() && !l2.get().isExpired()) {
            meterRegistry.counter("trustroot.cache.l2.hit").increment();
            CachedKey key = promoteToL1(l2.get());
            // Déclencher un rafraîchissement proactif si le TTL est proche
            scheduleRefreshIfNeeded(subject, l2.get());
            return key.publicKey();
        }
        
        // Étape 3 : Miss total — lever une exception
        // Le provider distant NE DOIT PAS être consulté ici.
        // Si ce cas se produit en production, c'est que le rafraîchissement
        // n'a pas fonctionné correctement (bug ou configuration incorrecte).
        meterRegistry.counter("trustroot.cache.miss").increment();
        
        // Exception avec contexte enrichi pour faciliter le diagnostic
        throw new TrustRootException(
            "No trusted key found for subject '" + subject + "'. " +
            "This indicates the cache was not properly populated. " +
            "Last sync: " + l2Cache.lastSyncTime()
        );
    }
    
    /**
     * Initialisation : peuple le cache depuis L2 puis depuis le provider.
     * Appelé au démarrage, avant d'accepter des requêtes.
     * PEUT effectuer des appels réseau.
     */
    public void initialize() throws TrustRootInitializationException {
        // Charger L2 en mémoire (L1)
        l2Cache.loadAll().forEach(this::promoteToL1);
        
        // Si L1 est vide, tenter une synchronisation depuis le provider
        if (l1Cache.isEmpty()) {
            try {
                synchronizeFromProvider();
            } catch (TrustRootProviderException e) {
                throw new TrustRootInitializationException(
                    "Cache is empty and provider is unavailable. " +
                    "Cannot initialize TrustRoot.", e
                );
            }
        }
        
        // Démarrer le thread de rafraîchissement
        startRefreshScheduler();
    }
    
    private void synchronizeFromProvider() throws TrustRootProviderException {
        Instant lastSync = l2Cache.lastSyncTime().orElse(Instant.EPOCH);
        List<TrustEntry> updates = provider.fetchModifiedSince(lastSync);
        
        for (TrustEntry entry : updates) {
            // VALIDATION CRITIQUE : vérifier la issuerSignature
            validator.validate(entry); // Lève une exception si invalide
            l2Cache.put(entry);
            promoteToL1(entry);
        }
        
        l2Cache.markSyncTime(Instant.now());
    }
    
    private void scheduleRefreshIfNeeded(String subject, TrustEntry entry) {
        Duration remaining = Duration.between(Instant.now(), entry.notAfter());
        Duration threshold = config.getRefreshThreshold(); // ex: 20% de la durée totale
        
        if (remaining.compareTo(threshold) < 0) {
            refreshExecutor.submit(() -> refreshSubject(subject));
        }
    }
    
    private void refreshSubject(String subject) {
        try {
            Optional<TrustEntry> fresh = provider.fetch(subject);
            fresh.ifPresent(entry -> {
                validator.validate(entry);
                l2Cache.put(entry);
                promoteToL1(entry);
            });
        } catch (Exception e) {
            // Log mais ne pas propager : le cache existant reste valide
            log.warn("Failed to refresh trust entry for '{}': {}", subject, e.getMessage());
        }
    }
    
    @Override
    public void close() {
        refreshExecutor.shutdown();
    }
}
```

### 10.5 Configuration

```java
@ConfigurationProperties(prefix = "veridot.trustroots")
public class TrustRootsProperties {
    
    private CacheProperties cache = new CacheProperties();
    private ProviderProperties provider = new ProviderProperties();
    
    public static class CacheProperties {
        /** Durée de vie en mémoire (L1). Si null, utilise notAfter de l'entrée. */
        private Duration l1Ttl = null;
        
        /** Seuil de rafraîchissement proactif (ex: PT1H = 1 heure avant expiration) */
        private Duration refreshThreshold = Duration.ofHours(1);
        
        /** Répertoire du cache persistant L2 */
        private Path l2Directory = Path.of(System.getProperty("user.home"), ".veridot", "trust-cache");
        
        /** Intervalle de synchronisation complète (full sync) */
        private Duration fullSyncInterval = Duration.ofHours(6);
        
        /** Tolérance aux clés expirées (stale-while-revalidate) */
        private Duration staleWhileRevalidate = Duration.ofMinutes(5);
    }
    
    public static class ProviderProperties {
        /** Type de provider: file, http, jdbc, redis, vault, tad */
        private String type = "file";
        
        // Propriétés spécifiques à chaque provider
        private FileProviderProperties file = new FileProviderProperties();
        private HttpProviderProperties http = new HttpProviderProperties();
        private JdbcProviderProperties jdbc = new JdbcProviderProperties();
        private RedisProviderProperties redis = new RedisProviderProperties();
        private TadProviderProperties tad = new TadProviderProperties();
        private VaultProviderProperties vault = new VaultProviderProperties();
    }
}
```

### 10.6 Auto-configuration Spring Boot

```java
@AutoConfiguration
@ConditionalOnClass(TrustRoot.class)
@EnableConfigurationProperties(TrustRootsProperties.class)
public class TrustRootsAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean(TrustRoot.class)
    public TrustRoot trustRoot(
            TrustRootsProperties properties,
            Optional<TrustRootProvider> customProvider,
            Optional<MeterRegistry> meterRegistry) {
        
        TrustRootProvider provider = customProvider
            .orElseGet(() -> buildProviderFromProperties(properties));
        
        return CachingTrustRoot.builder()
            .provider(provider)
            .config(properties.getCache())
            .meterRegistry(meterRegistry.orElse(new SimpleMeterRegistry()))
            .build();
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "veridot.trustroots.provider", name = "type", havingValue = "http")
    @ConditionalOnMissingBean(TrustRootProvider.class)
    public TrustRootProvider httpTrustRootProvider(TrustRootsProperties properties) {
        return new HttpTrustRootProvider(properties.getProvider().getHttp());
    }
    
    @Bean
    @ConditionalOnProperty(prefix = "veridot.trustroots.provider", name = "type", havingValue = "tad")
    @ConditionalOnMissingBean(TrustRootProvider.class)
    public TrustRootProvider tadTrustRootProvider(TrustRootsProperties properties) {
        return new TadTrustRootProvider(properties.getProvider().getTad());
    }
    
    // ... autres providers
}
```

### 10.7 Module de test

```java
/**
 * Utilitaires de test pour les implémentations de TrustRoot.
 * Dépendance à portée test uniquement.
 */
public final class TrustRootTestSupport {
    
    /** Crée un TrustRoot en mémoire pour les tests unitaires */
    public static TrustRoot inMemory(Map<String, PublicKey> keys) {
        return subject -> Optional.ofNullable(keys.get(subject))
            .orElseThrow(() -> new TrustRootException("Unknown subject: " + subject));
    }
    
    /** Crée un TrustRootProvider en mémoire pour les tests d'intégration */
    public static TrustRootProvider inMemoryProvider() {
        return new InMemoryTrustRootProvider();
    }
    
    /** Builder pour des TrustEntry de test */
    public static TrustEntry.Builder trustEntry(String subject) {
        return TrustEntry.builder()
            .subject(subject)
            .notBefore(Instant.now().minus(Duration.ofHours(1)))
            .notAfter(Instant.now().plus(Duration.ofDays(30)));
    }
    
    /** 
     * TrustRoot qui simule une latence réseau pour les tests de performance.
     * NE PAS utiliser en production.
     */
    public static TrustRoot withLatency(TrustRoot delegate, Duration latency) {
        return subject -> {
            Thread.sleep(latency.toMillis());
            return delegate.resolve(subject);
        };
    }
}
```

### 10.8 Exemple d'utilisation

```yaml
# application.yml avec provider TAD
veridot:
  trustroots:
    cache:
      l1-ttl: PT30M
      refresh-threshold: PT1H
      l2-directory: /var/lib/myapp/trust-cache
      full-sync-interval: PT6H
      stale-while-revalidate: PT5M
    provider:
      type: tad
      tad:
        url: https://tad.internal:8443
        mtls:
          key-store: /etc/ssl/myapp/keystore.p12
          key-store-password: ${TAD_KEYSTORE_PASSWORD}
          trust-store: /etc/ssl/myapp/tad-truststore.p12
        connect-timeout: PT5S
        read-timeout: PT10S
        retry:
          max-attempts: 3
          backoff: PT1S
```

```java
// Utilisation manuelle (sans Spring Boot)
TrustRootProvider provider = TadTrustRootProvider.builder()
    .url("https://tad.internal:8443")
    .mtlsConfig(mtlsConfig)
    .build();

CachingTrustRoot trustRoot = CachingTrustRoot.builder()
    .provider(provider)
    .l2Cache(FileBasedL2Cache.at(Path.of("/var/lib/myapp/trust-cache")))
    .refreshThreshold(Duration.ofHours(1))
    .build();

trustRoot.initialize(); // Appel bloquant au démarrage

// Utilisation dans Veridot
Verifier verifier = Veridot.verifier(trustRoot);
```

### 10.9 Stratégie de versionnement et publication

**Versionnement :** Suivre le versionnement de Veridot avec un suffixe de module :
- `veridot-trustroots-1.x.y` compatible avec `veridot-core-1.x.y`
- API du SPI `TrustRootProvider` versionnée indépendamment

**Compatibilité :** La compatibilité avec les futures évolutions de Veridot est garantie par le fait que le module n'utilise que l'interface publique `TrustRoot` du core. Aucune dépendance sur les classes internes de Veridot.

**Publication Maven Central :** Séparée du core, pour permettre une adoption indépendante.

---

## 11. Conclusion motivée

### 11.1 Bilan de l'analyse

L'étude confirme que la création d'un module `veridot-trustroots` est **pertinente, faisable et architecturalement saine**. L'interface `TrustRoot` est suffisamment simple pour permettre des implémentations génériques, et les invariants de `PROTOCOL_V4` sont respectables par toutes les implémentations proposées, à condition de respecter l'invariant fondamental du cache local obligatoire sur le chemin critique.

### 11.2 Rectifications des hypothèses du prompt

Plusieurs hypothèses du prompt doivent être corrigées :

**Hypothèse incorrecte 1 :** *"Lors de la résolution d'un TrustRoot, l'implémentation consulte d'abord un cache local persistant ; si elle est absente, expirée ou invalide, elle est récupérée depuis le TAD"*

**Correction :** Cette description implique un appel réseau synchrone dans `resolve()` en cas de miss, ce qui viole l'invariant de non-latence sur le chemin critique. La résolution distante doit être **entièrement asynchrone et proactive**, jamais réactive à un miss en production.

**Hypothèse correcte à confirmer :** *"chaque microservice conserve exclusivement sa clé privée long terme ; celle-ci ne quitte jamais le microservice"* — Confirmé par `PROTOCOL_V4`. C'est l'invariant le plus important et il exclut les KMS cloud qui génèrent et détiennent les clés.

**Hypothèse à nuancer :** *"les KMS (AWS KMS, Azure Key Vault, Google Cloud KMS, HashiCorp Vault)"* — Les KMS cloud sont architecturalement incompatibles avec la propriété de non-divulgation de clé privée de `PROTOCOL_V4`. Vault est acceptable uniquement dans un rôle de stockage de clés publiques, pas pour la gestion des clés privées.

### 11.3 Recommandation finale

1. **Créer `veridot-trustroots`** avec l'architecture en couches décrite.
2. **L'invariant non négociable :** `resolve()` ne fait jamais d'appel réseau. Le rafraîchissement est 100% asynchrone.
3. **Provider recommandé par défaut :** `veridot-trustroots-file` pour la simplicité, `veridot-trustroots-http` pour la généricité, `veridot-trustroots-tad` pour la production à grande échelle.
4. **Le TAD est la meilleure abstraction** pour les déploiements à grande échelle : il centralise la validation d'authenticité, offre une API cohérente, et est conçu spécifiquement pour ce cas d'usage. Sa complexité opérationnelle est justifiée dès qu'on dépasse 20 microservices.
5. **Ne pas modifier l'interface `TrustRoot` du core.** Toutes les enrichissements se font dans le module via des interfaces internes.
6. **Valider systématiquement la `issuerSignature`** dans toutes les implémentations distantes : c'est la seule garantie cryptographique que la clé publique reçue est authentique, même si le provider de distribution est compromis.

La philosophie de `PROTOCOL_V4` — sécurité par défaut, fonctionnement hors ligne, pas de SPoF — est pleinement compatible avec l'existence d'un module `veridot-trustroots`, à condition que ce module soit conçu comme un **outil de peuplement de cache**, et jamais comme un composant sur le chemin critique de vérification.

---

*Document d'architecture — Version 1.0*  
*Basé exclusivement sur le code source `cyfko/veridot` et le document `PROTOCOL_V4`*