# Spécification Complète du Protocole Véridot v2

## 1. Principes Fondamentaux

### 1.1 Séparation des Responsabilités
- **Protocole Véridot** : définit la structure, la syntaxe et la sémantique des messages
- **Transport/Broker** : assure la livraison, la persistance et la cohérence distribuée
- **Implémentation** : applique les règles du protocole dans un contexte d'exécution spécifique

### 1.2 Politique de Sécurité
- **Refus implicite** : tout message non conforme ou ambigu est REJETÉ
- **Autorisation explicite** : seuls les messages strictement conformes sont ACCEPTÉS
- **Validation stricte** : aucune tolérance aux déviations du format

## 2. Structure Générale des Messages

### 2.1 Format Canonique
```
[2]:[<groupId>:<sequenceId>]|[<metadata>]
```

### 2.2 Contraintes Syntaxiques Strictes

#### 2.2.1 Version
- **Valeur** : exactement `2` (caractère ASCII)
- **Validation** : DOIT être un entier positif égal à 2

#### 2.2.2 Identifiants (groupId et sequenceId)
- **Format** : toute chaîne de caractères imprimables excluant les délimiteurs du protocole
- **Longueur** : 1 à 125 caractères UTF-8
- **Interdictions** : 
  - Caractères `:` (0x3A), `,` (0x2C) et `|` (0x7C) INTERDITS (délimiteurs du protocole)
  - Espaces et caractères de contrôle INTERDITS
  - Chaînes vides INTERDITES
- **Regex** : `[^:,|\s]{1,125}`

#### 2.2.3 Séparateurs
- **Entre version et PCI** : `:` (deux-points, sans espaces)
- **Entre groupId et sequenceId** : `:` (deux-points, sans espaces)  
- **Entre header et metadata** : `|` (pipe, sans espaces)

### 2.3 Identifiant de Référence (messageId)
L'identifiant unique d'un message suit la forme :
```
messageId = [2] : [<groupId> : <sequenceId>]
```

**Exemples valides** :
- `2:user123:session001`
- `2:API_SERVICE:conn_456`
- `2:payment-sys:tx_789abc`

## 3. Messages Normaux

### 3.1 Structure
```
[2]:[<groupId>:<sequenceId>]|[<metadata>]
```

### 3.2 Rôle et Responsabilité

Un message normal Véridot distribue les **éléments nécessaires à la validation cryptographique** d'objets signés (JWT, API keys, documents, etc.).

**Responsabilités du message Véridot** :
- Fournir la clé publique de vérification
- Spécifier l'algorithme cryptographique à utiliser
- Définir la fenêtre temporelle de validité de la clé publique
- Associer optionnellement le groupe à un site

