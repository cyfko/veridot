# Spécification du Protocole Véridot

## 1. Structure générale

Un message du protocole suit la forme :

**[&lt;header&gt;] | [&lt;metadata&gt;]**

- **header** : définit l'en-tête du protocole Véridot
- **`|`** : séparateur fixe entre l'en-tête et le corps
- **metadata** : contient les métadonnées nécessaires à la vérification cryptographique

---

## 2. En-tête (Header)

L'en-tête se compose comme suit :

**[&lt;version&gt;] : [&lt;pci&gt;]**

- **version** : numéro de version du protocole (entier positif)
- **`:`** : séparateur fixe
- **pci** : Protocol Control Information, spécifique à la version

**Forme globale résultante :**
**[&lt;version&gt;] : [&lt;pci&gt;] | [&lt;metadata&gt;]**

---

## 3. Identifiant de référence de message

L'identifiant permettant de référencer un message spécifique suit invariablement la forme :

**[&lt;version&gt;] : [&lt;details&gt;]**

**Usage :**
- **Tracking** : Suivi du cycle de vie d'un message
- **Révocation** : Référencement pour invalidation d'un message spécifique
- **Audit** : Traçabilité des opérations sur les messages

Cette règle s'applique à toutes les versions du protocole.

---

## 4. Spécifications par Version

### 4.1 Version 1

- **Structure message** : `[1] : [<tokenId>] | [<metadata>]`
- **Structure tokenId** : `[1] : [<tokenId>]`
- **Sémantique** : un token = une session unique
- **PCI** : `<tokenId>` (identifiant simple)

### 4.2 Version 2

#### 4.2.1 Spécification du PCI

Pour la version 2, le **PCI** est composé de deux parties séparées par **`:`** :

**&lt;groupId&gt; : &lt;sequenceId&gt;**

**Définitions :**
- **groupId** : identifiant du groupe logique auquel appartient le message
  - Type : chaîne de caractères alphanumériques
  - Utilisation typique : identifiant utilisateur, identifiant de ressource
  
- **sequenceId** : identifiant unique d'une séquence au sein du groupe
  - Type : chaîne de caractères alphanumériques
  - Utilisation typique : identifiant de session, identifiant de connexion

#### 4.2.2 Identifiant de référence en version 2

L'**identifiant de référence** d'un message normal est défini comme suit :

**messageId = [2] : [&lt;groupId&gt; : &lt;sequenceId&gt;]**

**Utilisation :**
- **Révocation** : `REVOKE 2:user123:session001` pour invalider ce message spécifique
- **Tracking** : Suivi des opérations liées à ce message
- **Audit** : Identification unique dans les logs

**Exemple :** `2:user123:session001`

#### 4.2.3 Message complet version 2

**Structure finale :**

**[2] : [&lt;groupId&gt; : &lt;sequenceId&gt;] | [&lt;metadata&gt;]**

**Contraintes syntaxiques :**
- **version** : exactement `2`
- **groupId** : non vide, ne contient pas les caractères `:` et `|`
- **sequenceId** : non vide, ne contient pas les caractères `:` et `|`
- **metadata** : données binaires ou textuelles, format libre

**Sémantique opérationnelle :**
- Un **groupe** peut contenir plusieurs **séquences** actives simultanément
- Chaque **séquence** au sein d'un **groupe** est unique
- La gestion des sessions simultanées et des révocations s'effectue au niveau du **groupe**
- Les politiques de contrôle sont **définies par les messages de configuration du protocole**

#### 4.2.4 Messages de Configuration

**Configuration Locale (Par Groupe) :**
- **Format :** `[2] : [<groupId>] : [__CONFIG__] | [<metadata>]`
- **Portée :** Spécifique au groupe identifié par `groupId`
- **Priorité :** Maximale (surcharge toute autre configuration)
- **Exemple :** `2:user123:__CONFIG__|policy:RklGTw==,maxSessions:Mw==,ttl:MzYwMA==`

