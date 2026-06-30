# Contribuer à Veridot

Merci de l'intérêt que vous portez au projet Veridot ! En tant que bibliothèque de sécurité de niveau entreprise, nous appliquons des exigences strictes en matière de qualité de code, de contrôles de sécurité et de couverture de tests.

---

## 1. Prérequis de Développement

Pour compiler et tester Veridot localement, vous devez disposer de :
- **Java Development Kit (JDK) 25** ou supérieur.
- **Apache Maven 3.9** ou supérieur (ou utiliser le wrapper `./mvnw` inclus).
- **Docker** fonctionnel sur votre machine (nécessaire pour exécuter les tests d'intégration via Testcontainers).

---

## 2. Structure du Projet

```
veridot/
├── java/
│   ├── veridot-core/        # API Core, sérialisation TLV et moteur d'état
│   ├── veridot-kafka/       # Courtier Kafka + cache RocksDB local
│   ├── veridot-databases/   # Courtier base de données relationnelle (JDBC)
│   └── veridot-tests/       # Tests d'intégration et de charge
├── PROTOCOL_V4.md           # Spécification binaire du Protocole V4
└── KEY_ROTATION.md          # Guide opérationnel de rotation des clés
```

---

## 3. Compilation & Exécution des Tests

### Compiler la Bibliothèque
Pour compiler et installer la bibliothèque dans votre dépôt Maven local :
```bash
./mvnw clean install -DskipTests
```

### Exécuter les Tests Unitaires
Pour lancer les tests unitaires (ne requiert pas Docker) :
```bash
./mvnw test -pl veridot-core
```

### Exécuter les Tests d'Intégration
Les tests d'intégration démarrent de vrais conteneurs (PostgreSQL, MySQL, MariaDB, SQL Server) et un cluster Kafka via **Testcontainers**. Assurez-vous que Docker est démarré :
```bash
./mvnw test -pl veridot-tests
```

---

## 4. Directives de Code et Sécurité

- **Documentation** : Ne supprimez pas les commentaires ou les Javadocs. Toute nouvelle API publique doit être documentée avec ses préconditions et exceptions.
- **Timing-Safety** : Utilisez systématiquement des vérifications à temps constant lors de la manipulation de clés, d'empreintes ou de signatures cryptographiques.
- **Vérification des Nuls** : Validez systématiquement les entrées avec `Objects.requireNonNull` pour lever des exceptions explicites en cas d'arguments nuls.
- **Messages de Commit** : Nous respectons les conventions de commits (Conventional Commits) :
  - `feat(core): ...` pour les nouvelles fonctionnalités
  - `fix(kafka): ...` pour les corrections de bugs
  - `docs: ...` pour la documentation
  - `test: ...` pour les nouveaux tests
