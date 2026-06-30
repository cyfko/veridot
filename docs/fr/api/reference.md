# Référence API Java — Présentation

Le SDK Java de Veridot propose une API modulaire, thread-safe et hautement optimisée conçue pour s'intégrer facilement dans n'importe quel microservice ou application autonome.

---

## 1. Structure des Packages

L'API publique est organisée autour des packages suivants :

- **`io.github.cyfko.veridot.core`** : Contient les interfaces primaires et les modèles du domaine :
  - `DataSigner` : Contrat d'émission des tokens.
  - `TokenVerifier` : Contrat de vérification des tokens.
  - `TokenTracker` : Contrat d'interrogation de l'état actif.
  - `TokenRevoker` : Contrat de révocation des sessions.
  - `Broker` : Interface pour connecter un courtier de transport personnalisé.
  - `TrustRoot` : Magasin de confiance pour les clés long terme.
- **`io.github.cyfko.veridot.core.exceptions`** : Contient la hiérarchie des exceptions :
  - `VeridotException` : Exception d'exécution racine.
  - `BrokerExtractionException` : Levée lors d'un échec de lecture ou de validation du token.
  - `BrokerTransportException` : Levée en cas d'erreur réseau avec le courtier lors des écritures.
  - `SessionCapacityExceededException` : Levée lorsque la limite de sessions actives est dépassée.
- **`io.github.cyfko.veridot.core.impl`** : Implémentations concrètes :
  - `GenericSignerVerifier` : Orchestrateur central appliquant le Protocole V4.
  - `BasicConfigurer` : Constructeur fluide pour les demandes de signature.
  - `FileWatermarkStore` : Magasin de filigranes persistant avec contrôle d'intégrité.

---

## 2. Principes de Conception de l'API

- **Sécurité des Threads (Thread-Safety)** : Toutes les implémentations de `DataSigner`, `TokenVerifier`, `TokenTracker` et `TokenRevoker` sont thread-safe et peuvent être partagées par plusieurs threads de travail en toute sécurité.
- **Écritures Asynchrones** : La publication d'enveloppes sur le courtier renvoie un `CompletableFuture<Void>`, évitant de bloquer les threads d'exécution sur des entrées/sorties réseau.
- **Sécurisation par Défaut (Fail-Closed)** : En cas d'erreur réseau avec le courtier ou de clé racine introuvable, Veridot lève une `BrokerExtractionException` et rejette le token.
