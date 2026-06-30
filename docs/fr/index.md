---
layout: home
hero:
  name: Veridot
  text: Vérification Distribuée de Tokens
  tagline: Distribution de clés éphémères, autorisation par capacités, filigranes de version monotones et gestion de capacité cloisonnée pour microservices Java.
  image:
    src: /logo.svg
    alt: Logo Veridot
  actions:
    - theme: brand
      text: Démarrer
      link: /fr/guide/getting-started
    - theme: alt
      text: Pourquoi Veridot ?
      link: /fr/guide/why
    - theme: alt
      text: GitHub
      link: https://github.com/cyfko/veridot
features:
  - icon: 🛡️
    title: Découplage Cryptographique
    details: Vérifiez des objets signés (JWT, clés API) entre services distribués sans accès direct à la base de données ni secret HMAC partagé.
  - icon: ⌛
    title: Monotone & Résistant aux Retours
    details: Suivi d'état actif avec filigranes de version locaux. Les vérificateurs rejettent toute tentative de retour à un état antérieur.
  - icon: 🚀
    title: Capacité de Session Cloisonnée
    details: Fixez des limites strictes aux sessions actives par groupe d'utilisateurs avec politiques d'éviction (FIFO, LIFO, LRU, REJECT).
  - icon: 🔌
    title: Transports Prêts pour la Prod
    details: Courtiers de stockage enfichables pour Apache Kafka + RocksDB et les bases de données SQL majeures (PostgreSQL, MySQL, SQL Server, Oracle).
---

<div class="content-section" style="max-width: 960px; margin: 40px auto; padding: 0 24px;">

## Exemple Minimal en 1 Minute

### 1. Émettre un Token (Nœud Émetteur)
```java
// Configure et émet un token valide pendant 1 heure (3600s)
String token = signer.sign("user@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .sequenceId("session-A")   // Optionnel : UUID auto-généré si omis
        .validity(3600)
        .build());
```

### 2. Vérifier le Token (Nœud Vérificateur)
```java
// Vérifie le token cryptographiquement et extrait les données liées
VerifiedData<String> result = verifier.verify(token, s -> s);

String email = result.data();           // "user@example.com"
String groupId = result.groupId();      // "user-123"
String sessionId = result.sequenceId(); // "session-A"
```

### 3. Révoquer la Session (Tout Nœud avec Capacité d'Écriture)
```java
// Révoque instantanément cette session sur tous les nœuds de vérification
revoker.revoke("user-123", "session-A");
```

</div>
