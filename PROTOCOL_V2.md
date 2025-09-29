# Spécification Complète du Protocole Véridot v2

## 1. Principes Fondamentaux

### 1.1 Séparation des Responsabilités
- **Protocole Véridot** : définit la structure, la syntaxe et la sémantique des messages
- **Transport/Broker** : assure la livraison, l'ordre, la persistance et la cohérence distribuée
- **Implémentation** : applique les règles du protocole dans un contexte d'exécution spécifique

### 1.2 Politique de Sécurité
- **Refus implicite** : tout message non conforme ou ambiguë est REJETÉ
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
- **Format** : `[A-Za-z0-9_-]+` (expression régulière)
- **Longueur** : 1 à 64 caractères UTF-8
- **Interdictions** : 
  - Caractères `:` (0x3A) et `|` (0x7C) INTERDITS
  - Espaces et caractères de contrôle INTERDITS
  - Chaînes vides INTERDITES

#### 2.2.3 Séparateurs
- **Entre version et PCI** : `:` (deux-points, sans espaces)
- **Entre groupId et sequenceId** : `:` (deux-points, sans espaces)  
- **Entre header et metadata** : `|` (pipe, sans espaces)

### 2.3 Identifiant de Référence
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

### 3.5 Exemples

#### Message pour validation JWT
```
2:user123:session001|mode:ZWNkc2E=,pubkey:BKNPG59qjz0qfR5Va2N8hbKmWi6dOBA,timestamp:MTcwNjcxMjAwMA==,ttl:MzYwMA==
```
- Clé publique valide pendant 1h (`ttl=3600`)
- Les JWT signés avec la clé privée correspondante sont vérifiables pendant cette période
- Après 1h, même un JWT non expiré sera rejeté (clé publique Véridot expirée)

#### Message pour API key sans expiration explicite
```
2:API_SERVICE:key_789|mode:cnNh,pubkey:TUlJQklqQU5CZ2txaGtpRzl3MEJBUUVGQUFPQkpRQXdnZ0V,timestamp:MTcwNjcxMjAwMA==
```
- Pas de `ttl` : la clé reste valide selon `defaultTTL` de la configuration
- L'API key elle-même peut avoir sa propre logique d'expiration

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

#### 4.2.4 Résolution avec Appartenance de Site

Un groupe peut déclarer son appartenance à un site via la propriété `site` dans ses messages normaux. Cette appartenance détermine l'application des configurations de site.

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
#### 4.2.2 Résolution Temporelle
- Chaque configuration DOIT inclure un `timestamp` (Unix timestamp en secondes)
- En cas de configurations multiples au même niveau : la plus récente fait foi
- En cas de `timestamp` identiques : message REJETÉ (conflit non résolvable)
- Messages sans `timestamp` valide : REJETÉS

#### 4.2.3 Validation des Configurations
Une configuration est **valide** si et seulement si :
- Structure syntaxique correcte
- `timestamp` présent et valide (entier positif Unix)
- `validUntil` présent et supérieur au timestamp actuel
- Métadonnées bien formées selon le format unifié

## 5. Séquences Réservées

### 5.1 Convention de Nommage
**Pattern** : `__<NOM>__` (double underscore obligatoire)

### 5.2 Séquences Définies
- **`__CONFIG__`** : Messages de configuration
- **`__ALL__`** : Identifiant universel (configuration globale uniquement)

### 5.3 Séquences Réservées Futures
- **`__REVOKE__`** : Messages de révocation explicite
- **`__METRICS__`** : Messages de métriques/monitoring  
- **`__STATUS__`** : Messages de statut système
- **`__HEALTH__`** : Messages de health check

### 5.4 Registre des Séquences
Toute nouvelle séquence réservée DOIT :
- Suivre le pattern `__<NOM>__`
- Être documentée dans cette spécification
- Éviter les collisions avec les séquences existantes

## 6. Format Unifié des Métadonnées

