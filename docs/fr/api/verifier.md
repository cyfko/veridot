# TokenVerifier & VerifiedData

L'interface `TokenVerifier` est le point d'entrée principal pour les nœuds vérificateurs. Elle valide la signature cryptographique des tokens entrants, contrôle les chaînes d'autorisation par capacités, applique l'invariant de version monotone et vérifie la liveness (l'état actif) des sessions.

---

## 1. Interface : TokenVerifier

### Méthode
```java
<T> VerifiedData<T> verify(String token, Function<String, T> deserializer)
    throws BrokerExtractionException, DataDeserializationException;
```

- **Rôle** : Valide un token et extrait sa charge utile désérialisée.
- **Paramètres** :
  - `token` : Le token à vérifier (JWT signé ou chaîne `messageId` ; ne doit être ni `null` ni vide).
  - `deserializer` : Fonction de conversion de la chaîne brute vers le type cible `T` (ne doit pas être `null`).
- **Retour** :
  - Une instance de `VerifiedData<T>` contenant la charge utile désérialisée et les identifiants du protocole.
- **Exceptions** :
  - `BrokerExtractionException` : Levée si le token est invalide, expiré, révoqué, possède une version obsolète, ou si les métadonnées sur le courtier sont inaccessibles.
  - `DataDeserializationException` : Levée si le token est valide cryptographiquement, mais que sa charge utile ne peut pas être convertie vers `T`.
- **Préconditions** :
  - Le token doit commencer par un en-tête JWT valide ou le préfixe de messageId V4 (`"4:"`).
  - La réconciliation locale du scope doit être active et non obsolète (retard `< 60 minutes`).
- **Postconditions** :
  - Le filigrane de version local associé à la session (`EntryId`) est mis à jour avec la version de l'enveloppe vérifiée.

---

## 2. Modèle : `VerifiedData<T>`

Un conteneur générique transportant le résultat d'une vérification réussie :

- **`data()`** : Retourne la charge utile désérialisée de type `T`.
- **`groupId()`** : Retourne l'identifiant du groupe métier associé (ex. ID utilisateur).
- **`sequenceId()`** : Retourne l'identifiant unique de la séquence de session (ex. UUID de session).

---

## 3. Exemples de Code

### Intégration Java SE Classique
```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;

public class VerifierExample {

    public record UserProfile(String email, String role) {}

    public void processRequest(TokenVerifier verifier, String token) {
        try {
            // Vérification et désérialisation en POJO UserProfile
            VerifiedData<UserProfile> result = verifier.verify(
                    token,
                    BasicConfigurer.deserializer(UserProfile.class)
            );

            UserProfile profile = result.data();
            System.out.println("Authentifié : " + profile.email());
            System.out.println("ID Session : " + result.sequenceId());

        } catch (BrokerExtractionException e) {
            System.err.println("Échec d'authentification : " + e.getMessage());
            // Logger le code d'erreur cryptographique (ex: V4202, V4201)
        } catch (DataDeserializationException e) {
            System.err.println("Erreur de parsing JSON : " + e.getMessage());
        }
    }
}
```

### Intégration dans un Filtre Spring Boot (HTTP Security Filter)
Pour intercepter les requêtes HTTP dans une application Spring Boot :

```java
import io.github.cyfko.veridot.core.TokenVerifier;
import io.github.cyfko.veridot.core.VerifiedData;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class VeridotSecurityFilter implements Filter {

    private final TokenVerifier tokenVerifier;

    public VeridotSecurityFilter(TokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String authHeader = req.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token Bearer manquant");
            return;
        }

        String token = authHeader.substring(7);
        try {
            // Vérification
            VerifiedData<String> verified = tokenVerifier.verify(token, s -> s);
            
            // Liaison au contexte de la requête HTTP
            req.setAttribute("veridot.groupId", verified.groupId());
            req.setAttribute("veridot.sequenceId", verified.sequenceId());
            req.setAttribute("veridot.payload", verified.data());
            
            chain.doFilter(request, response);
        } catch (Exception e) {
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token invalide ou révoqué");
        }
    }
}
```
