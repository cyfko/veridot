# DataSigner & BasicConfigurer

L'interface `DataSigner` est responsable de la génération des tokens signés (JWT) ou des identifiants légers (`messageId`), ainsi que de la publication des enveloppes de métadonnées de vérification correspondantes sur le courtier (Broker).

---

## 1. Interface : DataSigner

### Méthode
```java
String sign(Object data, Configurer configurer) 
    throws DataSerializationException, BrokerTransportException;
```

- **Rôle** : Émet un token de session signé contenant une charge utile.
- **Paramètres** :
  - `data` : L'objet à sérialiser et à embarquer (ne doit pas être `null`).
  - `configurer` : La configuration de la demande de signature (ne doit pas être `null`).
- **Retour** :
  - En mode `DIRECT` : La chaîne de caractères du token complet et signé cryptographiquement (JWT).
  - En mode `INDIRECT` : Le messageId léger (ex. `4:groupId:sequenceId`). Le token complet est stocké sur le broker.
- **Exceptions** :
  - `DataSerializationException` : Levée si l'objet ne peut pas être sérialisé en chaîne de caractères.
  - `BrokerTransportException` : Levée en cas d'erreur de publication des métadonnées sur le courtier.
  - `IllegalArgumentException` : Levée si un paramètre de configuration est invalide (ex. durée négative).

---

## 2. Configuration : DataSigner.Configurer

Définit les paramètres requis lors d'une demande de signature :

- **`getGroupId()`** : Retourne l'identifiant du groupe métier (1 à 125 caractères, sans espaces ni `:`). **Précondition** : Ne doit être ni null ni vide.
- **`getSequenceId()`** : Clé de session facultative. Si elle est absente (`null`), un UUID aléatoire est généré par le signataire.
- **`getDistribution()`** : Retourne `DistributionMode.DIRECT` ou `DistributionMode.INDIRECT`.
- **`getDuration()`** : Durée de validité en secondes. **Précondition** : Doit être strictement supérieure à 0.
- **`getSerializer()`** : Fonction de sérialisation convertissant l'objet en chaîne textuelle.

---

## 3. Implémentation : BasicConfigurer

`BasicConfigurer` est l'implémentation par défaut de `Configurer` proposant un constructeur fluide (builder pattern).

### Préconditions et Invariants
- **`groupId`** : Obligatoire. Ne peut pas contenir de deux-points (`:`), de virgules (`,`), de barres verticales (`|`) ou de caractères de contrôle.
- **`validity`** : Obligatoire. Doit être un entier positif (durée en secondes).
- **`serializer`** : Par défaut, utilise Jackson (`ObjectMapper`) sauf si configuré via `serializedBy`.

---

## 4. Exemples de Code

### Utilisation Standard Java SE (Sérialiseur JSON POJO)
```java
import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;

public class SignerExample {
    
    public record UserClaims(String email, String role, String tenantId) {}

    public String issueUserSession(DataSigner signer, UserClaims claims) {
        // Construction de la configuration de signature
        BasicConfigurer config = BasicConfigurer.builder()
                .groupId(claims.tenantId())        // ex. "tenant-A"
                .sequenceId("session-" + claims.email()) // ID de session spécifique
                .distribution(DistributionMode.DIRECT)  // Retourne le JWT directement
                .validity(3600)                     // Valide pendant 1 heure
                .build();
        
        // Signature et publication des métadonnées
        return signer.sign(claims, config);
    }
}
```

### Mode Indirect (Grands Volumes / Charges Utiles Lourdes)
En mode `INDIRECT`, la charge utile est écrite directement sur le courtier, et la méthode de signature ne renvoie qu'un `messageId` compact. Idéal pour réduire la bande passante sur les requêtes HTTP :

```java
String messageId = signer.sign(largePayload,
    BasicConfigurer.builder()
        .groupId("analytics-group")
        .distribution(DistributionMode.INDIRECT)
        .validity(1800) // 30 minutes
        .build()
);
// Retourne : "4:analytics-group:7b8d4f..." (Clé du Broker)
```