**Hors scope** : Le message Véridot ne gère PAS :
- L'expiration métier de l'objet signé (ex: claim `exp` d'un JWT)
- Les permissions ou rôles de l'utilisateur
- La logique applicative de l'objet à vérifier

### 3.3 Contraintes Sémantiques
- Un **groupe** peut contenir plusieurs **séquences** actives simultanément
- Chaque **séquence** au sein d'un **groupe** DOIT être unique
- L'unicité est garantie au niveau de l'implémentation, pas du protocole
- Le message reste valide tant que `now < timestamp + ttl` (si ttl spécifié)

### 3.4 Processus de Vérification Standard

1. **Récupération** : L'objet à vérifier référence un `messageId`
2. **Extraction** : Récupérer le message Véridot correspondant via le broker
3. **Validation temporelle** : Vérifier `timestamp + ttl > now`
4. **Validation cryptographique** : Utiliser `pubkey` et `mode` pour vérifier la signature
5. **Validation métier** : L'application vérifie ses propres règles (expiration JWT, permissions, etc.)

**Association objet signé ↔ messageId** : L'objet signé DOIT référencer le `messageId` complet (`2:<groupId>:<sequenceId>`) de la métadonnée Véridot correspondante. Le mécanisme d'association est défini par l'implémentation (ex : claim `sub` d'un JWT, header HTTP, champ d'un document signé), mais l'identifiant DOIT être transmis intégralement et intact.

### 3.5 Gestion de la Capacité de Sessions

Un groupe peut être soumis à une limite de séquences actives simultanées (`maxSessions`), définie via la configuration (§4).

#### 3.5.1 Comptage des sessions

Le nombre de séquences actives d'un groupe est **dérivé de l'état du broker**, pas d'un compteur local. Avant de publier une nouvelle séquence, le processeur signataire DOIT interroger le broker pour obtenir la liste des séquences actives (non expirées, non révoquées) du groupe concerné.

#### 3.5.2 Déclenchement de l'éviction

L'éviction est déclenchée **au moment de la signature**, par le processeur signataire :

1. Le processeur obtient les séquences actives du groupe depuis le broker.
2. Si `count >= maxSessions`, il émet un message `__REVOKE__` (§5) pour la séquence à évincer selon la politique configurée (`policy`), **avant** de publier la nouvelle séquence.
3. Si `maxSessions` n'est pas défini, aucune vérification de capacité n'est effectuée.

#### 3.5.3 Cohérence en contexte distribué

La cohérence du comptage de sessions est **éventuelle**. Deux processeurs signant simultanément pour le même groupe peuvent temporairement dépasser `maxSessions`. Après convergence du broker, le nombre effectif se stabilisera. C'est un choix délibéré : **disponibilité > cohérence stricte**.

### 3.6 Exemples

#### Message pour validation JWT
```
2:user123:session001|mode:ZWNkc2E,pubkey:BKNPG59qjz0qfR5Va2N8hbKmWi6dOBA,timestamp:MTcwNjcxMjAwMA,ttl:MzYwMA
```
- Clé publique valide pendant 1h (`ttl=3600`)
- Les JWT signés avec la clé privée correspondante sont vérifiables pendant cette période
- Après 1h, même un JWT non expiré sera rejeté (clé publique Véridot expirée)

#### Message pour API key sans expiration explicite
```
2:API_SERVICE:key_789|mode:cnNh,pubkey:TUlJQklqQU5CZ2txaGtpRzl3MEJBUUVGQUFPQkpRQXdnZ0V,timestamp:MTcwNjcxMjAwMA
```
- Pas de `ttl` : la clé reste valide selon `defaultTTL` de la configuration
- L'API key elle-même peut avoir sa propre logique d'expiration

#### Message avec appartenance de site
```
2:USER_123:session001|mode:ZWNkc2E,site:bXMtYXV0aC12MQ,pubkey:BKNPG59qjz0qfR5Va2N8hbKmWi6dOBA,timestamp:MTcwNjcxMjAwMA,ttl:MzYwMA
```
- `site` = `"ms-auth-v1"` : le groupe appartient au site ms-auth-v1
- Les configurations du site ms-auth-v1 s'appliquent à ce groupe

## 4. Messages de Configuration

### 4.1 Hiérarchie des Configurations

#### 4.1.1 Configuration Locale (Priorité : 1 - Maximale)
```
[2]:[<groupId>:__CONFIG__]|[<metadata>]
```
**Portée** : Spécifique au groupe `groupId`

#### 4.1.2 Configuration de Site (Priorité : 2 - Intermédiaire)
```
[2]:[__CONFIG__:<siteId>]|[<metadata>]
```
**Portée** : Tous les groupes du site `siteId`

#### 4.1.3 Configuration Globale (Priorité : 3 - Minimale)
```
[2]:[__CONFIG__:__ALL__]|[<metadata>]
```
**Portée** : Tous les groupes de tous les sites

### 4.2 Résolution des Conflits

#### 4.2.1 Règles de Priorité
1. **Configuration locale** surcharge **configuration de site**
2. **Configuration de site** surcharge **configuration globale**
3. **Configuration globale** surcharge **configuration par défaut**

#### 4.2.2 Résolution avec Appartenance de Site

Un groupe peut déclarer son appartenance à un site via la propriété `site` dans ses messages normaux.

**Règles de résolution** :
1. **Configuration locale** : `2:groupId:__CONFIG__|metadata` (priorité maximale)
2. **Configuration de site** : `2:__CONFIG__:siteId|metadata` (si le groupe déclare `site=siteId`)
3. **Configuration globale** : `2:__CONFIG__:__ALL__|metadata`
4. **Configuration par défaut** : valeurs d'implémentation

**Appartenance de site** :
- **Déclaration** : via propriété `site` dans les métadonnées des messages normaux
- **Optionnelle** : un groupe sans propriété `site` n'appartient à aucun site
- **Dynamique** : peut changer en cours d'exécution
- **Cohérente** : tous les messages d'un même groupe doivent déclarer le même site (si déclaré)

#### 4.2.3 Résolution Temporelle
- Chaque configuration DOIT inclure un `timestamp` (Unix timestamp en secondes)
- En cas de configurations multiples au même niveau : la plus récente fait foi
- En cas de `timestamp` identiques : message REJETÉ (conflit non résolvable)
- Messages sans `timestamp` valide : REJETÉS

#### 4.2.4 Validation des Configurations
Une configuration est **valide** si et seulement si :
- Structure syntaxique correcte
- `timestamp` présent et valide (entier positif Unix)
- `validUntil` présent et supérieur au timestamp actuel
- Métadonnées bien formées selon le format unifié

## 5. Messages de Révocation

### 5.1 Rôle

Un message de révocation invalide explicitement une ou plusieurs séquences d'un groupe. Après traitement d'une révocation, toute vérification ultérieure sur les séquences ciblées DOIT échouer.

### 5.2 Structure

```
[2]:[<groupId>:__REVOKE__]|[<metadata>]
```

### 5.3 Propriétés

| Propriété | Type | Obligatoire | Description |
|-----------|------|-------------|-------------|
| `target` | `string` | OUI | Le `sequenceId` à révoquer, ou `__ALL__` pour révoquer toutes les séquences du groupe |
| `timestamp` | `number` | OUI | Unix timestamp de la demande de révocation |

### 5.4 Sémantique

- La réception d'un message `__REVOKE__` par un processeur DOIT entraîner la suppression immédiate de la métadonnée ciblée dans son store.
- **Révocation d'une séquence** : seule la séquence identifiée par `target` est invalidée.
- **Révocation d'un groupe entier** : `target=__ALL__` invalide toutes les séquences actives du groupe.
- La révocation est **irréversible** dans le scope du `sequenceId` concerné. Pour ré-autoriser, il faut créer une nouvelle séquence.
- Un processeur DOIT traiter les révocations de manière **atomique** : la suppression DOIT être complète ou ne pas avoir lieu.

### 5.5 Exemples

#### Révocation d'une session spécifique
```
2:user123:__REVOKE__|target:c2Vzc2lvbjAwMQ,timestamp:MTcwNjcxNTYwMA
```
- `target` = `"session001"` → révoque uniquement `2:user123:session001`

#### Révocation de toutes les sessions d'un utilisateur
```
2:user123:__REVOKE__|target:X19BTExfXw,timestamp:MTcwNjcxNTYwMA
```
- `target` = `"__ALL__"` → révoque toutes les séquences actives du groupe `user123`

## 6. Séquences Réservées

### 6.1 Convention de Nommage
**Pattern** : `__<NOM>__` (double underscore obligatoire de chaque côté)

### 6.2 Séquences Définies

| Séquence | Rôle | Spécification |
|----------|------|---------------|
| `__CONFIG__` | Messages de configuration | §4 |
| `__REVOKE__` | Messages de révocation | §5 |
| `__ALL__` | Identifiant universel (utilisé comme cible dans `__CONFIG__` et `__REVOKE__`) | §4.1.3, §5.4 |

### 6.3 Registre des Séquences
Toute nouvelle séquence réservée DOIT :
- Suivre le pattern `__<NOM>__`
- Être entièrement spécifiée (format, propriétés, sémantique) dans ce document
- Éviter les collisions avec les séquences existantes

## 7. Format Unifié des Métadonnées

### 7.1 Structure Générale
```
<metadata> = <name>:<base64url_value>[,<name>:<base64url_value>...]
```

### 7.2 Spécification de l'Encodage

#### 7.2.1 Noms de Propriétés
- **Format** : `[a-zA-Z][a-zA-Z0-9_]*` (commence par une lettre)
- **Longueur** : 1 à 32 caractères
- **Sensibilité** : sensible à la casse (`timestamp` ≠ `Timestamp`)

#### 7.2.2 Valeurs
- **Encodage** : Base64url (RFC 4648 §5) sans padding
- **Contenu** : Représentation UTF-8 de la valeur. Pour les nombres : représentation décimale. Pour les chaînes : la chaîne brute. Pour les structures complexes : JSON sérialisé.
- **Taille maximale** : 1024 octets par valeur (après décodage)

### 7.3 Processus de Décodage Canonique

#### 7.3.1 Étapes de Validation
1. **Parsing** : séparer par virgules (`name:value,name:value,...`)
2. **Validation des noms** : conformité regex et unicité
3. **Décodage Base64url** : validation format et décodage
4. **Validation sémantique** : vérifier le type et la valeur selon la propriété

#### 7.3.2 Gestion des Erreurs
- **Nom invalide** : REJETER le message entier
- **Base64url invalide** : REJETER le message entier
- **Valeur invalide** (type inattendu, hors bornes) : REJETER le message entier
- **Propriété inconnue** : IGNORER la propriété (forward compatibility)

### 7.4 Propriétés Standard

#### 7.4.1 Messages Normaux
| Propriété | Type | Obligatoire | Description |
|-----------|------|-------------|-------------|
| `mode` | `string` | OUI | Algorithme de signature (`rsa`, `ecdsa`) |
| `pubkey` | `string` | OUI | Clé publique encodée en base64url |
| `timestamp` | `number` | OUI | Unix timestamp de création (secondes) |
| `ttl` | `number` | NON | Durée de vie en secondes |
| `site` | `string` | NON | Identifiant du site auquel le groupe appartient |
| `token` | `string` | NON | L'objet signé complet (JWT, API key…) quand le mode de distribution ne transmet pas le token directement au client |

> **Note sur `token`** : Cette propriété est utilisée lorsque le token n'est pas retourné au caller mais stocké côté broker (équivalent du mode `id` dans Véridot v1). Le vérificateur récupère le token depuis la métadonnée au lieu de le recevoir directement.

#### 7.4.2 Messages de Configuration
| Propriété | Type | Obligatoire | Défaut | Description |
|-----------|------|-------------|--------|-------------|
| `timestamp` | `number` | OUI | — | Unix timestamp de la configuration |
| `validUntil` | `number` | OUI | — | Timestamp d'expiration de la configuration |
| `maxSessions` | `number` | NON | ∞ (pas de limite) | Nombre max de séquences actives par groupe |
| `policy` | `string` | NON | `FIFO` | Politique d'éviction, appliquée uniquement si `maxSessions` est défini |
| `defaultTTL` | `number` | NON | — | Durée de vie par défaut en secondes |
| `name` | `string` | NON | — | Nom descriptif |
| `description` | `string` | NON | — | Description de la configuration |

> **`policy` n'est pertinente que si `maxSessions` est défini.** Si `maxSessions` est absent, la politique d'éviction est ignorée par le processeur.

##### Politiques d'Éviction

| Policy | Description |
|--------|-------------|
| `FIFO` | First In, First Out — évince la séquence la plus ancienne (par `timestamp`) |
| `LIFO` | Last In, First Out — évince la séquence la plus récente |
| `LRU` | Least Recently Used — évince la séquence la moins utilisée récemment |

##### Interaction des Paramètres

```
Scénario 1 - Limite atteinte :
- Utilisateur avec maxSessions=5 et policy=FIFO
- 5 sessions actives → création 6ème session
- Résultat : session la plus ancienne révoquée automatiquement, puis nouvelle session publiée

Scénario 2 - Expiration TTL :
- Session créée sans TTL explicite
- defaultTTL=7200 → session expire après 2h
- Résultat : nettoyage automatique des ressources

Scénario 3 - Combinaison :
- maxSessions=10, policy=LRU, defaultTTL=3600
- Protection : max 10 sessions, éviction intelligente, expiration 1h
```

#### 7.4.3 Messages de Révocation
| Propriété | Type | Obligatoire | Description |
|-----------|------|-------------|-------------|
| `target` | `string` | OUI | Le `sequenceId` à révoquer, ou `__ALL__` |
| `timestamp` | `number` | OUI | Unix timestamp de la révocation |

## 8. Règles d'Implémentation

### 8.1 Responsabilités du Processeur

#### 8.1.1 Validation des Messages
Un processeur DOIT :
- Valider la syntaxe selon les contraintes strictes (§2.2)
- Rejeter tout message non conforme
- Loguer les rejets avec raison détaillée
- Ne JAMAIS traiter un message partiellement valide

#### 8.1.2 Gestion des Configurations
Un processeur DOIT :
- Appliquer la configuration valide la plus récente
- Maintenir un cache des configurations actives
- Nettoyer les configurations expirées (`validUntil` dépassé)
- Garantir l'atomicité des changements de configuration

#### 8.1.3 Gestion des Messages Normaux
Un processeur DOIT :
- Vérifier la validité temporelle des messages : `now < timestamp + ttl`
- Rejeter les messages dont le TTL est dépassé
- Extraire la clé publique et l'algorithme pour la validation cryptographique
- Séparer la validation Véridot (message) de la validation métier (objet signé)

#### 8.1.4 Gestion des Sessions
Un processeur DOIT :
- Dériver le comptage de sessions depuis l'état du broker (pas d'un compteur local)
- Appliquer l'éviction selon la politique configurée au moment de la signature
- Gérer les révocations de manière atomique