### 6.1 Structure Générale
```
<metadata> = <name>:<base64_value>[,<name>:<base64_value>...]
```

### 6.2 Spécification de l'Encodage

#### 6.2.1 Noms de Propriétés
- **Format** : `[a-zA-Z][a-zA-Z0-9_]*` (commence par une lettre)
- **Longueur** : 1 à 32 caractères
- **Sensibilité** : sensible à la casse (`timestamp` ≠ `Timestamp`)

#### 6.2.2 Valeurs
- **Encodage** : Base64 standard (RFC 4648) sans padding (`=`)
- **Contenu** : JSON valide avant encodage
- **Taille maximale** : 1024 octets par valeur (après décodage JSON)

### 6.3 Processus de Décodage Canonique

#### 6.3.1 Étapes de Validation
1. **Parsing** : séparer par virgules (`name:value,name:value,...`)
2. **Validation des noms** : conformité regex et unicité
3. **Décodage Base64** : validation format et décodage
4. **Parsing JSON** : validation syntaxe JSON
5. **Validation sémantique** : selon le type de message

#### 6.3.2 Gestion des Erreurs
- **Nom invalide** : REJETER le message entier
- **Base64 invalide** : REJETER le message entier
- **JSON invalide** : REJETER le message entier
- **Propriété inconnue** : IGNORER la propriété (forward compatibility)

### 6.4 Propriétés Standard

#### 6.4.1 Messages Normaux
| Propriété | Type JSON | Obligatoire | Description |
|-----------|-----------|-------------|-------------|
| `mode` | `string` | OUI | Type de signature (`rsa`, `ecdsa`) |
| `pubkey` | `string` | OUI | Clé publique encodée en base64 |
| `timestamp` | `number` | OUI | Unix timestamp de création |
| `ttl` | `number` | NON | Durée de vie en secondes |
| `payload` | `string` | NON | Payload signé (pour API keys) |

#### 6.4.2 Messages de Configuration
| Propriété | Type JSON | Obligatoire | Description |
|-----------|-----------|-------------|-------------|
| `timestamp` | `number` | OUI | Unix timestamp de la configuration |
| `validUntil` | `number` | OUI | Timestamp d'expiration |
| `policy` | `string` | OUI | Politique d'éviction (`FIFO`, `LIFO`, `LRU`) |
| `maxSessions` | `number` | NON | Limite de sessions simultanées par groupe |
| `defaultTTL` | `number` | NON | Durée de vie par défaut (secondes) |
| `name` | `string` | NON | Nom descriptif |
| `description` | `string` | NON | Description de la configuration |

##### Gestion du Cycle de Vie des Sessions

**Politique d'éviction (`policy`)** - Détermine quelle session fermer quand `maxSessions` est atteint :
- **`FIFO`** (First In, First Out) : évince la session la plus ancienne
- **`LIFO`** (Last In, First Out) : évince la session la plus récente
- **`LRU`** (Least Recently Used) : évince la session la moins utilisée récemment

**Limite de sessions (`maxSessions`)** - Contrôle les ressources et la sécurité :
- Prévient l'épuisement des ressources système
- Limite les connexions simultanées par utilisateur/groupe
- Protection contre les comptes compromis créant des milliers de sessions

**TTL par défaut (`defaultTTL`)** - Nettoyage automatique :
- Durée de vie en secondes pour les sessions sans TTL explicite
- Expiration automatique des sessions abandonnées ou inactives
- Libération automatique des ressources

##### Interaction des Paramètres

Les trois propriétés travaillent ensemble pour la gestion de sessions :

```
Scénario 1 - Limite atteinte :
- Utilisateur avec maxSessions=5 et policy=FIFO
- 5 sessions actives → création 6ème session
- Résultat : session la plus ancienne fermée automatiquement

Scénario 2 - Expiration TTL :
- Session créée sans TTL explicite
- defaultTTL=7200 → session expire après 2h d'inactivité
- Résultat : nettoyage automatique des ressources

Scénario 3 - Combinaison :
- maxSessions=10, policy=LRU, defaultTTL=3600
- Protection : max 10 sessions, éviction intelligente, expiration 1h
```

