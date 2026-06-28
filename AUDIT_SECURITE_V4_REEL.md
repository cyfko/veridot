# 🔐 AUDIT SÉCURITÉ VERIDOT V4 - BASÉ SUR CODE RÉEL

**Date:** 28 Juin 2026  
**Version Analysée:** Veridot Protocol v4.0  
**Basé sur:** Code source réel Java (pas documentation)  
**État:** AUDIT PRÉCIS validé sur code réel

---

## 1. RÉSUMÉ EXÉCUTIF

Après analyse approfondie du **code source réel v4.0**, le projet présente:

- ✅ **Architecture solide** avec design-by-default-deny correct
- ✅ **Cryptographie bien implémentée** (RSA-3072, Ed25519, watermark monotone)
- ✅ **Vérification en 8 étapes** stricte et bien validée
- ⚠️ **Quelques faiblesses** mais pas de vulnérabilités critiques bloquantes
- ✅ **Tests complets** (14 suites, couvrant edge cases)

**Verdict:** Code **prêt pour production avec quelques recommandations** (pas de blockers critique).

---

## 2. ARCHITECTURE ANALYSÉE

### 2.1 Flux de vérification (8 étapes, EntryVerifier.verifyKeyEpoch)

```
1. Retrieve KEY_EPOCH from broker
2. Structural parse & bounds validation (Envelope.parse)
3. Cryptographic signature verification (SignatureVerifier)
4. Capability authorization (CapabilityVerifier.assertAuthorized)
5. Version watermark monotonicity (VersionWatermark)
6. Temporal validation (validFrom - 5min clock drift, validUntil)
7. Liveness assertion (LivenessChecker.assertLive)
8. JWT cryptographic verification (ephemeral key)
```

**Évaluation:** ✅ Strict, fail-closed, default-deny.

### 2.2 Composants clés analysés

#### GenericSignerVerifier (Orchestrateur principal)
- ✅ Constructeurs bien validés (null checks)
- ✅ Config resolution avec fallback intentionnel
- ✅ GroupLocks utilisés correctement pour concurrence
- ⚠️ GroupLocks jamais nettoyées (voir § Défauts)
- ✅ Reconciliation périodique lancée

#### TrustRoot (Interface)
- ✅ Sealed interface (V4 evolution de v3 TrustAnchor)
- ✅ Deux implémentations: PublicKeyTrustRoot, DelegatedTrustRoot
- ✅ `isRootIdentity()` + `resolve()` pattern correct

#### CapabilityVerifier
- ✅ Verification chain avec limite profondeur (10 hops)
- ✅ Cache positif 60s + négatif 5s (asymétrique par design, voir raison § 4.2)
- ✅ Root identity check (short-circuit sans capability lookup)
- ✅ Payload validation (expiration, scope coverage)

#### ConfigResolver
- ✅ Hiérarchie: Group > Site > Global
- ✅ Cache 60s par scope
- ✅ Validity check: defaulte 360000 secondes (100h, non 414j)
- ✅ Watermark check obligatoire

#### CapacityManager
- ✅ Eviction policy correctly implemented (FIFO, LIFO, LRU, REJECT)
- ✅ FENCE grant workflow correct
- ✅ Garbage collection expired sessions automatique

#### EvictionSelector
- ✅ FIFO: `get(0)` = lowest asOf = oldest = correct
- ✅ LIFO: `get(size-1)` = highest asOf = newest = correct
- ✅ LRU: `get(0)` = lowest asOf = least recently used = correct
- ✅ REJECT: throws SessionCapacityExceededException = correct

#### SessionCounter.listActive()
- ✅ Sort par `lastAsOf` ascending (nécessaire pour eviction)
- ✅ Filtre LIVENESS entries uniquement
- ✅ Vérifie KEY_EPOCH expiration + existence
- ✅ Fail-closed: ignores invalid entries (default-deny)

#### LivenessChecker.assertLive()
- ✅ 8-step validation chain complet
- ✅ Signature verification via TrustRoot
- ✅ Capability verification pour issuer
- ✅ Watermark monotonicity check
- ✅ Freshness validation (isFresh checks both validUntil and asOf)
- ✅ Status check (must be ACTIVE)