### 8.2 Exigences du Broker

Le transport (broker) DOIT fournir les capacités suivantes :

| Capacité | Description |
|----------|-------------|
| **Envoi** | Publier un message associé à une clé (`messageId`) |
| **Lecture par clé** | Récupérer un message par son `messageId` exact |
| **Listing par groupe** | Récupérer la liste des `messageId` actifs pour un `groupId` donné |

La capacité de **listing par groupe** est nécessaire pour :
- Le comptage de sessions (§3.5)
- La révocation de groupe (`__ALL__`) (§5.4)
- L'interrogation de l'état actif d'un groupe

### 8.3 Propriétés de Cohérence

#### 8.3.1 Cohérence Locale
- **Atomicité** : changements de configuration et révocations atomiques au niveau processeur
- **Isolation** : les états intermédiaires ne sont pas visibles
- **Durabilité** : les configurations validées survivent aux redémarrages

#### 8.3.2 Cohérence Distribuée
- **Éventuelle** : tous les processeurs convergent vers le même état
- **Transport** : la cohérence forte relève du broker, pas du protocole
- **Partition** : en cas de partition réseau, privilégier la disponibilité

### 8.4 Gestion des Erreurs

#### 8.4.1 Classification des Erreurs
- **Erreurs de protocole** : syntaxe, format, validation
- **Erreurs de transport** : réseau, broker indisponible
- **Erreurs d'implémentation** : mémoire, disque, corruption

