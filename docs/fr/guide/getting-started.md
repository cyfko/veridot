# Démarrage Rapide

Ce guide vous accompagne pas à pas dans la configuration de Veridot et l'exécution de votre premier cycle d'émission-vérification-révocation de token en Java. Pour cette démonstration, nous utiliserons un courtier (Broker) en mémoire et un magasin de clés racine statique.

---

## 1. Code Complet de Démarrage Rapide

Voici une classe Java complète et compilable qui montre le pipeline de vérification.

```java
package io.github.cyfko.example;

import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.core.impl.InMemoryBroker;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;

public class VeridotQuickstart {

    public static void main(String[] args) throws Exception {
        // Étape 1: Générer des clés cryptographiques
        // Nous générons une clé racine à long terme pour signer les enveloppes de vérification.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair rootKeyPair = kpg.generateKeyPair();
        PublicKey rootPublicKey = rootKeyPair.getPublic();
        PrivateKey rootPrivateKey = rootKeyPair.getPrivate();

        String issuerId = "admin-signer";

        // Étape 2: Initialiser les dépendances
        // - Broker : magasin en mémoire (pour les tests)
        // - TrustRoot : résout issuerId vers la clé publique racine
        Broker broker = new InMemoryBroker();
        TrustRoot trustRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                if (issuerId.equals(issuer)) {
                    return new TrustIdentity(rootPublicKey, true);
                }
                return null;
            }
        };

        // Étape 3: Instancier l'Orchestrateur
        // GenericSignerVerifier implémente DataSigner, TokenVerifier, TokenRevoker, TokenTracker
        GenericSignerVerifier orchestrator = new GenericSignerVerifier(
                broker,
                trustRoot,
                issuerId,
                rootPrivateKey,
                Algorithm.ED25519
        );

        // Étape 4: Définir une classe pour la charge utile
        record UserSession(String email, String role) {}
        UserSession userPayload = new UserSession("alice@example.com", "ADMIN");

        System.out.println("--- 1. ÉMISSION DU TOKEN ---");
        // Étape 5: Émettre un token (mode DIRECT)
        // Cela signe le token avec une clé éphémère et publie KEY_EPOCH & LIVENESS.
        String token = orchestrator.sign(userPayload,
                BasicConfigurer.builder()
                        .groupId("user-alice")
                        .validity(3600) // Valide pendant 1 heure (3600 secondes)
                        .build()
        );
        System.out.println("Token émis (JWT) : " + token);

        System.out.println("\n--- 2. VÉRIFICATION DU STATUT ACTIF ---");
        // Étape 6: Vérifier la liveness avant la vérification du token
        boolean isActive = orchestrator.hasActiveToken("user-alice");
        System.out.println("Le groupe 'user-alice' a une session active ? " + isActive); // true

        System.out.println("\n--- 3. VÉRIFICATION DU TOKEN ---");
        // Étape 7: Vérifier le token et extraire la charge utile
        // Cela récupère les métadonnées, valide les capacités, vérifie les versions et signatures.
        VerifiedData<UserSession> verified = orchestrator.verify(
                token,
                BasicConfigurer.deserializer(UserSession.class)
        );
        System.out.println("Vérification réussie !");
        System.out.println("GroupId extrait : " + verified.groupId());
        System.out.println("SequenceId extrait : " + verified.sequenceId());
        System.out.println("Données : " + verified.data().email() + " (" + verified.data().role() + ")");

        System.out.println("\n--- 4. RÉVOCATION DE LA SESSION ---");
        // Étape 8: Révoquer la session à l'aide des identifiants extraits
        orchestrator.revoke(verified.groupId(), verified.sequenceId());
        System.out.println("Session révoquée : " + verified.sequenceId());

        System.out.println("\n--- 5. STATUT ACTIF APRÈS RÉVOCATION ---");
        // Étape 9: Ré-évaluer la liveness
        boolean isActivePostRevoke = orchestrator.hasActiveToken("user-alice");
        System.out.println("Le groupe 'user-alice' a une session active ? " + isActivePostRevoke); // false

        try {
            // Étape 10: La tentative de vérification doit échouer
            orchestrator.verify(token, BasicConfigurer.deserializer(UserSession.class));
        } catch (Exception e) {
            System.out.println("La vérification a échoué comme attendu : " + e.getMessage());
        }

        // Nettoyer les threads d'arrière-plan du planificateur
        orchestrator.close();
    }
}
```

---

## 2. Explication des Concepts Clés

### Entités principales
1. **`Broker`** : Le bus de messages ou le transport de base de données. En production, vous utiliserez `KafkaBroker` ou `DatabaseBroker`.
2. **`TrustRoot`** : Le magasin de confiance hors-bande qui valide les signatures à long terme. Dans cet exemple, nous associons `"admin-signer"` à `rootPublicKey`.
3. **`GenericSignerVerifier`** : L'orchestrateur central. Il signe les tokens, publie les enveloppes cryptographiques sur le broker, maintient la liveness en tâche de fond et valide les tokens entrants.
4. **`BasicConfigurer`** : Un constructeur fluide pour configurer la durée de vie (TTL) de la session, la sérialisation et le groupe de session.