**Configuration Globale (Par Site/Service) :**
- **Format :** `[2] : [__CONFIG__] : [<siteId>] | [<metadata>]`
- **Portée :** Tous les groupes du site/service identifié par `siteId`
- **Priorité :** Intermédiaire (appliquée si pas de configuration locale)
- **Exemples :**
  - `2:__CONFIG__:PAYMENT_SERVICE|policy:TFJV,maxSessions:MTA=,ttl:NzIwMA==`
  - `2:__CONFIG__:__ALL__|policy:RklGTw==,maxSessions:Mg==,ttl:MzYwMA==` (configuration globale)

**Résolution Hiérarchique :**
1. **Configuration locale** : `2:<groupId>:__CONFIG__|<metadata>`
2. **Configuration de site** : `2:__CONFIG__:<siteId>|<metadata>`
3. **Configuration globale** : `2:__CONFIG__:__ALL__|<metadata>`
4. **Configuration par défaut** : Politique définie par l'implémentation

**Évolution de Configuration :**
- **Principe** : Le message de configuration le plus récent fait foi
- **Mécanisme** : Même infrastructure de transport que les messages normaux
- **Cohérence** : Ordre garanti des événements via le broker
- **Changement dynamique** : Nouveau message de configuration = nouvelle politique appliquée

#### 4.2.5 Séquences Réservées

**Liste des Séquences Réservées :**
- **`__CONFIG__`** : Messages de configuration de politiques
- **`__ALL__`** : Identifiant universel pour configuration globale
- **Format** : Les séquences réservées utilisent le préfixe `__` et le suffixe `__`

**Usage Contextuel :**
- Configuration locale : `2:<groupId>:__CONFIG__|<metadata>`
- Configuration de site : `2:__CONFIG__:<siteId>|<metadata>`

#### 4.2.6 Format Unifié des Métadonnées

**Structure Générale**

Toutes les métadonnées suivent un format unifié, qu'il s'agisse de messages normaux ou de séquences réservées :

```
<metadata> = <name>:<base64_encoded_value>[,<name>:<base64_encoded_value>...]
```

**Spécification Version 2 :**
- **`<base64_encoded_value>`** : Valeur JSON encodée en base64
- **Avantage** : Structure de données typée et facilement parsable
- **Conversion** : `JSON.parse(atob(base64_value))` pour décoder

**Notation :** Les crochets `[...]` indiquent que l'élément peut être répété (zéro ou plusieurs fois).

**Exemples Concrets :**

*Message normal pour vérification d'un token JWT signé avec ECDSA (groupe USER, séquence 001) :*
```
2:USER:001|mode:ZWNkc2E=,pubkey:BKNPG59qjz0qfR5Va2N8hbKmWi6dOBA/fLRe3As2rU+ssv1+nBp6Jzs178Wou5gsL556SXzJ7wjnWutr6C49PnA==
```
*Valeurs décodées :*
- `mode` = `"ecdsa"`
- `pubkey` = `"BKNPG59qjz0qfR5Va2N8hbKmWi6dOBA/fLRe3As2rU+ssv1+nBp6Jzs178Wou5gsL556SXzJ7wjnWutr6C49PnA=="`

*Message normal pour vérification d'une clé d'API ECDSA (groupe USER, séquence 002) :*
```
2:USER:002|mode:ZWNkc2E=,pubkey:BKNPG59qjz0qfR5Va2N8hbKmWi6dOBA/fLRe3As2rU+ssv1+nBp6Jzs178Wou5gsL556SXzJ7wjnWutr6C49PnA==,payload:MEUCIQCqhPO3G7gLnsihLPucIp4fZrbAQJfmxYjdx+17ahtnzAIgakjSzZy3487scEMfDnjUr1zLFruD13K8UkEg5PKXHqY=
```
*Valeurs décodées :*
- `mode` = `"ecdsa"`
- `pubkey` = `"BKNPG59qjz0qfR5Va2N8hbKmWi6dOBA/fLRe3As2rU+ssv1+nBp6Jzs178Wou5gsL556SXzJ7wjnWutr6C49PnA=="`
- `payload` = `"MEUCIQCqhPO3G7gLnsihLPucIp4fZrbAQJfmxYjdx+17ahtnzAIgakjSzZy3487scEMfDnjUr1zLFruD13K8UkEg5PKXHqY="`