#### 8.4.2 Stratégies de Récupération
- **Retry** : erreurs transitoires de transport
- **Fallback** : configurations par défaut si aucune configuration valide
- **Circuit breaker** : isoler les composants défaillants
- **Graceful degradation** : maintenir le service même en mode dégradé

### 8.5 Observabilité

#### 8.5.1 Métriques Obligatoires
- Nombre de messages traités/rejetés par type
- Latence de traitement des messages
- Taux d'erreur par catégorie
- Nombre de sessions actives par groupe

#### 8.5.2 Logs Obligatoires
- Rejets de messages avec raison détaillée
- Changements de configuration avec timestamp
- Révocations de sessions avec identifiant
- Erreurs de transport avec contexte

## 9. Sécurité et Conformité

### 9.1 Validation Cryptographique
- **Signatures** : validation obligatoire selon `mode` spécifié
- **Clés publiques** : validation format et algorithme
- **Timestamps** : validation contre dérive d'horloge (±5 minutes)
- **TTL** : respect strict des durées de vie

### 9.2 Protection contre les Attaques
- **Replay** : utilisation des timestamps et TTL
- **Injection** : validation stricte des formats
- **DoS** : limites de taille et de fréquence (implémentation)
- **Tampering** : signatures cryptographiques obligatoires