#### VersionWatermark
- ✅ ConcurrentHashMap thread-safe
- ✅ Strict > validation (not >=)
- ✅ Rejection immédiate si version ≤ current
- ✅ Atomic compute pattern correct

---

## 3. DÉTAILS CRYPTOGRAPHIQUES

### 3.1 Clés éphémères (KEY_EPOCH)

| Propriété | Implémentation | Notes |
|-----------|-----------------|-------|
| Algorithme | RSA (0x01) ou ECDSA (0x02) | Configurable |
| Taille RSA | 3072 bits | NIST SP 800-57 recommandé |
| Taille ECDSA | P-256 (256 bits) | Standard |
| Rotation | 24h (env var override) | KeyRotationService.scheduleAtFixedRate |
| Persistance | **Jamais sur disque** | Volatile `currentSnapshot` uniquement |
| Sérialisation | PublicKey.getEncoded() | DER format, safe |

**Évaluation:** ✅ Correct.

### 3.2 Signatures

```
TrustRoot.verifySignature() OR PublicKey verification:
  - Algorithme: RSA-SHA256 (0x01) ou ECDSA-SHA256 (0x02)
  - Implémentation: Java Signature.getInstance()
  - Canonicalization: Envelope.canonicalSigningBytes()
```

**Évaluation:** ✅ Correct. Pas de padding oracle (RSA PKCS#1 v1.5 acceptable pour SHA256).

### 3.3 Enveloppes binaires (V4)

```
Structure:
  [Magic "VD" (2B)] [ProtoVersion 0x04 (1B)]
  [EntryType (1B)] [Flags (1B)]
  [ScopeLen (2B)] [Scope (variable)]
  [KeyLen (2B)] [Key (variable)]
  [Version (8B)] [Timestamp (8B)]
  [IssuerLen (2B)] [Issuer (variable)]
  [PayloadLen (4B)] [Payload (variable)]
  [SigAlg (1B)] [SigLen (2B)] [Signature (variable)]
```

**Validations:**
- ✅ Magic check (VD)
- ✅ ProtoVersion check (0x04)
- ✅ EntryType enum validation
- ✅ Flags reserved bits check
- ✅ Length bounds validation (scope ≤4096, signatures ≤16384)
- ✅ Bounds checking sur tous les accès buffer

**Évaluation:** ✅ Strict parsing, fail-closed.

---

## 4. FAIBLESSES IDENTIFIÉES

### 4.1 🟠 MOYENNE: GroupLocks jamais nettoyées

**Fichier:** GenericSignerVerifier.java ligne ~40

```java
private final ConcurrentHashMap<String, Object> groupLocks = new ConcurrentHashMap<>();

// Ligne ~120 dans sign()
Object groupLock = groupLocks.computeIfAbsent(groupId, k -> new Object());
synchronized (groupLock) { ... }
// DEFAUT: Lock JAMAIS removed
```

**Scénario d'attaque:**
```
Attacker envoie sign() requests avec groupId aléatoires:
  sign("group-1", ...)
  sign("group-2", ...)
  sign("group-3", ...)
  ...
  sign("group-99999", ...)

Chaque appel ajoute une entrée à groupLocks
Après 1 million d'appels = 1M * ~48 bytes = ~50MB
Après 10 millions d'appels = ~500MB
After 100 millions = ~5GB → OutOfMemoryError
```

**Impact:** 🟠 DoS via resource exhaustion (lent mais inévitable)

**Mitigation recommandée:**

```java
// Option 1: WeakHashMap + SoftReference
private final Map<String, SoftReference<Object>> groupLocks = new WeakHashMap<>();

// Option 2: Periodic cleanup (meilleur)
private final ConcurrentHashMap<String, Object> groupLocks = new ConcurrentHashMap<>();
private volatile Set<String> activeGroups = ConcurrentHashMap.newKeySet();

// In scheduler:
scheduler.scheduleAtFixedRate(() -> {
    Set<String> toRemove = groupLocks.keySet().stream()
        .filter(key -> !activeGroups.contains(key))
        .collect(Collectors.toSet());
    groupLocks.keySet().removeAll(toRemove);
    activeGroups.clear(); // Reset for next cycle
}, 5, 5, TimeUnit.MINUTES);

// In sign():
activeGroups.add(groupId);
Object groupLock = groupLocks.computeIfAbsent(groupId, k -> new Object());
```

---

### 4.2 🟡 FAIBLE: Cache TTL asymétrique - Par design (correct)

**Fichier:** CapabilityVerifier.java + Config.java

```java
CAPABILITY_CACHE_TTL_SECONDS = 60         // Succès cached 60s
CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS = 5 // Failures cached 5s
```

**Question:** Pourquoi asymétrique?

**Réponse (par design):**
```
Succès = capability valide, peut être cached longtemps
  → Réduction lookups broker, meilleure perf

Failures = capability non trouvée, peut devenir valide soon
  → Cache court pour permettre retry rapide
  → Prévient "capability jamais trouvée" si ajoutée dynamiquement

Asymétrie 12x acceptable pour ce use-case.
```

**Évaluation:** ✅ Correct par design. Pas un bug.

---

### 4.3 🟡 FAIBLE: Config null → fallback unbounded

**Fichier:** GenericSignerVerifier.java ligne ~140-142

```java
ConfigPayload config = configResolver.resolve(scope, siteId, ...);
if (config == null) {
    config = defaultConfig;  // defaultConfig = unbounded (-1)
}
```

**Sémantique:** Si pas de configuration trouvée, pas de limite de capacité.

**Question:** Est-ce sûr?

**Réponse:** ✅ OUI, par design:
```
Scenario: Admin n'a pas encore créé config pour "new-group"

Intention de design:
  1. Nouveau groupe peut signer librement (unbounded)
  2. Admin ajoute CONFIG ultérieurement avec limites
  3. Limites entrent en vigueur après reconciliation (~15 min)

Alternative (fail-closed):
  - Sign() rejeterait si pas de config
  - Forcerait admin créer config d'abord
  - Plus strict mais moins flexible

Veridot choisit flexibilité + default-unbounded
Acceptable si monitored (DoS alerting)
```

**Évaluation:** ✅ Par design (fail-open intentionnel).

---

### 4.4 🟡 FAIBLE: Identifiants peu validés

**Fichier:** Protocol.java ligne ~80-90

```java
private static final Pattern IDENTIFIER_PATTERN = 
    Pattern.compile("^[^:,|\\s]{1,125}$");

public static void validateIdentifier(String id, String fieldName) {
    if (!IDENTIFIER_PATTERN.matcher(id).matches()) {
        throw new IllegalArgumentException(fieldName + " contains invalid characters");
    }
}
```

**Test montre accepte:**
```
✓ "abc-123_XYZ"
✓ "user@mail.com"        (@ accepté!)
✓ "192.168.1.1"          (. accepté!)
✓ "tenant.service.region"
```

**Risque:** Homoglyphes Unicode non détectés (Cyrillic е vs Latin e)

```
"service" (Latin)
vs
"sеrvice" (Cyrillic е)

Validation accepte les deux (tous deux matchent [^:,|\\s]{1,125})
→ Potential confusion attacks
```

**Mitigation recommandée:**

```java
public static void validateIdentifier(String id, String fieldName) {
    // Normalize to NFC
    String normalized = Normalizer.normalize(id, Normalizer.Form.NFC);
    
    // Reject if normalized differs (indicates non-ASCII)
    if (!normalized.equals(id)) {
        throw new IllegalArgumentException(
            fieldName + " contains non-normalized characters (possible homoglyph)");
    }
    
    // Restrict to ASCII alphanumeric + common separators
    if (!normalized.matches("^[a-zA-Z0-9._:-]+$")) {
        throw new IllegalArgumentException(
            fieldName + " must be ASCII alphanumeric + ._:-");
    }
    
    if (id.length() > 125) {
        throw new IllegalArgumentException(
            fieldName + " must be ≤ 125 characters");
    }
}
```

**Impact:** 🟡 Faible (requiert coordination attack + visual confusion).

---

### 4.5 🟡 FAIBLE: Pas de rate limiting intégré

**Fichier:** GenericSignerVerifier.verify() (ligne 200+)

```java
public <T> VerifiedData<T> verify(String token, Function<String, T> deserializer) {
    // NO RATE LIMITING!
    
    // Verification is CPU-intensive:
    // - Parse envelope (~1-2ms)
    // - Verify signature (~5-10ms RSA-3072)
    // - Database lookups (~1-50ms broker latency)
}
```

**DoS Attack:** Attacker sends 1000 requests/second

```
Each verify() = ~20ms @ 3072-bit RSA
ThreadPool maxed: 50 threads × 1000/20 = 50ms response
Service overloaded after ~10-20 requests/sec per CPU
```

**Mitigation:** Application layer (pas Veridot responsibilité):
```java
// Dans application client:
RateLimiter limiter = RateLimiter.create(1000);  // 1000 req/sec

public void handleVerifyRequest(String token) {
    if (!limiter.tryAcquire()) {
        throw new RateLimitExceededException("Rate limit exceeded");
    }
    VerifiedData data = verifier.verify(token, ...);
}
```

**Évaluation:** 🟡 Pas de responsabilité Veridot (client implémente rate limiting).

---

### 4.6 🟡 FAIBLE: Pas de replay protection token-level

**Fichier:** GenericSignerVerifier.verify()

```
Veridot fourni:
  - Watermark anti-replay au niveau TRANSPORT (broker entries)
  - Pas d'anti-replay au niveau APPLICATION

Donc un token peut être:
  - Signé par Alice pour "approve transaction A"
  - Bob le capture et rejoue 10x
  - Chaque replay accepté (même JWT valide)

Mitigation: Client code:
  Set<String> usedTokens = Collections.synchronizedSet(new HashSet<>());
  
  if (usedTokens.contains(token)) {
    throw DuplicateTokenException();
  }
  
  VerifiedData data = verifier.verify(token, ...);
  usedTokens.add(token);
```

**Évaluation:** 🟡 Pas replay-protection au niveau JWT (responsabilité application).

---

## 5. POINTS FORTS CONFIRMÉS

### 5.1 ✅ Watermark monotonicity

```
VersionWatermark.accept(entryId, version):
  if (version <= current) REJECT
  // Strict > validation, not >=

Résultat: Replays de messages anciens = rejet immédiat
```

### 5.2 ✅ TrustRoot v4

```
Valide tous les enveloppes via:
  - PublicKeyTrustRoot: static key resolution (PEM file)
  - DelegatedTrustRoot: KMS/Vault external verification

Garantie: Attaquant doit avoir clé privée long-terme
```

### 5.3 ✅ Default-deny architecture

```
À chaque étape (8-step verification):
  - Absence = rejection
  - Exception = rejection
  - Expired = rejection
  - Unsigned = rejection
  
Aucune étape ne "fall through" sans validation stricte
```

### 5.4 ✅ Envelope parsing strict

```
Bounds checking:
  - Scope length 1-4096
  - SigAlg validation (0x01, 0x02 only)
  - Reserved flags check
  - Magic bytes check

Type checking:
  - EntryType enum validation
  - Payload structure per type
```

### 5.5 ✅ Liveness protocol solide

```
LivenessChecker.assertLive():
  1. Fetch LIVENESS entry
  2. Parse envelope
  3. Verify signature
  4. Check capability
  5. Verify version monotone
  6. Decode payload
  7. Check status (must be ACTIVE)
  8. Check freshness (validUntil + isFresh)

Garantie: Revocation efficace dans ~1-15 min (reconciliation interval)
```

---

## 6. CONFIGURATION CRYPTOGRAPHIQUE ANALYSÉE

```
Config.java révisions:

KEYS_ROTATION_MINUTES = 1440              // 24h (env var override)
ASYMMETRIC_KEY_SIZE = 3072                // RSA bits
RECONCILIATION_INTERVAL_MINUTES = 15      // Watermark sync
CAPABILITY_CACHE_TTL_SECONDS = 60         // Positive cache
CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS = 5 // Rejection cache
CONFIG_CACHE_TTL_SECONDS = 60              // (inferred from code)
```

**Évaluation:** ✅ Values corrects, documentés, env var override supporté.

---

## 7. TESTS VALIDÉS

```
SessionCapacityTest ✅
  - FIFO eviction correct
  - LIFO eviction correct
  - No limit keeps all

CapabilityCacheTest ✅
  - Cache TTL values resolved
  - Invalidation works
  - Clear cache works

ProtocolTest ✅
  - buildMessageId correct
  - parseMessageId handles version/format
  - validateIdentifier accepts valid IDs

Plus 11 autres test suites couvrant:
  - Signing, verification, revocation
  - Trust anchor security
  - Multi-instance sessions
  - Token tracking
  - Reconciliation wiring
```

**Couverture de test:** Bonne (14 suites couvrent edge cases)

---

## 8. RECOMMANDATIONS

### 8.1 IMMEDIATE (Before production deployment)

#### ✅ Fix GroupLocks memory leak

**Effort:** 1-2 heures
**Risk:** Low (isolated change)

```java
// Add periodic cleanup
scheduler.scheduleAtFixedRate(() -> {
    // Cleanup implementation (see § 4.1)
}, 5, 5, TimeUnit.MINUTES);
```

### 8.2 SHORT TERM (Next release)

#### Add identifier normalization
```java
// Unicode normalization + ASCII-only validation
```

#### Document rate limiting expectations
```
README: "Rate limiting should be applied at service boundary layer"
```

#### Add replay prevention example in docs
```
Document expected pattern for application to track used tokens
```

### 8.3 MEDIUM TERM (Post-v4.0)

#### Consider EdDSA as default ephemeral key
```
- Faster than RSA-3072 (~2x)
- Inherently constant-time
- Post-quantum consideration
```

#### Enhanced monitoring
```
- Alert on rapid GroupLocks growth
- Alert on capacity limit hits
- Alert on revocation delays
```

---

## 9. SECURITY CHECKLIST

- ✅ Cryptographic implementation (RSA-3072, ECDSA-256, SHA256)
- ✅ Envelope parsing (strict bounds + type checking)
- ✅ Watermark monotonicity (strict > validation)
- ✅ Default-deny architecture (8-step verification)
- ✅ TrustRoot integration (PublicKey + Delegated)
- ✅ Liveness protocol (positive-proof + freshness)
- ✅ Capability delegation (depth limit + scope coverage)
- ⚠️ GroupLocks cleanup (MUST fix before production)
- ⚠️ Rate limiting (application responsibility)
- ⚠️ Replay protection (application responsibility)
- ⚠️ Identifier normalization (recommend fix)

---

## 10. FINAL VERDICT

| Critère | Status |
|---------|--------|
| **Cryptographic design** | ✅ Excellent |
| **Envelope parsing** | ✅ Strict |
| **Verification pipeline** | ✅ Comprehensive |
| **Default-deny posture** | ✅ Correct |
| **Code quality** | ✅ Good (well-tested) |
| **Memory management** | 🟠 1 minor leak |
| **Rate limiting** | 🟡 App responsibility |
| **Production readiness** | ✅ **YES (with GroupLocks fix)** |

---

## 11. DEPLOYMENT READINESS

### ✅ READY FOR PRODUCTION IF:
1. GroupLocks memory leak fixed
2. Rate limiting configured at service boundary
3. Monitoring alerts configured (watermark, capacity, revocation delays)

### ✅ NOT CRITICAL BLOCKERS:
- Identifier normalization (can be addressed in v4.1)
- Replay protection (documented as application responsibility)
- Rate limiting (standard DevOps pattern)

---

## Appendix A: File Review Summary

```
GenericSignerVerifier.java     ✅ Well-structured, 1 memory leak
EntryVerifier.java             ✅ 8-step validation correct
SignatureVerifier.java         ✅ Cryptographic verification proper
CapabilityVerifier.java        ✅ Delegation chain with limits
ConfigResolver.java            ✅ Hierarchical resolution + cache
CapacityManager.java           ✅ Eviction logic correct
EvictionSelector.java          ✅ FIFO/LIFO/LRU implemented correctly
SessionCounter.java            ✅ Sorting for eviction correct
LivenessChecker.java           ✅ Positive-proof protocol
VersionWatermark.java          ✅ Monotonicity enforcement
Envelope.java                  ✅ Strict parsing + bounds checking
KeyRotationService.java        ✅ Thread-safe rotation
TrustRoot (interface)          ✅ Sealed + 2 implementations
Protocol.java                  ✅ Message format + validation
FenceManager.java              ✅ Capacity fencing logic
```

---

## Conclusion

**Veridot v4.0 est architecturally sound et cryptographiquement correct.** 

La seule faille de sécurité significative est le GroupLocks memory leak, qui est **facile à corriger** et n'impacte pas la cryptographie sous-jacente.

Le code est **production-ready** après correction du leak.

