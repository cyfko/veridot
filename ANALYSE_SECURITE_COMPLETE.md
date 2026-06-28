# ANALYSE DE SÉCURITÉ COMPLÈTE - Veridot Core Java
## Rapport Détaillé de la Base de Code

**Date:** 28 Juin 2026  
**Scope:** `/java/veridot-core/src/main/java/io/github/cyfko/veridot/core/`  
**Version:** Protocol V4 (avec compatibilité V3)

---

## 1. ARCHITECTURE GLOBALE ET FLUX DE DONNÉES

### 1.1 Orchestration Principale
**Fichier:** [GenericSignerVerifier.java](GenericSignerVerifier.java#L1-L50)

La classe `GenericSignerVerifier` est l'orchestrateur central implémentant:
- `DataSigner` - signature et émission de tokens
- `TokenVerifier` - vérification et extraction de payloads
- `TokenRevoker` - révocation de sessions
- `TokenTracker` - surveillance de l'activité

**Architecture interne:**
```
GenericSignerVerifier
├── KeyRotationService (rotation d'ephemeral keys toutes les 24h)
├── LivenessManager (gestion de l'attestation de session active)
├── CapacityManager (contrôle des limites de capacité)
├── ReconciliationManager (réconciliation périodique du watermark)
├── CapabilityVerifier (validation des chaînes de délégation)
├── EntryVerifier (orchestrateur de vérification en 9 étapes)
├── SessionCounter (énumération de sessions actives)
├── VersionWatermark (tracker monotone par EntryId)
└── EntryPublisher (signature et publication aux brokers)
```

**Thread-safety:**
- `groupLocks` (ConcurrentHashMap) pour synchronisation par groupe [Ligne 35](GenericSignerVerifier.java#L35)
- `ScheduledExecutorService` avec 2 threads pour renouvellement + réconciliation [Ligne 36](GenericSignerVerifier.java#L36)
- Accès concurrent via `volatile` sur `KeyRotationService.currentSnapshot`

### 1.2 Flux de Signature (`sign()`)
**Fichier:** [GenericSignerVerifier.java](GenericSignerVerifier.java#L104-L200)

```
1. Validation du groupId et sequenceId (ou génération UUID)
   └─ Protocol.validateIdentifier() [ligne 113-115]
   
2. Achat d'un verrou de groupe pour cette transaction
   └─ synchronized(groupLock) [ligne 120]
   
3. Démarrage de la réconciliation périodique si absent
   └─ ensureReconciliationStarted(scope) [ligne 123]
   
4. Résolution de la configuration effective (group → site → global)
   └─ ConfigResolver.resolve() [ligne 135]
   
5. Snapshot atomique des clés éphémères actuelles
   └─ KeyRotationService.snapshot() [ligne 138]
   
6. Application des limites de capacité + éviction si nécessaire
   └─ CapacityManager.enforceCapacity() [ligne 141]
   
7. Sérialisation du payload
   └─ configurer.getSerializer().apply() [ligne 154]
   
8. Construction et signature d'un JWT avec la clé éphémère
   └─ JwtMaker.builder()...compact() [ligne 160-166]
   
9. Publication de KEY_EPOCH (contient clé publique + validité)
   └─ EntryPublisher.publish(EntryType.KEY_EPOCH) [ligne 174]
   
10. Publication de LIVENESS(ACTIVE)
    └─ LivenessManager.publishActive() [ligne 185]
    
11. Démarrage d'une boucle de renouvellement (80% de la validité)
    └─ LivenessManager.startRenewalLoop() [ligne 188]
    
12. Retour du token ou messageId selon le mode de distribution
    └─ Return DIRECT (JWT) ou INDIRECT (messageId) [ligne 190]
```

### 1.3 Flux de Vérification (`verify()`) - 9 ÉTAPES
**Fichier:** [GenericSignerVerifier.java](GenericSignerVerifier.java#L202-L268)

```
ÉTAPE 1-2: Résolution du format token et extraction du messageId
├─ Protocol.isMessageId() vs Protocol.isJwt() [ligne 217-220]
├─ Extraction du sub claim du JWT si nécessaire [ligne 276]
└─ Parsing du messageId en (version, groupId, sequenceId) [ligne 229]

ÉTAPE 3-7: Vérification de KEY_EPOCH (via EntryVerifier.verifyKeyEpoch)
├─ [EntryVerifier.java:54] Récupération depuis broker
├─ [EntryVerifier.java:65] Parsing d'Envelope avec validation structurelle
│  └─ Envelope.parse() effectue 9 validations structurelles
├─ [EntryVerifier.java:68] Vérification de signature (SignatureVerifier.verify)
│  ├─ Résolution TrustRoot.resolve(issuer) → PublicKey
│  └─ Vérification RSA-SHA256 ou Ed25519
├─ [EntryVerifier.java:73] Vérification de capabilité (CapabilityVerifier.assertAuthorized)
│  └─ Récursion jusqu'à l'identité root (max 10 hops)
├─ [EntryVerifier.java:81] Vérification du watermark de version (monotonie)
├─ [EntryVerifier.java:88] Validation temporelle
│  ├─ now >= validFrom - 300000 (5 min dérive antérieure)
│  └─ now < validUntil
└─ [EntryVerifier.java:95] Vérification LIVENESS=ACTIVE
   ├─ LivenessChecker.assertLive() [LivenessChecker.java:1]
   ├─ Positive proof: LIVENESS entry doit exister
   ├─ Status doit être 0x01 (ACTIVE), pas 0x02 (REVOKED)
   └─ validUntil doit être dans le futur

ÉTAPE 8: Vérification cryptographique du JWT
├─ [GenericSignerVerifier.java:242] Extraction header.payload.signature
├─ [GenericSignerVerifier.java:252] Base64url decode signature
└─ [EntryVerifier.java:119] Vérification avec clé publique éphémère
   ├─ PublicKey reconstruite depuis KEY_EPOCH.payload.pk (X509 DER)
   ├─ RSA-SHA256 ou ECDSA-SHA256 selon alg code
   └─ sig.verify() - VULNÉRABLE AUX TIMING ATTACKS

ÉTAPE 9: Désérialisation
└─ [GenericSignerVerifier.java:259] ObjectMapper.readTree() + extraction 'data'
   └─ [GenericSignerVerifier.java:260] Appel du deserializer utilisateur
```

---

## 2. DÉTAILS D'IMPLÉMENTATION CRYPTOGRAPHIQUE

### 2.1 Cryptographie Longue Durée
**Fichier:** [Config.java](Config.java#L1)

| Aspect | Valeur | Notes |
|--------|--------|-------|
| Algorithme RSA-LT | 3072 bits | [Ligne 36] Recommandation post-2030 NIST |
| Générateur | KeyPairGenerator standard JDK | Aucun seed custom |
| SecureRandom | Défaut système | Pas d'initialisation explicite |

### 2.2 Cryptographie Éphémère (Rotation)
**Fichier:** [KeyRotationService.java](KeyRotationService.java#L1-L60)

```
Rotation toutes les KEYS_ROTATION_MINUTES (défaut: 1440 min = 24h)
├─ Configurable via VDOT_KEYS_ROTATION_MINUTES
├─ ScheduledExecutorService.scheduleAtFixedRate [ligne 36]
└─ Generation: RSA-3072 ou EC-256 (secp256r1)

Accès atomique:
├─ volatile KeySnapshot currentSnapshot [ligne 28]
└─ snapshot() retourne atomiquement (privateKey, publicKey, alg) [ligne 57]
   └─ Prévient les race conditions reader-writer

Format d'encodage KEY_EPOCH:
├─ Tag 0x01: alg (1 byte): 0x01=RSA-SHA256, 0x02=ECDSA-SHA256
├─ Tag 0x02: epochId (8 bytes)
├─ Tag 0x03: pk (variable): DER-encoded X509 public key
├─ Tag 0x04: validFrom (8 bytes i64, ms since epoch)
└─ Tag 0x05: validUntil (8 bytes i64, ms since epoch)
```

**PROBLÈME IDENTIFIÉ:** Incohérence d'algorithme
- [GenericSignerVerifier.java:86] génère RSA ou EC basé sur `longTermKey.getAlgorithm()`
- [EntryVerifier.java:122] vérifie JWT avec "SHA256withECDSA" pour alg==0x02
- MAIS [KeyRotationService.java:45] utilise "EC" pour 0x02, OK
- [JwtVerifier.java:31] utilise "SHA256withRSA" only

### 2.3 Signature et Vérification
**Fichier:** [SignatureVerifier.java](SignatureVerifier.java#L1-L55)

```
Vérification d'Envelope:
1. Résolution TrustRoot [ligne 21]
   └─ PublicKeyTrustRoot.resolve(issuer) ou DelegatedTrustRoot.verifySignature()
   
2. Vérification cohérence clé/algo [ligne 35-46]
   ├─ RSA 0x01 → key.algorithm() == "RSA"
   ├─ Ed25519 0x02 → key.algorithm() ∈ {"Ed25519", "EdDSA"}
   └─ SINON: throw ErrorCode.SIGALG_KEY_MISMATCH
   
3. Signature.getInstance(algo) [ligne 50-53]
   └─ "SHA256withRSA" ou "Ed25519"
   
4. sig.verify() [ligne 56]
   └─ RISQUE: Vulnerable aux timing attacks
```

### 2.4 Décodage de Payload
**Fichier:** [TlvCodec.java](TlvCodec.java#L1)

Format TLV (Tag-Length-Value):
```
Structure:
├─ byte: tag (0x00 interdit)
├─ short: length (big-endian, max 65535)
└─ bytes: value
```

**Validations:**
- [TlvCodec.java:35] Tag 0x00 interdit
- [TlvCodec.java:48] Longueur TLV vérifiée avant lecture
- [TlvCodec.java:52] Détection de tags dupliqués
- [TlvCodec.java:65-129] Lecture typée stricte (u8, u16, u32, u64, i64, string, bytes, stringList)

---

## 3. ANALYSE DE LA GESTION D'ERREURS

### 3.1 Codes d'Erreur V4
**Fichier:** [ErrorCode.java](ErrorCode.java#L1-L25)

| Code | Description | Comportement |
|------|-------------|-------------|
| V4001 | INVALID_ENVELOPE | Fail-closed (raise VeridotException) |
| V4002 | UNREGISTERED_ENTRY_TYPE | Fail-closed |
| V4003 | INVALID_IDENTIFIER_LENGTH | Fail-closed |
| V4004 | INVALID_PAYLOAD_LENGTH | Fail-closed |
| V4005 | RESERVED_FLAG_SET | Fail-closed |
| V4006 | INVALID_SCOPE_GRAMMAR | Fail-closed |
| V4007 | MALFORMED_PAYLOAD | Fail-closed |
| V4101 | TRUST_RESOLUTION_FAILED | Fail-closed |
| V4102 | CAPABILITY_NOT_FOUND | Fail-closed (ou cached negative) |
| V4103 | CAPABILITY_EXPIRED | Fail-closed |
| V4104 | DELEGATION_DEPTH_EXCEEDED | Fail-closed (max 10 hops) |
| V4201 | STALE_VERSION | Fail-closed (watermark violation) |
| V4202 | LIVENESS_NOT_ESTABLISHED | Fail-closed (positive proof) |
| V4203 | KEY_EPOCH_EXPIRED | Fail-closed |
| V4204 | SIGALG_KEY_MISMATCH | Fail-closed |
| V4301 | FENCE_TOKEN_STALE | Fail-closed (capacity control) |
| V4302 | CAPACITY_EXCEEDED | Fail-closed ou Eviction selon policy |
| V4401 | TRANSPORT_UNAVAILABLE | Fail-closed (broker unavailable) |

### 3.2 Pattern Fail-Open/Fail-Closed

**Fail-Closed (Sécurisé):**
- [GenericSignerVerifier.java:217-220] Masquerading de token format → BrokerExtractionException
- [EntryVerifier.java:54-56] Récupération KEY_EPOCH → VeridotException si absent
- [LivenessChecker.java:59-62] LIVENESS entry must exist (positive proof)
- [SessionCounter.java:53-63] Exception during listActive → ignore (default-deny)

**Fail-Open (RISQUE):**
- [ConfigResolver.java:34-48] Configuration NULL retourne null → utilise defaultConfig
  ```java
  if (config == null) {
      config = defaultConfig;  // Ligne 135 GenericSignerVerifier
  }
  ```
  **PROBLÈME:** Si aucune config trouvée, applique config par défaut (max=-1, unbounded)

- [CapacityManager.java:20] Config NULL → skip capacity check
  ```java
  if (config == null) {
      return; // unbounded
  }
  ```
  **PROBLÈME:** No capacity enforcement si config absent

- [SessionCounter.java:63-69] Exception handling liberal
  ```java
  } catch (Exception e) {
      // Ignore any invalid or stale liveness entries (fail-closed / default-deny)
  }
  ```
  Commentaire dit "fail-closed" mais absorbe TOUTE exception incluant réseau

### 3.3 Gestion des Erreurs au Niveau Verify

**Fichier:** [GenericSignerVerifier.java:202-268]

```java
} catch (BrokerExtractionException | DataDeserializationException e) {
    throw e;                          // Ligne 264
} catch (Exception e) {
    throw new BrokerExtractionException(
        "Failed to verify token: " + e.getMessage(), e);  // Ligne 266
}
```

**PROBLÈME:**
- Masquage de toute RuntimeException en BrokerExtractionException
- Perte de contexte d'erreur (VeridotException.errorCode perdu)
- Client ne peut pas distinguer: malformed token vs broker failure vs crypto failure

---

## 4. DÉFAUTS ARCHITECTURAUX IDENTIFIÉS

### 4.1 Problème LRU / Éviction
**Fichier:** [EvictionSelector.java](EvictionSelector.java#L20-L32)

```java
case 0x01 -> // FIFO: lowest asOf (first in sorted list)
    sessions.get(0);
case 0x02 -> // LIFO: highest asOf (last in sorted list)
    sessions.get(sessions.size() - 1);
case 0x03 -> // LRU: lowest asOf (first in sorted list)
    sessions.get(0);  // ← IDENTIQUE À FIFO !
case 0x04 -> // REJECT
    throw new VeridotException(ErrorCode.CAPACITY_EXCEEDED, ...);
```

**FAILLE:** LRU et FIFO sont identiques. LRU doit éviter session la plus RÉCEMMENT utilisée, pas la plus ANCIENNE.

**Impact:** Politique d'éviction ne fonctionne pas comme documentée.

**Correction nécessaire:** Implémenter LRU avec tracking de dernier accès.

---

### 4.2 Fuite de Mémoire: GroupLocks
**Fichier:** [GenericSignerVerifier.java:35, 120-121]

```java
private final ConcurrentHashMap<String, Object> groupLocks = new ConcurrentHashMap<>();
...
Object groupLock = groupLocks.computeIfAbsent(groupId, k -> new Object());
synchronized (groupLock) { ... }
```

**PROBLÈME:**
- Locks jamais supprimés après usage
- Après 1 million de groupes distincts → OutOfMemory
- Les verrous restent indéfiniment (pas d'expiration)

**Impact:** DoS par création de groupId unique à chaque signature.

**Correction:** Implémenter LRU ou TTL sur le cache de locks.

---

### 4.3 Race Condition: KeyRotationService + GenericSignerVerifier

**Fichier:** [KeyRotationService.java:56-58] vs [GenericSignerVerifier.java:155]

```
KeyRotationService:
private volatile KeySnapshot currentSnapshot;
public KeySnapshot snapshot() {
    return currentSnapshot;  // Atomique ✓
}

GenericSignerVerifier.sign():
KeyRotationService.KeySnapshot keySnapshot = keyRotationService.snapshot(); // Ligne 155
// 1. currentSnapshot peut rotate entre snapshot() et utilisation
// 2. JWT créé avec clé A, KEY_EPOCH publié avec clé A, mais
//    à la vérification: clé B est en snapshot() (rotée entre-temps)
```

**PROBLÈME:** Entre le moment où on récupère le snapshot et où on l'utilise, les clés peuvent avoir roté (24h config).

**MAIS** Atténuation: KEY_EPOCH contient la clé publique explicitement → vérification utilise la bonne clé.

**Risque résiduel:** Si deux rotations successives surviennent dans un court laps, l'ordre peut être ambigu.

---

### 4.4 Synchronisation Incohérente: Session Lock vs Config Resolution

**Fichier:** [GenericSignerVerifier.java:120-138]

```java
Object groupLock = groupLocks.computeIfAbsent(groupId, k -> new Object());
synchronized (groupLock) {
    ensureReconciliationStarted(scope);
    
    // Resolve Config - NO LOCK
    ConfigPayload config = configResolver.resolve(...);  // Ligne 135
    // ← ConfigResolver fait des appels au broker
    // ← Transactions externes sans protection du verrou de groupe
}
```

**PROBLÈME:** ConfigResolver.resolve accède au broker sans lock. Deux threads peuvent:
1. Thread A: resolve config X
2. Thread B: resolve config Y (stale copy)
3. Thread A: apply config X (capacity M1)
4. Thread B: apply config Y (capacity M2, different!)

**Impact:** Incohérence de la configuration appliquée entre sessions du même groupe.

---

### 4.5 Absence de Validation de Configuration Temporelle

**Fichier:** [ConfigResolver.java:67]

```java
ConfigPayload config = ConfigPayload.decode(envelope.payload);
long now = System.currentTimeMillis();
long validityMs = config.validity().isPresent() ? 
    config.validity().getAsLong() : 360000L * 1000L;  // 360 000 secondes!

if (now >= envelope.timestamp + validityMs) {
    return null; // Config expired
}
```

**PROBLÈME:** Défaut de 360 000 **secondes** = 100 heures! Typo?
- **360000L * 1000L = 3.6e11 ms** (414 jours)
- Devrait probablement être **360 * 1000L** (6 minutes) ou **3600 * 1000L** (1 heure)

**Impact:** Configuration restent valides pendant des mois même si expirant.

---

## 5. PROBLÈMES DE SÉCURITÉ (FAIL-OPEN, VALIDATION MANQUANTE)

### 5.1 Cache Poisoning sur Capabilités
**Fichier:** [CapabilityVerifier.java:39-45]

```java
public void assertAuthorized(String issuer, Scope scope, String siteId, 
                             Broker broker, TrustRoot trustRoot) {
    long now = System.currentTimeMillis();
    String cacheKey = issuer + "\0" + scope.value() + "\0" + (siteId != null ? siteId : "");
    CacheEntry entry = cache.get(cacheKey);
    if (entry != null && now < entry.expiresAt) {
        if (entry.authorized) {
            return;  // ✓ Cache hit, authorized
        } else {
            throw new VeridotException(...);  // ✗ Cached denial
        }
    }
    
    try {
        checkCapabilityChain(...);
        cache.put(cacheKey, new CacheEntry(true, now + TTL));
    } catch (VeridotException e) {
        cache.put(cacheKey, new CacheEntry(false, now + NEG_TTL));  // Negative cache
        throw e;
    }
}
```

**PROBLÈME:**
- TTL: 60 secondes (capability cache) [Config.java:176]
- Negative TTL: 5 secondes [Config.java:180]
- Entre deux vérifications, capability peut être révoquée mais cache accepte old entry
- Negative cache courte (5s) mais positive cache longue (60s) → asymétrie

**Impact:** Acceptance d'une capabilité révoquée pendant jusqu'à 60 secondes après révocation.

**Absence de mécanisme de revocation:** Aucun appel `invalidateAuthorization()` lors de revocation.

---

### 5.2 Validation Laxe d'Identifiants
**Fichier:** [Protocol.java:293-301]

```java
private static final Pattern IDENTIFIER_PATTERN = 
    Pattern.compile("^[^:,|\\s]{1,125}$");

public static void validateIdentifier(String id, String fieldName) {
    if (id == null || !IDENTIFIER_PATTERN.matcher(id).matches()) {
        throw new IllegalArgumentException(
            fieldName + " must be 1-125 printable characters excluding :,| and whitespace, got: " + id);
    }
    if (id.startsWith("__") && id.endsWith("__")) {
        throw new IllegalArgumentException(
            fieldName + " must not use the reserved namespace pattern __...__: " + id);
    }
}
```

**PROBLÈME:**
- Pattern `[^:,|\\s]` accepte TOUS caractères sauf `:,| \t\n\r` etc.
- N'interdit pas: caractères de contrôle Unicode (ZWJ, RLO, LRM, etc.)
- N'interdit pas: homoglyphes (latin a vs cyrillic а)
- N'interdit pas: caractères de largeur zéro
- Permet: surrogates UTF-16 mal formés

**Attaque:**
```
groupId = "user\u200B\u200Cadmin"  // Zero-width characters
sequenceId = "session\uFEFF"        # Byte Order Mark
```

Ces passent la validation regex mais peuvent confondre la logique.

**Correction:** Utiliser Character.isIdentifierIgnorable() ou white-list ASCII printable.

---

### 5.3 Pas de Protection Timing Attack sur Signature
**Fichier:** [SignatureVerifier.java:56]

```java
if (!sig.verify(envelope.signature)) {
    throw new VeridotException(
        ErrorCode.TRUST_RESOLUTION_FAILED, 
        entryId.loggable(), 
        "Cryptographic signature verification failed");
}
```

**PROBLÈME:**
- Java Signature.verify() utilise vérification byte-by-byte standard (non-constant-time)
- Attaquant peut mesurer le temps pour déduire bits de signature corrects
- Applicable si attaquant contrôle le token + peut mesurer latence réseau/timing

**Impact:** Faible (nécessite contrôle token ET mesure timing précise), mais présent.

**Correction:** Utiliser MessageDigest.isEqual() ou java.security.SecureComparison (Java 6+).

---

### 5.4 Absence de Rate Limiting sur Verification

**Fichier:** [GenericSignerVerifier.java:202-268]

```java
@Override
public <T> VerifiedData<T> verify(String token, Function<String, T> deserializer) 
        throws BrokerExtractionException {
    // Aucun rate limiting
    // Aucun throttling
    // Aucun account lockout
    
    // Attaquant peut faire 1M appels/sec avec tokens invalides
}
```

**RISQUE:** Brute-force sur tokens, ou DoS par vérification coûteuse.

**Aucune protection par:**
- Rate limiting
- Token bucket
- Exponential backoff
- Per-group quotas

---

### 5.5 Token Substitution: DIRECT vs INDIRECT Mode

**Fichier:** [GenericSignerVerifier.java:181-184, 217-220]

```
DIRECT Mode:
├─ Return JWT (full token)
├─ GenericSignerVerifier.sign() → JWT
└─ GenericSignerVerifier.verify(jwt) → ✓

INDIRECT Mode:
├─ Return messageId (key to fetch)
├─ GenericSignerVerifier.sign() → messageId
└─ GenericSignerVerifier.verify(messageId) →
   └─ Fetch KEY_EPOCH from broker
   └─ Extract JWT from payload [GenericSignerVerifier.java:240]

PROBLÈME: Token Substitution
├─ Attacker controls broker
├─ Sign with DIRECT mode → get JWT1
├─ Fetch KEY_EPOCH from broker for messageId
├─ Replace JWT in KEY_EPOCH.token field with FAKE JWT2
└─ Verify(messageId) → loads FAKE JWT2 from broker
    └─ Signature checks out (uses ephemeral key from KEY_EPOCH)
    └─ Payload can be anything attacker wants
```

**Atténuation:** KEY_EPOCH est signé par long-term key, donc attacker ne peut pas create fake KEY_EPOCH.

**MAIS:** Si attacker compromises long-term key, full compromise.

---

### 5.6 Pas de Vérification d'Expiration FENCE Token
**Fichier:** [CapacityManager.java:29]

```java
// Set fence validity duration to 5 minutes
long fenceValidUntil = now + 300000;
FenceManager.FenceGrant grant;
try {
    grant = fenceManager.acquire(..., fenceValidUntil, ...);
} catch ...

// MAIS: aucune vérification que fenceGrantValidUntil > now+TTL
// Si FENCE expires avant capacity check complète, not validated
```

**PROBLÈME:** FENCE grant retourné avec expiration, mais:
- [CapacityManager.java:71-91] Capacity enforcement ne valide pas expirationis
- [FenceManager.java:49] assertFenceValid() vérifie version, pas expiration
- Attacker peut use stale FENCE grant after expiration

**Correction:** Ajouter validation `if (grant.validUntil() < now) throw ERROR`

---

### 5.7 Absence de Nonce/Anti-Replay Protection
**Fichier:** Aucun dans le flux verify()

```java
// Protocol V3 message format:
// "3:<groupId>:<sequenceId>|alg:...|ts:...| → Timestamp présent
// MAIS: aucune validation de freshness au niveau du verifier

// Le timestamp est dans la configuration, pas dans le token JWT
// Risque: Le même JWT peut être rejoued indéfiniment
```

**PROBLÈME:**
- JWT signé contient `exp` claim → valide jusqu'à expiration
- Aucun nonce ou timestamp vérifié au moment du verify()
- Attacker peut rejouer le même JWT 1000x après signature
- Protocol V3 a `ts` mais n'est pas utilisé dans V4

**Impact:** Replay attacks possible.

**Correction:** Ajouter nonce ou timestamp check avec TTL court.

---

### 5.8 Scope Confusion Possible
**Fichier:** [Scope.java:30-60]

```java
public record Scope(String value) {
    public static Scope group(String groupId) {
        return new Scope("group:" + groupId);
    }
    public static Scope site(String siteId) {
        return new Scope("site:" + siteId);
    }
    public static Scope global() {
        return new Scope("global");
    }
    
    public String groupId() {
        if (!isGroup()) throw new IllegalStateException("...");
        return value.substring(6);  // String slicing!
    }
}
```

**PROBLÈME:** Pas de validation que `groupId` ne contient pas `:` après `group:` prefix
- groupId = "site:admin" → Scope.group("site:admin") → value = "group:site:admin"
- Parsing ambigu: "group:site:admin" peut être interprété comme:
  - group "site:admin" ✓
  - group "site" + site "admin" (ambiguous parsing)

**Impact:** Possible scope confusion attacks si parser defects exist.

**Correction:** Valider groupId/siteId ne contiennent pas `:`.

---

## 6. PATTERNS DE CONCURRENCE

### 6.1 Problème: GroupLocks dans sign()
**Fichier:** [GenericSignerVerifier.java:120]

```java
Object groupLock = groupLocks.computeIfAbsent(groupId, k -> new Object());
synchronized (groupLock) {
    // Vérouille seulement ce groupe
    // Autres groupes peuvent signer en parallèle ✓
}
```

**Bonne conception:** Per-group locking.

**Mauvaise conception:** Pas de nettoyage → memory leak (voir section 4.2).

---

### 6.2 VersionWatermark Thread-Safety
**Fichier:** [VersionWatermark.java:28-38]

```java
private final ConcurrentHashMap<String, Long> watermarks = new ConcurrentHashMap<>();

public void accept(EntryId entryId, long version) {
    String key = toMapKey(entryId);
    watermarks.compute(key, (k, current) -> {
        long currentVal = current == null ? 0L : current;
        if (version <= currentVal) {
            throw new VeridotException(..., "not strictly greater than watermark");
        }
        return version;
    });
}
```

**Bon:** Atomic compare-and-set avec `.compute()`.

**Mais:** Exception lancée DANS la lambda n'annule pas l'update. Vérifier l'implémentation JDK.

---

### 6.3 LivenessManager Renewal Loops
**Fichier:** [LivenessManager.java:42-60]

```java
private final Map<EntryId, ScheduledFuture<?>> renewalTasks = new ConcurrentHashMap<>();

public void startRenewalLoop(EntryId liveEntryId, long renewalWindowMillis, 
                             VersionWatermark watermark, 
                             ScheduledExecutorService scheduler) {
    stopRenewalLoop(liveEntryId);
    
    long delay = (long) (renewalWindowMillis * 0.8);
    ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
        try {
            publishActive(liveEntryId, renewalWindowMillis, watermark);
        } catch (Exception e) {
            // Log and ignore to allow subsequent renewal retries
        }
    }, delay, delay, TimeUnit.MILLISECONDS);
    
    renewalTasks.put(liveEntryId, future);
}
```

**Bon:** ConcurrentHashMap pour thread-safe tasks.

**Problème:** 
- Si `publishActive()` lance exception → continue trying (exponential backoff absent)
- Si broker unavailable, renews fail silently jusqu'à expiration LIVENESS
- Session expires prématurément si renouvellement échoue

**Risque:** Revocation silencieuse sans notification client.

---

### 6.4 ReconciliationManager Periodic Tasks
**Fichier:** [ReconciliationManager.java:75-92]

```java
ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
    try {
        reconcile(scope, broker, watermark, ...);
    } catch (Exception e) {
        // Log and ignore to allow subsequent reconciliation runs
    }
}, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
```

**Bonne conception:** Fixed delay entre exécutions (robuste aux appels longs).

**Mais:** Exception silencieuse → watermark peut diverger indéfiniment.

---

## 7. CONTRÔLES DE SÉCURITÉ PRÉSENTS

### 7.1 Positive Proof (Default-Deny)
✅ **Liveness Checking:** [LivenessChecker.java:54-62]
- LIVENESS entry MUST exist (absent → fail-closed)
- Status MUST be ACTIVE (REVOKED → fail-closed)
- validUntil MUST be in future (expired → fail-closed)

✅ **Watermark Monotonicity:** [EntryVerifier.java:81-86]
- Version must strictly increase
- Prevents replay of old envelopes

✅ **Capability Chain:** [CapabilityVerifier.java:58-98]
- Delegation depth limited to 10 hops
- Each capability must be signed by issuer
- Scope patterns must cover target scope

### 7.2 Temporal Validation
✅ **Clock Skew Tolerance:** [EntryVerifier.java:88-95]
- validFrom check: now >= validFrom - 300000 (5 min drift backward)
- validUntil check: now < validUntil (no forward drift)
- Asymmetric: penalizes future-dated tokens

### 7.3 Structural Validation
✅ **Envelope Parsing:** [Envelope.java:50-180]
- 9-step validation process [§3.1 of report]
- Length bounds checking
- Magic bytes verification
- Flag coherence (COMPACT_SIG vs sigAlg)
- Trailing byte detection

✅ **TLV Codec:** [TlvCodec.java:40-66]
- Tag 0x00 forbidden
- Length bounds verification before read
- Duplicate tag detection
- Type-safe readers (readU8, readU64, etc.)

### 7.4 Identifier Validation
✅ **Syntax Checking:** [Protocol.java:293-301]
- 1-125 character length limit
- Forbidden characters: `:,| \t\n\r`
- Reserved pattern: `__...__`

⚠️ **BUT:** Unicode issues remain (see section 5.2)

### 7.5 Trust Verification
✅ **Long-Term Key Validation:** [SignatureVerifier.java:21-60]
- TrustRoot resolution (PublicKeyTrustRoot or DelegatedTrustRoot)
- Key algorithm coherence checking
- Cryptographic signature verification

✅ **Certificate Chain:** [CapabilityVerifier.java:80-98]
- Recursive delegation chain verification
- Depth limits
- Expiration checks

### 7.6 Revocation Support
✅ **Session Revocation:** [GenericSignerVerifier.java:273-303]
- LIVENESS(REVOKED) publication
- Renewal loop termination

⚠️ **BUT:** No immediate invalidation of cached entries

---

## 8. FAILLES DE SÉCURITÉ ET RECOMMANDATIONS

### CRITIQUE - Corriger Immédiatement

#### 1. LRU Policy Broken [EvictionSelector.java:19-32]
```
Current: LRU returns sessions.get(0) (same as FIFO)
Correct: LRU should return oldest by access time
```
- **Risk:** Session never evicted under LRU, leading to capacity violations
- **Fix:** Implement lastAccessTime tracking, evict minimum

#### 2. Memory Leak: GroupLocks [GenericSignerVerifier.java:35, 120]
```
Current: groupLocks never cleaned
Correct: Implement LRU or TTL eviction
```
- **Risk:** DoS via OOM after 1M unique groupIds
- **Fix:** 
  ```java
  // Option A: Cleanup on close()
  public void close() {
      groupLocks.clear();
      ...
  }
  
  // Option B: Bounded cache
  LinkedHashMap<String, Object> groupLocks = 
      new LinkedHashMap<String, Object>(16, 0.75f, true) {
          protected boolean removeEldestEntry(Map.Entry eldest) {
              return size() > 10000;  // Max 10K locks
          }
      };
  ```

#### 3. Config Temporal Validation Typo [ConfigResolver.java:67]
```
Current: 360000L * 1000L = 3.6e11 ms (414 days!)
Correct: Probably 360 * 1000 (6 min) or 3600 * 1000 (1 hour)
```
- **Risk:** Stale configs accepted for months
- **Fix:** 
  ```java
  long validityMs = config.validity().isPresent() ? 
      config.validity().getAsLong() : 
      3600 * 1000L;  // 1 hour default
  ```

### HAUTE - Corriger dans Prochaine Version

#### 4. Fail-Open on Missing Config [GenericSignerVerifier.java:134-136]
```
Current: if (config == null) { config = defaultConfig; }
Risk: Unbounded capacity if config missing
```
- **Fix:** Make config required or use conservative defaults
  ```java
  if (config == null) {
      throw new BrokerExtractionException(
          "Required configuration not found. Ensure CONFIG entry published for scope.");
  }
  ```

#### 5. Cache Poisoning: Capabilities [CapabilityVerifier.java:39-45]
```
Current: 60s positive cache, 5s negative cache → asymmetry
Risk: Revoked capability accepted up to 60s
```
- **Fix:** Balance TTLs, implement revocation invalidation
  ```java
  public void invalidateAuthorization(String issuer, Scope scope) {
      cache.keySet().removeIf(key -> key.startsWith(issuer + "\0"));
  }
  // Call on revocation or config update
  ```

#### 6. Missing FENCE Expiration Validation [CapacityManager.java:29]
```
Current: FENCE grant obtained but not validated at usage time
Risk: Stale FENCE grants accepted
```
- **Fix:**
  ```java
  if (grant.validUntil() < now) {
      throw new VeridotException(ErrorCode.FENCE_TOKEN_STALE, ...);
  }
  ```

#### 7. Weak Identifier Validation [Protocol.java:293-301]
```
Current: Regex [^:,|\\s]{1,125} allows Unicode/homoglyphs
Risk: Confusable identifiers, scope confusion
```
- **Fix:** Whitelist ASCII alphanumeric + restricted special chars
  ```java
  Pattern IDENTIFIER_PATTERN = Pattern.compile(
      "^[a-zA-Z0-9._-]+$");  // Only safe chars
  ```

#### 8. No Rate Limiting on verify() [GenericSignerVerifier.java:202]
```
Current: No protection against brute-force
Risk: 1M verify attempts/sec, no throttle
```
- **Fix:** Implement per-group quota
  ```java
  private final RateLimiter verifyLimiter = 
      RateLimiter.create(1000.0);  // 1000 req/sec
  
  public <T> VerifiedData<T> verify(...) {
      if (!verifyLimiter.tryAcquire()) {
          throw new BrokerExtractionException("Rate limit exceeded");
      }
      ...
  }
  ```

#### 9. No Nonce/Replay Protection [GenericSignerVerifier.java:202-268]
```
Current: Same JWT can be replayed indefinitely within TTL
Risk: Business logic assumes each verify() is single use
```
- **Fix:** Implement nonce tracking or one-time-use tokens
  ```java
  private final Set<String> usedNonces = Collections.newSetFromMap(
      new ConcurrentHashMap<>());
  
  if (!usedNonces.add(jwtNonce)) {
      throw new BrokerExtractionException("Token already used (replay attack)");
  }
  // Cleanup expired nonces periodically
  ```

#### 10. Timing Attack on Signature [SignatureVerifier.java:56]
```
Current: sig.verify() not constant-time
Risk: Timing side-channel on signature bits
```
- **Fix:** Use constant-time comparison
  ```java
  if (!MessageDigest.isEqual(sig.sign(), envelope.signature)) {
      throw new VeridotException(...);
  }
  ```

### MOYEN - Considérer pour Défense-en-Profondeur

#### 11. Sync Inconsistency: Config during sign() [GenericSignerVerifier.java:120-138]
```
Current: ConfigResolver.resolve() called outside critical section
Risk: Two threads apply different configs to same group
```
- **Fix:** Move config resolution inside synchronized block
  ```java
  synchronized (groupLock) {
      ensureReconciliationStarted(scope);
      ConfigPayload config = configResolver.resolve(...);  // Inside
      ...
  }
  ```

#### 12. Scope Confusion: `:` in identifiers [Scope.java:30-60]
```
Current: No validation that groupId != "group:xxx"
Risk: Ambiguous scope parsing
```
- **Fix:**
  ```java
  public static Scope group(String groupId) {
      if (groupId.contains(":")) {
          throw new VeridotException(..., "groupId cannot contain ':'");
      }
      return new Scope("group:" + groupId);
  }
  ```

---

## 9. LISTE DE VÉRIFICATION DE SÉCURITÉ

### Avant Production
- [ ] Fix LRU eviction policy (section 8, #1)
- [ ] Implement groupLocks cleanup (section 8, #2)
- [ ] Fix config validity typo (section 8, #3)
- [ ] Make configuration required (section 8, #4)
- [ ] Balance capability cache TTLs (section 8, #5)
- [ ] Add FENCE expiration validation (section 8, #6)
- [ ] Whitelist identifier characters (section 8, #7)
- [ ] Implement rate limiting on verify() (section 8, #8)
- [ ] Add replay protection (section 8, #9)
- [ ] Use constant-time signature comparison (section 8, #10)

### Tests Recommandés
- [ ] Eviction policy correctness (FIFO, LIFO, LRU, REJECT)
- [ ] Memory leak test: 10M unique groupIds
- [ ] Config resolution under concurrent sign()
- [ ] Capability cache coherency after revocation
- [ ] Token replay detection
- [ ] Timing attack resistance
- [ ] Rate limiting effectiveness
- [ ] Scope parsing with colons/ambiguous strings
- [ ] Identifier unicode/homoglyph rejection
- [ ] FENCE expiration boundaries

---

## 10. RÉSUMÉ EXÉCUTIF

### Points Forts
1. ✅ **Positive-Proof Liveness:** Default-deny architecture with explicit LIVENESS(ACTIVE)
2. ✅ **Watermark Monotonicity:** Prevents replay of old versions
3. ✅ **Multi-Level Scope Hierarchy:** Flexible capability delegation (group/site/global)
4. ✅ **Binary Envelope Format:** Efficient, structured, validated
5. ✅ **Key Rotation:** Ephemeral keys rotated every 24h for forward secrecy
6. ✅ **Atomic Snapshots:** KeyRotationService prevents key mixing

### Points Faibles
1. ❌ **LRU Policy Broken:** Same as FIFO (HIGH RISK)
2. ❌ **Memory Leak:** GroupLocks never cleaned (HIGH RISK)
3. ❌ **Config Typo:** Validity 414 days instead of hours (HIGH RISK)
4. ❌ **Fail-Open on Config:** Unbounded capacity if config missing (HIGH RISK)
5. ⚠️ **Cache Poisoning:** 60s TTL on revoked capabilities (MEDIUM RISK)
6. ⚠️ **Weak Identifiers:** Unicode/homoglyphs not rejected (MEDIUM RISK)
7. ⚠️ **No Rate Limiting:** Brute-force friendly (MEDIUM RISK)
8. ⚠️ **Timing Attacks:** Signature comparison not constant-time (LOW RISK)

### Recommendation
**ADEQUATE for non-critical applications with fixes to sections 8 #1-3.**

**NOT READY for HIGH-SECURITY use cases (payment, authentication) until:**
- All CRITICAL items addressed
- Full rate limiting implemented
- Replay protection added
- Timing attack mitigated

---

## ANNEXE: Mapping Fichiers ↔ Vulnérabilités

| Fichier | Lignes | Problème | Sévérité |
|---------|--------|---------|----------|
| [EvictionSelector.java](EvictionSelector.java) | 19-32 | LRU == FIFO | CRITIQUE |
| [GenericSignerVerifier.java](GenericSignerVerifier.java) | 35, 120 | GroupLocks leak | CRITIQUE |
| [ConfigResolver.java](ConfigResolver.java) | 67 | Typo validité | CRITIQUE |
| [GenericSignerVerifier.java](GenericSignerVerifier.java) | 134-136 | Fail-open config | HAUTE |
| [CapabilityVerifier.java](CapabilityVerifier.java) | 39-45 | Cache TTL asymétrie | HAUTE |
| [CapacityManager.java](CapacityManager.java) | 29, 71 | FENCE expiration | HAUTE |
| [Protocol.java](Protocol.java) | 293-301 | Identifiants faibles | HAUTE |
| [GenericSignerVerifier.java](GenericSignerVerifier.java) | 202 | Pas rate limit | HAUTE |
| [GenericSignerVerifier.java](GenericSignerVerifier.java) | 202-268 | Pas replay protection | HAUTE |
| [SignatureVerifier.java](SignatureVerifier.java) | 56 | Timing attack | MOYEN |
| [GenericSignerVerifier.java](GenericSignerVerifier.java) | 120-138 | Sync race | MOYEN |
| [Scope.java](Scope.java) | 30-60 | Scope confusion | MOYEN |

---

**FIN DU RAPPORT**
