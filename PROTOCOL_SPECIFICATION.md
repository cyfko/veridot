# Spécification du Protocole Véridot v2

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

## 3. Identifiant unique de token

L'identifiant unique du token généré suit invariablement la forme :

**[&lt;version&gt;] : [&lt;details&gt;]**

Cette règle s'applique à toutes les versions du protocole.

---

## 4. Version 2 - Spécification du PCI

Pour la version 2, le **PCI** est composé de deux parties séparées par **`:`** :

**&lt;groupId&gt; : &lt;sequenceId&gt;**

### 4.1 Définitions

- **groupId** : identifiant du groupe logique auquel appartient le token
  - Type : chaîne de caractères alphanumériques
  - Utilisation typique : identifiant utilisateur, identifiant de ressource
  
- **sequenceId** : identifiant unique d'une séquence au sein du groupe
  - Type : chaîne de caractères alphanumériques
  - Utilisation typique : identifiant de session, identifiant de connexion

### 4.2 TokenId en version 2

Le **tokenId** est défini comme suit :

**tokenId = [2] : [&lt;groupId&gt; : &lt;sequenceId&gt;]**

**Exemple :** `2:user123:session001`

---

## 5. Message complet version 2

**Structure finale :**

**[2] : [&lt;groupId&gt; : &lt;sequenceId&gt;] | [&lt;metadata&gt;]**

### 5.1 Contraintes syntaxiques

- **version** : exactement `2`
- **groupId** : non vide, ne contient pas les caractères `:` et `|`
- **sequenceId** : non vide, ne contient pas les caractères `:` et `|`
- **metadata** : données binaires ou textuelles, format libre

### 5.2 Sémantique opérationnelle

- Un **groupe** peut contenir plusieurs **séquences** actives simultanément
- Chaque **séquence** au sein d'un **groupe** est unique
- La gestion des sessions simultanées et des révocations s'effectue au niveau du **groupe**
- Les politiques de contrôle (limitation de sessions, révocations) sont **externes au protocole**

---

## 6. Rétrocompatibilité

### 6.1 Version 1 (héritage)

- **Structure message** : `[1] : [&lt;tokenId&gt;] | [&lt;metadata&gt;]`
- **Structure tokenId** : `[1] : [&lt;tokenId&gt;]`
- **Sémantique** : un token = une session unique
- **Migration** : compatible avec la version 2 où `groupId = tokenId` et `sequenceId = "default"`

### 6.2 Détection de version

La version est déterminée par le premier caractère avant le premier **`:`** dans l'en-tête du message.

---

## 7. Messages de Configuration

### 7.1 Configuration Locale (Par Groupe)

**Format :** `[2] : [<groupId>] : [__CONFIG__] | [<policy_metadata>]`

- **Portée** : Spécifique au groupe identifié par `groupId`
- **Priorité** : Maximale (surcharge toute autre configuration)
- **Usage** : Politique personnalisée pour un groupe particulier

**Exemple :** `2:user123:__CONFIG__|{"policy":"FIFO","maxSessions":3,"ttl":3600}`

### 7.2 Configuration Globale (Par Site/Service)

**Format :** `[2] : [__CONFIG__] : [<siteId>] | [<policy_metadata>]`

- **Portée** : Tous les groupes du site/service identifié par `siteId`
- **Priorité** : Intermédiaire (appliquée si pas de configuration locale)
- **Usage** : Politique par défaut pour un domaine, service ou environnement

**Exemple :** `2:__CONFIG__:PAYMENT_SERVICE|{"policy":"LRU","maxSessions":10,"ttl":7200}`

### 7.3 Résolution Hiérarchique

**Ordre de priorité pour résoudre la configuration d'un groupe :**

1. **Configuration locale** : `2:<groupId>:__CONFIG__|<policy>`
2. **Configuration de site** : `2:__CONFIG__:<siteId>|<policy>`
3. **Configuration globale** : `2:__CONFIG__:ALL|<policy>`
4. **Configuration par défaut** : Politique définie par l'implémentation

### 7.4 Évolution de Configuration

- **Principe** : Le message de configuration le plus récent fait foi
- **Mécanisme** : Même infrastructure de transport que les tokens normaux
- **Cohérence** : Ordre garanti des événements via le broker
- **Changement dynamique** : Nouveau message de configuration = nouvelle politique appliquée

---

## 8. Propriétés du protocole

### 8.1 Invariants

1. **Unicité** : un couple `(groupId, sequenceId)` identifie uniquement un token
2. **Séparation** : les caractères `:` et `|` sont réservés comme séparateurs
3. **Versionning** : la version détermine l'interprétation du PCI
4. **Configuration intégrée** : les messages de configuration utilisent le même protocole
5. **Séquences réservées** : `__CONFIG__` est une séquence réservée pour la configuration

### 8.2 Extensibilité

- **Nouvelles versions** : ajout possible de nouvelles versions (3, 4, ...)
- **Rétrocompatibilité** : maintien de la structure générale `[<version>] : [<pci>] | [<metadata>]`
- **Configuration évolutive** : ajout de nouveaux types de configuration via des séquences réservées
- **Hiérarchie extensible** : support de nouveaux niveaux de configuration