### 9.3 Audit et Traçabilité
- **Identifiants uniques** : chaque message a un identifiant unique
- **Logs d'audit** : traçabilité complète des opérations
- **Non-répudiation** : signatures cryptographiques
- **Retention** : conservation des logs selon politiques de rétention

## 10. Évolution et Compatibilité

### 10.1 Versioning du Protocole
- **Version majeure** : changements incompatibles
- **Version mineure** : extensions rétrocompatibles
- **Version patch** : corrections et clarifications

### 10.2 Extensibilité
- **Nouvelles propriétés** : ajout dans les métadonnées sans casser l'existant
- **Nouvelles séquences réservées** : enregistrement et spécification complète dans ce document
- **Nouveaux types de messages** : via nouvelles versions du protocole

### 10.3 Migration
- **Coexistence** : plusieurs versions peuvent coexister
- **Détection** : version détectée automatiquement via le premier champ
- **Transition** : migration progressive possible

## 11. Annexes

### 11.1 Grammaire ABNF (RFC 5234)

```abnf
message           = version ":" pci "|" metadata
version           = "2"
pci               = groupId ":" sequenceId
groupId           = identifier
sequenceId        = identifier / reserved-sequence
identifier        = 1*64(ALPHA / DIGIT / "_" / "-")
reserved-sequence = "__" 1*28(ALPHA) "__"
metadata          = property *("," property)
property          = prop-name ":" base64url-value
prop-name         = ALPHA 0*31(ALPHA / DIGIT / "_")
base64url-value   = 1*(ALPHA / DIGIT / "-" / "_")
```