*Configuration de groupe (__CONFIG__) :*
```
2:USER:__CONFIG__|policy:RklGTw==,maxSessions:Mw==,ttl:MzYwMA==
```
*Valeurs décodées :*
- `policy` = `"FIFO"`
- `maxSessions` = `3`
- `ttl` = `3600`

*Configuration globale (__ALL__) :*
```
2:__CONFIG__:__ALL__|version:Mi4w,issuer:aXNzdWVyX2xk,globalPolicy:RklGTw==,defaultTTL:MzYwMA==,validUntil:MTIzNDU2Nzg5OQ==
```
*Valeurs décodées :*
- `version` = `"2.0"`
- `issuer` = `"issuer_id"`
- `globalPolicy` = `"FIFO"`
- `defaultTTL` = `3600`
- `validUntil` = `1234567899`

**Propriétés du Format :**
- **Cohérence** : Même structure pour tous les types de messages
- **Parsing unifié** : Un seul parseur pour toutes les métadonnées  
- **Extensibilité** : Ajout facile de nouvelles propriétés
- **Lisibilité** : Format `name:value` séparé par virgules, intuitif et parsable
- **Sécurité** : Valeurs encodées en base64 pour éviter les conflits de caractères
- **Robustesse** : Virgule comme séparateur (absente du charset base64)
- **Simplicité** : Pas de caractères d'échappement nécessaires
- **Structure Typée** : Valeurs JSON permettent types complexes (objets, tableaux, etc.)
- **Interopérabilité** : JSON facilite l'échange entre différents langages

**Propriétés Standard des Métadonnées :**

*Pour les messages normaux :*
- **mode** : Type de signature cryptographique (`rsa` ou `ecdsa`)
- **pubkey** : Clé publique encodée en base64 pour la validation
- **payload** : (Optionnel) Payload signé encodé en base64 pour les API keys

*Pour les messages de configuration :*
- **name** : Nom descriptif de la configuration
- **desc** : Description de la configuration
- **policy** : Politique appliquée (FIFO, LIFO, LRU, etc.)
- **maxSessions** : Nombre maximum de sessions simultanées
- **ttl** : Durée de vie par défaut (Time To Live)
- **validUntil** : Date limite de validité de la configuration

#### 4.2.7 Extensibilité des Séquences Réservées

**Séquences Réservées Futures :**
- **`__REVOKE__`** : Messages de révocation explicite
- **`__METRICS__`** : Messages de métriques/monitoring
- **`__STATUS__`** : Messages de statut système
- **`__HEALTH__`** : Messages de health check

**Règle de Nommage :** `__<NOM>__` (double underscore obligatoire)

---

## 5. Rétrocompatibilité

### 5.1 Migration Version 1 vers Version 2

- **Compatibilité** : v2 compatible avec v1 où `groupId = tokenId` et `sequenceId = "default"`
- **Détection** : La version est déterminée par le premier caractère avant le premier **`:`**
- **Coexistence** : Les deux versions peuvent coexister dans le même système

---

## 6. Propriétés du protocole

### 6.1 Invariants

1. **Unicité** : chaque couple version-dépendant identifie uniquement un message
2. **Séparation** : les caractères `:` et `|` sont réservés comme séparateurs
3. **Versionning** : la version détermine l'interprétation du PCI
4. **Protocole vs Implémentation** : le protocole définit le cadre, l'implémentation le réalise

### 6.2 Extensibilité

- **Nouvelles versions** : ajout possible de nouvelles versions (3, 4, ...)
- **Rétrocompatibilité** : maintien de la structure générale `[<version>] : [<pci>] | [<metadata>]`
- **Spécialisation par version** : chaque version peut définir ses propres spécificités