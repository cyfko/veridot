# Modèle Cryptographique

Veridot V4 repose sur un modèle cryptographique double couche pour garantir que même si le courtier de transport (Broker) est compromis, un attaquant ne peut ni forger d'identité ni falsifier l'état d'une session.

---

## 1. Registre des Algorithmes Asymétriques

Le protocole prend en charge quatre algorithmes de signature à clé publique, représentés par le champ `sigAlg` dans l'en-tête de l'enveloppe et le champ `alg` dans le payload `KEY_EPOCH` :

| Code | Algorithme | Description | Référence Standard |
|---|---|---|---|
| `0x01` | **RSA-SHA256** | Signature RSASSA-PKCS1-v1_5 classique | RFC 8017 |
| `0x02` | **ECDSA-SHA256** | Algorithme de courbe elliptique sur la courbe P-256 | FIPS 186-5 |
| `0x03` | **RSA-PSS** | RSA Signature Scheme with Appendix (probabiliste) | RFC 8017 / NIST SP 800-56B |
| `0x04` | **Ed25519** | Edwards-Curve Digital Signature Algorithm sur Curve25519 | RFC 8032 / NIST SP 800-186 |

> [!TIP]
> **L'usage d'Ed25519 (0x04) est fortement recommandé** pour les clés racines et éphémères. Il offre des signatures compactes et des vérifications rapides en temps constant, ce qui immunise le code contre les attaques par canal auxiliaire de cache (cache-timing attacks).

---

## 2. Hiérarchie et Pivotement des Clés

Veridot sépare l'autorité de signature en deux couches distinctes :

```
+---------------------------------------+
|  Clé Privée Racine KMS/HSM            |
+---------------------------------------+
                    | (signe la délégation)
                    v
+---------------------------------------+
|  Clé Privée Long Terme Déléguée       |
+---------------------------------------+
                    | (signe l'époque de clé)
                    v
+---------------------------------------+
|  Clé Privée Éphémère (Courte durée)   | (pivotée automatiquement)
+---------------------------------------+
                    | (signe le JWT applicatif)
                    v
          [Token Applicatif Signé]
```

1. **Clés Long Terme** : Gérées dans des modules sécurisés (HSM ou cloud KMS comme Vault). Elles signent les configurations, les capacités et les métadonnées d'époque des clés.
2. **Clés Éphémères** : Générées à la volée par le service `KeyRotationService` au sein du microservice Émetteur. Elles signent les tokens individuels des utilisateurs. Par défaut, elles pivotent toutes les **24 heures** (paramétrable via `VDOT_KEYS_ROTATION_MINUTES`).

---

## 3. Protection contre les Attaques par Confusion d'Algorithme

La confusion d'algorithme est une vulnérabilité classique où un attaquant exploite un verificateur pour valider un token asymétrique en utilisant un algorithme HMAC symétrique, ou substitue un algorithme faible.

Veridot V4 bloque structurellement cette attaque :
- **Cohérence d'Enveloppe** : Le bit 0 des drapeaux (`COMPACT_SIG`) doit correspondre à l'algorithme de signature `sigAlg`. Ed25519 (`0x04`) impose que ce bit soit à `1` ; RSA (`0x01` et `0x03`) impose que ce bit soit à `0`. Toute incohérence entraîne un rejet `V4005`.
- **Validation du Type d'Algorithme JWT** : Durant l'exécution de `TokenVerifier#verify`, le vérificateur extrait l'en-tête du JWT et s'assure que le champ `alg` correspond exactement au type de clé de l'époque `KEY_EPOCH` associée :
  - Si l'époque indique `0x01` (RSA-SHA256) -> Le champ `alg` du JWT doit être `RS256`.
  - Si l'époque indique `0x02` (ECDSA-SHA256) -> Le champ `alg` du JWT doit être `ES256`.
  - Si l'époque indique `0x03` (RSA-PSS) -> Le champ `alg` du JWT doit être `PS256`.
  - Si l'époque indique `0x04` (Ed25519) -> Le champ `alg` du JWT doit être `EdDSA`.
  - Toute différence provoque le rejet immédiat du token, bloquant les attaques par substitution d'algorithme.

---

## 4. Protection contre les Attaques Temporelles (Side-Channel)

Pour contrer les attaques par mesure de temps de réponse, les vérificateurs de Veridot s'appuient sur des calculs cryptographiques à temps constant.

Les vérificateurs :
- Emploient des opérations de comparaison à temps constant pour valider les signatures des enveloppes.
- Évitent les structures conditionnelles basées sur le secret lors de l'extraction des données.
- Recommandent le format de clé `Ed25519` dont l'implémentation est naturellement immunisée contre les variations de temps de calcul du CPU.

---

## 5. Contraintes Temporelles et Dérive d'Horloge

La validation temporelle empêche le rejeu infini des métadonnées d'époque ou de liveness.

- **Marge de Dérive d'Horloge** : Veridot tolère un décalage d'horloge de **5 minutes** (300 secondes) maximum entre les nœuds émetteurs et vérificateurs (définie dans `Config#MAX_CLOCK_DRIFT_SECONDS`).
- **Contrôles Temporels** :
  - `validFrom - 300 000 <= now` (la clé est active, en tenant compte de la dérive d'horloge).
  - `now < validUntil` (la clé n'est pas expirée).
- Cette dérive peut être ajustée via la variable d'environnement `VDOT_CLOCK_DRIFT_TOLERANCE_SECONDS` (bornée entre `0` et `600` secondes).
