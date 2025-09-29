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

**Caractéristiques principales :**
- **Structure** : `[2] : [<groupId> : <sequenceId>] | [<metadata>]`
- **Architecture** : Système de groupes et séquences pour gestion multi-sessions
- **Configuration** : Messages de configuration hiérarchiques via séquences réservées
- **Métadonnées** : Format unifié avec encodage JSON+Base64

**Innovations v2 :**
- Groupes logiques permettant plusieurs sessions simultanées
- Configuration dynamique via le protocole lui-même
- Séquences réservées (`__CONFIG__`, `__ALL__`) pour administration
- Hiérarchie de résolution (local → site → global → défaut)

**📋 Pour la spécification complète de la version 2 :**  
👉 **[Voir PROTOCOL_V2.md](./PROTOCOL_V2.md)** - Spécification détaillée avec contraintes syntaxiques, exemples complets et règles d'implémentation

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