# FAQ & Dépannage

Cette section regroupe les questions fréquemment posées et les guides de résolution des anomalies pour les déploiements Veridot V4.

---

## 1. Résolution des Codes d'Erreur

### Pourquoi ai-je des erreurs `V4201 (STALE_VERSION)` ?
- **Cause** : Un émetteur a tenté de publier une enveloppe avec un numéro de version inférieur ou égal au filigrane accepté par le vérificateur.
- **Résolution** :
  - Vérifiez si deux instances d'émetteurs écrivent sur le même `EntryId` logique sans coordination.
  - Si vous re-signez une capacité ou une configuration, assurez-vous d'incrémenter le numéro de version par rapport à la valeur précédemment publiée. Les vérificateurs rejettent toute mise à jour dont la version n'augmente pas.

### Qu'est-ce qui cause les exceptions `RECONCILIATION_STALE` côté vérificateur ?
- **Cause** : Le nœud vérificateur n'a pas réussi à exécuter son cycle de réconciliation périodique par rapport au courtier dans la limite tolérée (60 minutes). Pour éviter les attaques par retour en arrière, le vérificateur se bloque en mode sécurisé et rejette tout jeton.
- **Résolution** :
  - Vérifiez que le courtier est en ligne et accessible depuis les pods vérificateurs.
  - Inspectez les logs des vérificateurs pour y déceler des erreurs `TRANSPORT_UNAVAILABLE (V4401)` lors des scans d'instantanés.
  - Vérifiez la latence réseau et les temps de pause de nettoyage de la mémoire (JVM Garbage Collection).

### Pourquoi mon token est-il rejeté avec le code `V4202 (LIVENESS_NOT_ESTABLISHED)` ?
- **Cause** : La validation de l'état actif a échoué pour l'une de ces raisons :
  1. L'entrée `LIVENESS` est introuvable sur le Broker.
  2. L'entrée porte le statut `REVOKED`.
  3. L'attestation de liveness a expiré temporellement (`now >= validUntil`).
- **Résolution** :
  - Vérifiez que la boucle de renouvellement de liveness en arrière-plan fonctionne sur l'émetteur.
  - Assurez-vous qu'aucun retard réseau n'empêche l'émetteur de publier le renouvellement avant l'expiration de la précédente attestation.
  - Vérifiez la synchronisation des horloges des serveurs via NTP.

---

## 2. Questions Courantes d'Intégration

### Veridot est-il thread-safe ?
Oui. L'orchestrateur principal `GenericSignerVerifier` est entièrement thread-safe. Tous les traitements de signature, vérification et révocation peuvent être partagés en toute sécurité par les différents threads de votre application.

### Puis-je faire du multi-tenant avec Veridot ?
Oui. Les scopes de groupe (`group:<groupId>`) offrent un cloisonnement strict. Vous pouvez définir des limites et des politiques d'éviction distinctes pour chaque locataire (tenant) en publiant des entrées `CONFIG` au niveau du groupe.

### Comment configurer les durées de vie des caches ?
Vous pouvez ajuster les caches grâce aux variables d'environnement suivantes :
- **`VDOT_CAPABILITY_CACHE_TTL_SECONDS`** : Durée de mise en cache des chaînes de capacités valides (par défaut : 60s).
- **`VDOT_CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS`** : Durée de mise en cache des échecs de capacités (par défaut : 5s) pour éviter de saturer le Broker.
- **`VDOT_CLOCK_DRIFT_TOLERANCE_SECONDS`** : Marge de dérive d'horloge autorisée (par défaut : 300s).
- **`VDOT_RECONCILIATION_INTERVAL_MINUTES`** : Fréquence de la réconciliation (par défaut : 15m).