### 6.5 Types de Données JSON Autorisés
- **string** : chaînes UTF-8
- **number** : entiers et flottants IEEE 754
- **boolean** : `true` ou `false`
- **null** : valeur nulle
- **array** : tableaux homogènes ou hétérogènes
- **object** : objets JSON imbriqués (niveau max : 3)

### 6.6 Exemples Complets

#### 6.6.1 Message Normal avec Appartenance de Site
```
2:USER_123:session001|mode:ZWNkc2E=,site:bXMtYXV0aC12MQ==,pubkey:BKNPG59qjz0qfR5Va2N8hbKmWi6dOBA,timestamp:MTcwNjcxMjAwMA==,ttl:MzYwMA==
```

**Valeurs décodées** :
- `mode` = `"ecdsa"`
- `site` = `"ms-auth-v1"` (groupe appartient au site ms-auth-v1)
- `pubkey` = `"BKNPG59qjz0qfR5Va2N8hbKmWi6dOBA"`
- `timestamp` = `1706712000` (2024-01-31 12:00:00 UTC)
- `ttl` = `3600` (1 heure)

#### 6.6.2 Message Normal sans Site (Partitionnement Global)
```
2:API_SERVICE:conn_456|mode:cnNh,pubkey:TUlJQklqQU5CZ2txaGtpRzl3MEJBUUVGQUFPQkpRQXdnZ0V,timestamp:MTcwNjcxMjAwMA==
```

**Valeurs décodées** :
- `mode` = `"rsa"`
- `pubkey` = `"MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."`
- `timestamp` = `1706712000`
- Aucune propriété `site` : le groupe n'appartient à aucun site spécifique

#### 6.6.3 Configuration de Site pour ms-auth-v1
```
2:__CONFIG__:ms-auth-v1|timestamp:MTcwNjcxMjAwMA==,validUntil:MTcwNjc5ODQwMA==,policy:RklGTw==,maxSessions:MTA=,defaultTTL:NzIwMA==,name:QXV0aGVudGljYXRpb24gU2VydmljZSB2MQ==
```

**Valeurs décodées** :
- `timestamp` = `1706712000`
- `validUntil` = `1706798400` (expire dans 24h)
- `policy` = `"FIFO"`
- `maxSessions` = `10`
- `defaultTTL` = `7200` (2 heures)
- `name` = `"Authentication Service v1"`

**Impact** : Cette configuration s'applique à tous les groupes déclarant `site=ms-auth-v1`

## 7. Règles d'Implémentation

### 7.1 Responsabilités du Processeur

#### 7.1.1 Validation des Messages
Un processeur DOIT :
- Valider la syntaxe selon les contraintes strictes (section 2.2)
- Rejeter tout message non conforme
- Loguer les rejets avec raison détaillée
- Ne JAMAIS traiter un message partiellement valide

#### 7.1.2 Gestion des Configurations
Un processeur DOIT :
- Appliquer la configuration valide la plus récente
- Maintenir un cache des configurations actives
- Nettoyer les configurations expirées (`validUntil` dépassé)
- Garantir l'atomicité des changements de configuration

#### 7.1.3 Gestion des Messages et Validations
Un processeur DOIT :
- Vérifier la validité temporelle des messages : `now < timestamp + ttl`
- Rejeter les messages dont le TTL est dépassé
- Extraire la clé publique et l'algorithme pour la validation cryptographique
- Séparer la validation Véridot (message) de la validation métier (objet signé)
- Respecter la politique de session configurée
- Maintenir les compteurs de sessions par groupe
- Gérer les révocations de manière atomique

### 7.2 Propriétés de Cohérence

#### 7.2.1 Cohérence Locale
- **Atomicité** : changements de configuration atomiques au niveau processeur
- **Isolation** : les états intermédiaires ne sont pas visibles
- **Durabilité** : les configurations validées survivent aux redémarrages

