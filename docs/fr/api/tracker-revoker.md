# TokenTracker & TokenRevoker

Cette section documente les interfaces permettant de surveiller l'état actif des tokens (liveness) et de révoquer des sessions sur l'ensemble d'un déploiement distribué Veridot.

---

## 1. Interface : TokenTracker

`TokenTracker` permet aux applications de vérifier si un groupe possède au moins une session active, ou si un token particulier est toujours valide (ni expiré, ni révoqué).

### Méthode
```java
boolean hasActiveToken(Object target);
```

- **Rôle** : Vérifie s'il existe au moins un token actif correspondant à la cible.
- **Paramètres** :
  - `target` : Objet polymorphe (doit être de type `String`). Il est résolu de la manière suivante :
    1. **Token Signé (JWT)** : Décode la charge utile pour extraire l'ID de session et en vérifie la liveness.
    2. **Protocol V4 messageId** (commençant par `"4:"`) : Interroge directement l'état de liveness pour cette session spécifique.
    3. **groupId** : Traité comme un identifiant de groupe ; recherche dans le courtier s'il existe des sessions actives sous cette clé de groupe.
- **Retour** : `true` si au moins une session active correspondante est trouvée, `false` sinon.
- **Exceptions** :
  - `IllegalArgumentException` : Levée si `target` n'est pas une chaîne de caractères (`String`).

---

## 2. Interface : TokenRevoker

`TokenRevoker` invalide les tokens précédemment émis en publiant une enveloppe `LIVENESS` avec le statut `REVOKED` (`0x02`) sur le courtier (Broker).

### Méthode
```java
void revoke(String groupId, String sequenceId);
```

- **Rôle** : Révoque une session spécifique ou un groupe complet.
- **Paramètres** :
  - `groupId` : L'identifiant de groupe (ne doit être ni `null` ni vide).
  - `sequenceId` : L'identifiant de session spécifique à révoquer, ou `null` pour déclencher une **Révocation de Groupe** (invalide toutes les sessions liées au groupe).
- **Exceptions** :
  - `IllegalArgumentException` : Levée si `groupId` est null ou vide.

---

## 3. Exemples de Code

### Surveiller la Présence d'une Session Active
Avant d'effectuer une action sensible ou d'autoriser une nouvelle connexion, vérifiez si l'utilisateur possède déjà une session active :

```java
public void handleUserAccess(TokenTracker tracker, String userId) {
    boolean isUserLoggedIn = tracker.hasActiveToken(userId);
    if (isUserLoggedIn) {
        System.out.println("L'utilisateur a une session active. Action autorisée.");
    } else {
        System.out.println("L'utilisateur est hors-ligne. Redirection vers la page de login.");
    }
}
```

### Révocation de Session vs Révocation de Groupe

#### 1. Révocation d'une Session Unique (ex. Déconnexion d'un onglet de navigateur)
Extrayez les identifiants depuis l'objet `VerifiedData` et passez-les au révocateur :

```java
VerifiedData<String> result = verifier.verify(token, s -> s);
// Révoque UNIQUEMENT cette session d'appareil
revoker.revoke(result.groupId(), result.sequenceId());
```

#### 2. Révocation de Groupe (ex. Changement de mot de passe ou faille de sécurité)
Pour déconnecter instantanément un utilisateur de **tous** ses appareils et invalider tous ses tokens actifs :

```java
// Révoque TOUTES les sessions du groupe "user-123" en passant sequenceId à null
revoker.revoke("user-123", null);
```
Lors d'une révocation de groupe, `GenericSignerVerifier` liste toutes les sessions actives de ce groupe, publie une enveloppe `LIVENESS(REVOKED)` pour chacune d'entre elles et arrête leurs boucles de renouvellement en arrière-plan.