### 11.2 Codes d'Erreur Standardisés

| Code | Nom | Description |
|------|-----|-------------|
| `V2001` | `INVALID_SYNTAX` | Structure syntaxique invalide |
| `V2002` | `INVALID_VERSION` | Version non supportée |
| `V2003` | `INVALID_IDENTIFIER` | GroupId ou sequenceId invalide |
| `V2004` | `INVALID_METADATA` | Format des métadonnées invalide |
| `V2005` | `MISSING_REQUIRED_PROPERTY` | Propriété obligatoire manquante |
| `V2006` | `INVALID_TIMESTAMP` | Timestamp invalide ou expiré |
| `V2007` | `INVALID_SIGNATURE` | Signature cryptographique invalide |
| `V2008` | `CONFIGURATION_CONFLICT` | Conflit de configuration non résolvable |
| `V2009` | `SESSION_LIMIT_EXCEEDED` | Limite de sessions atteinte (après échec d'éviction) |
| `V2010` | `REVOCATION_FAILED` | La révocation n'a pas pu être traitée |

### 11.3 Références Normatives
- **RFC 4648 §5** : Base64url Encoding
- **RFC 8259** : The JavaScript Object Notation (JSON) Data Interchange Format
- **RFC 5234** : Augmented BNF for Syntax Specifications
- **ISO 8601** : Date and time format (pour timestamps)

---

*Cette spécification définit le protocole Véridot version 2.0*  
*Dernière révision : 2026-04-10*