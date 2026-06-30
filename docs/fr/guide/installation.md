# Installation

Veridot requiert **Java 25** ou supérieur et est distribué via Maven Central.

Le projet est structuré sous forme de modules afin de vous permettre de n'importer que les dépendances nécessaires à votre couche de transport (Broker).

---

## 1. Module Core (Obligatoire)

Le module `veridot-core` contient toutes les interfaces, le moteur d'état, les contrôles de sécurité et les structures standard en mémoire.

### Maven
Ajoutez cette dépendance à votre fichier `pom.xml` :

```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>4.0.0</version>
</dependency>
```

### Gradle
Ajoutez cette dépendance à votre fichier `build.gradle` ou `build.gradle.kts` :

```kotlin
implementation("io.github.cyfko:veridot-core:4.0.0")
```

---

## 2. Implémentations de Broker (Optionnel)

Choisissez **une** des intégrations de courtier suivantes selon votre infrastructure :

### Courtier Apache Kafka (`veridot-kafka`)
Utilisez ce module pour propager les métadonnées de vérification via un sujet Kafka. Il intègre **RocksDB** en local pour rendre durables les filigranes et assurer des lectures ultra-rapides hors-tas (off-heap) côté vérificateur.

#### Maven
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>4.0.0</version>
</dependency>
```

#### Gradle
```kotlin
implementation("io.github.cyfko:veridot-kafka:4.0.0")
```

### Courtier Base de données SQL (`veridot-databases`)
Utilisez ce module pour stocker et interroger les métadonnées de vérification dans une base de données relationnelle. Il prend en charge la création automatique de tables et propose des dialectes upsert optimisés pour **PostgreSQL, MySQL, MariaDB, SQL Server et Oracle**.

#### Maven
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-databases</artifactId>
    <version>4.0.0</version>
</dependency>
```

#### Gradle
```kotlin
implementation("io.github.cyfko:veridot-databases:4.0.0")
```

---

## 3. Dépendances Transitives

La bibliothèque est conçue pour être très légère et n'importe que le strict nécessaire :
- **Jackson Databind** (pour la sérialisation/désérialisation JSON)
- **Apache Kafka Clients** (uniquement dans `veridot-kafka`)
- **RocksDB Java** (uniquement dans `veridot-kafka`)

Aucune bibliothèque de logging externe n'est imposée ; Veridot utilise le système standard `java.util.logging` (JUL).
