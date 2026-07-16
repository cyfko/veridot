---
title: spring-autoconfiguration
description: Spring Boot starters for rapidly integrating Veridot V5 and the TAAS client.
keywords: [spring-boot, autoconfiguration, veridot, V5]
sidebar_position: 7
---

# L'intégration Spring Boot (spring-autoconfiguration)

Bienvenue dans le guide d'intégration de Veridot V5 pour Spring Boot. 

Le module `veridot-trustroots-spring` a été conçu pour simplifier considérablement l'intégration de la cryptographie et de la vérification d'identité au sein de vos microservices, en masquant la complexité de l'infrastructure de confiance.

---

## 1. Auto-configuration de l'Infrastructure de Confiance

L'ajout de la dépendance Maven active immédiatement la configuration automatique :

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-trustroots-spring</artifactId>
    <version>5.0.0</version>
</dependency>
```

Le starter se charge d'instancier la couche de confiance fondamentale :
- **`TaasTrustRootProvider`** : Gère la communication réseau avec le cluster TAAS pour la résolution des clés.
- **`CachingTrustRoot`** : Encapsule le provider pour offrir un cache L1 (mémoire) et L2 (disque), garantissant des performances de vérification sous la milliseconde.

La connexion au cluster TAAS se paramètre simplement dans votre fichier `application.yml` :

```yaml
veridot:
  taas:
    endpoints: 
      - "https://taas.internal.company.com"
    connect-timeout: 3s
  trustroots:
    provider-type: taas
    l1-max-size: 10000
    refresh-threshold: 1h
```

Ce cache (`TrustRoot`) est alors disponible dans le contexte Spring et peut être injecté directement si vous avez uniquement besoin de résoudre des identités.

---

## 2. Configuration du Moteur Cryptographique

Pour signer des données ou vérifier des enveloppes, l'application a besoin du moteur principal : le **`GenericSignerVerifier`**. 

Ce composant n'est pas instancié automatiquement car il requiert des éléments spécifiques à votre environnement de déploiement (vos clés privées et le paramétrage de votre Broker). Vous devez l'assembler dans une classe `@Configuration` dédiée.

### A. Configuration du Broker
Le moteur doit s'appuyer sur un broker (par exemple Kafka) pour la distribution asynchrone des identités et des révocations.

```java
import io.github.cyfko.veridot.core.Broker;
import io.github.cyfko.veridot.kafka.KafkaBroker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import java.util.Properties;

@Configuration
public class VeridotConfiguration {

    @Bean
    public Broker veridotBroker(@Value("${spring.kafka.bootstrap-servers}") String servers) {
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", servers);
        return new KafkaBroker(props);
    }
```

### B. Instanciation du GenericSignerVerifier
Une fois le broker défini, vous pouvez instancier le moteur en y injectant le `TrustRoot` généré par le starter, ainsi que vos clés cryptographiques.

```java
    import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
    import io.github.cyfko.veridot.core.Algorithm;
    import io.github.cyfko.veridot.core.EvictionPolicy;
    import io.github.cyfko.veridot.core.TrustRoot;
    import java.security.PrivateKey;
    import java.security.PublicKey;

    @Bean
    public GenericSignerVerifier genericSignerVerifier(
            Broker broker,               
            TrustRoot trustRoot,         // Injecté automatiquement par le starter
            PrivateKey privateKey,       // Idéalement chargé depuis un KMS ou Vault
            PublicKey publicKey) {       
        
        return new GenericSignerVerifier(
            broker, 
            trustRoot, 
            "order-service",            // L'identifiant (CN) de ce microservice
            privateKey, 
            publicKey,
            Algorithm.ED25519,
            1000,                       // Nombre maximal de sessions
            EvictionPolicy.FIFO
        );
    }
```

### C. Exposition des Interfaces Métier
Pour faciliter l'utilisation dans le reste de l'application, il est recommandé d'exposer les interfaces abstraites. Vos services métier pourront ainsi injecter `DataSigner` ou `TokenVerifier` sans se coupler à l'implémentation.

```java
    import io.github.cyfko.veridot.core.DataSigner;
    import io.github.cyfko.veridot.core.TokenVerifier;

    @Bean
    public DataSigner dataSigner(GenericSignerVerifier sv) { 
        return sv; 
    }

    @Bean
    public TokenVerifier tokenVerifier(GenericSignerVerifier sv) { 
        return sv; 
    }
} // Fin de la classe VeridotConfiguration
```

## Conclusion

Le module Spring Boot élimine le code répétitif (boilerplate) lié à la mise en place de la racine de confiance et du cache distribué. L'assemblage final du moteur cryptographique reste explicite, offrant ainsi le contrôle total nécessaire pour garantir la sécurité et s'adapter à n'importe quelle infrastructure.