#### 7.2.2 Cohérence Distribuée
- **Éventuelle** : tous les processeurs convergent vers la même configuration
- **Transport** : la cohérence forte relève du broker, pas du protocole
- **Partition** : en cas de partition réseau, privilégier la disponibilité

### 7.3 Gestion des Erreurs

#### 7.3.1 Classification des Erreurs
- **Erreurs de protocole** : syntaxe, format, validation
- **Erreurs de transport** : réseau, broker indisponible
- **Erreurs d'implémentation** : mémoire, disque, corruption

#### 7.3.2 Stratégies de Récupération
- **Retry** : erreurs transitoires de transport
- **Fallback** : configurations par défaut si aucune configuration valide
- **Circuit breaker** : isoler les composants défaillants
- **Graceful degradation** : maintenir le service même en mode dégradé

### 7.4 Observabilité

#### 7.4.1 Métriques Obligatoires
- Nombre de messages traités/rejetés par type
- Latence de traitement des messages
- Taux d'erreur par catégorie
- Nombre de sessions actives par groupe

#### 7.4.2 Logs Obligatoires
- Rejets de messages avec raison détaillée
- Changements de configuration avec timestamp
- Révocations de sessions avec identifiant
- Erreurs de transport avec contexte

## 8. Sécurité et Conformité

### 8.1 Validation Cryptographique
- **Signatures** : validation obligatoire selon `mode` spécifié
- **Clés publiques** : validation format et algorithme
- **Timestamps** : validation contre dérive d'horloge (±5 minutes)
- **TTL** : respect strict des durées de vie

### 8.2 Protection contre les Attaques
- **Replay** : utilisation des timestamps et TTL
- **Injection** : validation stricte des formats
- **DoS** : limites de taille et de fréquence (implémentation)
- **Tampering** : signatures cryptographiques obligatoires

### 8.3 Audit et Traçabilité
- **Identifiants uniques** : chaque message a un identifiant unique
- **Logs d'audit** : traçabilité complète des opérations
- **Non-répudiation** : signatures cryptographiques
- **Retention** : conservation des logs selon politiques de rétention

## 9. Évolution et Compatibilité

### 9.1 Versioning du Protocole
- **Version majeure** : changements incompatibles
- **Version mineure** : extensions rétrocompatibles
- **Version patch** : corrections et clarifications

### 9.2 Extensibilité
- **Nouvelles propriétés** : ajout dans les métadonnées sans casser l'existant
- **Nouvelles séquences réservées** : enregistrement dans cette spécification
- **Nouveaux types de messages** : via nouvelles versions du protocole

### 9.3 Migration
- **Coexistence** : plusieurs versions peuvent coexister
- **Détection** : version détectée automatiquement
- **Transition** : migration progressive possible

## 10. Annexes

### 10.1 Grammaire ABNF (RFC 5234)

```abnf
message         = version ":" pci "|" metadata
version         = "2"
pci             = groupId ":" sequenceId
groupId         = identifier
sequenceId      = identifier / reserved-sequence
identifier      = 1*64(ALPHA / DIGIT / "_" / "-")
reserved-sequence = "__" 1*28(ALPHA) "__"
metadata        = property *("," property)
property        = prop-name ":" base64-value
prop-name       = ALPHA *(ALPHA / DIGIT / "_")
base64-value    = 1*1024base64-char
base64-char     = ALPHA / DIGIT / "+" / "/" 
```

### 10.2 Codes d'Erreur Standardisés

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

### 10.3 Références Normatives
- **RFC 4648** : Base64 Data Encodings
- **RFC 8259** : The JavaScript Object Notation (JSON) Data Interchange Format
- **RFC 5234** : Augmented BNF for Syntax Specifications
- **ISO 8601** : Date and time format (pour timestamps)

---

*Cette spécification définit le protocole Véridot version 2.0*  
*Dernière révision : 2025-01-28*