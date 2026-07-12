# Veridot V5 Protocol Specification — Sections 9–17 & Appendices

---

## §9. Capability System

The capability system governs authorization within Veridot V5. A CAPABILITY entry grants a subject the right to perform operations (sign, verify, publish, consume) within one or more scope patterns. Capabilities MAY be delegated: a subject holding a CAPABILITY with `maxDelegationDepth > 0` MAY mint a child CAPABILITY for another subject, decrementing `maxDelegationDepth` by one.

### §9.1 Chain Walking Algorithm

To verify that a subject `S` holds a valid CAPABILITY for scope `targetScope` and operation `op`, the verifier MUST walk the delegation chain from `S` back to a root authority.

```
FUNCTION walkCapabilityChain(subject, targetScope, op, broker, taas) → CapabilityResult
  MAX_DEPTH ← 10
  visited  ← ∅
  current  ← subject
  depth    ← 0

  LOOP:
    IF depth > MAX_DEPTH THEN
      RAISE V5401 DELEGATION_DEPTH_EXCEEDED(
        "Delegation chain exceeded maximum depth of 10"
      )
    END IF

    IF current ∈ visited THEN
      RAISE V5402 CIRCULAR_DELEGATION(
        "Circular delegation detected at subject: " ‖ current
      )
    END IF

    visited ← visited ∪ {current}

    cap ← broker.lookupCapability(current, targetScope)
    IF cap = NULL THEN
      RAISE V5403 NO_CAPABILITY(
        "No capability found for subject: " ‖ current ‖ " scope: " ‖ targetScope
      )
    END IF

    ; Verify the capability envelope signature
    trustEntry ← taas.resolve(cap.signerSubject)
    IF trustEntry = NULL THEN
      RAISE V5101 TRUST_RESOLUTION_FAILED(
        "Cannot resolve trust for signer: " ‖ cap.signerSubject
      )
    END IF

    valid ← verifySignature(cap.envelope, trustEntry.publicKey, trustEntry.algorithm)
    IF NOT valid THEN
      RAISE V5102 SIGNATURE_INVALID(
        "Capability signature verification failed"
      )
    END IF

    ; Check operation permission
    IF op NOT IN cap.allowedOperations THEN
      RAISE V5404 OPERATION_DENIED(
        "Operation " ‖ op ‖ " not permitted by capability"
      )
    END IF

    ; Check scope pattern match
    IF NOT matchesScopePattern(targetScope, cap.scopePatterns) THEN
      RAISE V5405 SCOPE_MISMATCH(
        "Target scope does not match any capability scope pattern"
      )
    END IF

    ; Check expiry
    IF now() > cap.validUntil THEN
      RAISE V5406 CAPABILITY_EXPIRED(
        "Capability expired at: " ‖ cap.validUntil
      )
    END IF

    ; If the signer is a root authority, the chain is valid
    IF taas.isRoot(cap.signerSubject) THEN
      RETURN CapabilityResult(GRANTED, depth, cap)
    END IF

    ; Otherwise, walk up to the signer
    current ← cap.signerSubject
    depth   ← depth + 1
  END LOOP
END FUNCTION
```

> **Rationale — Max delegation depth = 10:** Empirically, microservice delegation chains rarely exceed 3–4 levels (service → team-admin → platform-admin → root). 10 provides generous headroom while bounding computational cost (each level = 1 broker read + 1 signature verification + 1 TrustRoot resolution ≈ 3 ms per level). Beyond 10, the risk of circular delegation laundering (where a chain obscures the true authority) grows unacceptably.

### §9.2 Capability Resolution

When resolving a capability for a subject and scope, implementations MUST apply the following lookup order:

1. **Exact match** — The scope string matches a `scopePattern` entry exactly.
2. **Prefix match** — The scope string starts with a `scopePattern` that ends with `:*` (wildcard suffix).
3. **Subject pattern match** — The capability's `subjectPattern` field uses the `~cn@` convention to match all subjects sharing a common name prefix.

The `~cn@` convention works as follows: a `subjectPattern` value of `~myservice@` matches any subject whose `cn-part` equals `myservice`, regardless of the `hash-part`. This allows a single CAPABILITY to authorize all key-rotated identities of the same logical service.

```
FUNCTION matchesScopePattern(targetScope, patterns) → boolean
  FOR EACH pattern IN patterns DO
    IF pattern = targetScope THEN
      RETURN true
    END IF
    IF pattern ends with ":*" THEN
      prefix ← pattern[0 .. len(pattern) - 2]   ; strip trailing "*"
      IF targetScope starts with prefix THEN
        RETURN true
      END IF
    END IF
  END FOR
  RETURN false
END FUNCTION

FUNCTION matchesSubjectPattern(candidateSubject, subjectPattern) → boolean
  IF subjectPattern does NOT start with "~" THEN
    RETURN candidateSubject = subjectPattern
  END IF
  ; Extract the CN portion: "~cn@" → match any subject with cn-part = "cn"
  patternCN ← subjectPattern[1 .. indexOf(subjectPattern, "@") - 1]
  candidateCN ← candidateSubject[0 .. indexOf(candidateSubject, "@") - 1]
  RETURN patternCN = candidateCN
END FUNCTION
```

Implementations MUST check `subjectPattern` before checking `subjectSid`. If `subjectPattern` is present and non-empty, it takes precedence as the authorization target. If both are present, the entry authorizes the specific `subjectSid` AND any subject matching `subjectPattern`.

### §9.3 Caching

Implementations SHOULD cache resolved capabilities to avoid repeated chain walks. The cache operates at two levels:

| Cache Level | Key | Value | TTL | Eviction |
|---|---|---|---|---|
| **Positive** | `(subject, scope, op)` | `CapabilityResult` | `min(cap.validUntil - now(), CAPABILITY_CACHE_TTL)` | Time-based |
| **Negative** | `(subject, scope, op)` | `DENIED` | `CAPABILITY_NEGATIVE_CACHE_TTL` (default: 30 s) | Time-based |

Negative cache entries MUST have a short TTL (≤ 60 s) to avoid stale denials after capability grants. Implementations MUST invalidate positive cache entries when:

1. A TRUST_REVOCATION entry is received for any subject in the cached chain.
2. A reconciliation cycle detects a newer CAPABILITY version for any subject in the cached chain.
3. The entry's `validUntil` timestamp is reached.

Cache writes MUST use atomic compare-and-set operations to prevent race conditions between concurrent verifications.

---

## §10. Liveness System

The liveness system provides real-time presence and revocation signaling. Each instance periodically publishes a LIVENESS entry proving it is active and has not been revoked. Verifiers check liveness to reject entries from compromised or decommissioned instances.

### §10.1 Verification

The liveness verification pipeline consists of 8 steps, executed in order. If any step fails, the entire verification MUST be rejected.

```
FUNCTION verifyLiveness(entry, taas, broker, watermarks) → LivenessResult
  ; Step 1: Parse the LIVENESS payload
  payload ← parseLivenessPayload(entry)
  IF payload = NULL THEN
    RAISE V5201 MALFORMED_LIVENESS("Cannot parse LIVENESS payload")
  END IF

  ; Step 2: Verify entry type
  IF entry.entryType ≠ LIVENESS (0x04) THEN
    RAISE V5202 WRONG_ENTRY_TYPE("Expected LIVENESS entry type")
  END IF

  ; Step 3: Resolve trust for the signer
  trustEntry ← taas.resolve(entry.signerSubject)
  IF trustEntry = NULL THEN
    RAISE V5101 TRUST_RESOLUTION_FAILED(
      "Cannot resolve trust for: " ‖ entry.signerSubject
    )
  END IF

  ; Step 4: Verify the envelope signature
  valid ← verifySignature(entry.envelope, trustEntry.publicKey, trustEntry.algorithm)
  IF NOT valid THEN
    RAISE V5102 SIGNATURE_INVALID("LIVENESS signature verification failed")
  END IF

  ; Step 5: Check status field
  IF payload.status = REVOKED THEN
    RETURN LivenessResult(REVOKED, entry.signerSubject)
  END IF

  ; Step 6: Check freshness — asOf timestamp
  age ← now() - payload.asOf
  IF age > MAX_LIVENESS_AGE_SECONDS THEN
    RAISE V5203 LIVENESS_STALE(
      "LIVENESS entry is " ‖ age ‖ "s old, max=" ‖ MAX_LIVENESS_AGE_SECONDS
    )
  END IF

  ; Step 7: Check validUntil
  IF now() > payload.validUntil THEN
    RAISE V5204 LIVENESS_EXPIRED("LIVENESS entry has expired")
  END IF

  ; Step 8: Version watermark check
  accepted ← watermarks.accept(
    watermarkKey("liveness", entry.signerSubject, entry.scope),
    entry.version
  )
  IF NOT accepted THEN
    RAISE V5301 VERSION_REJECTED("LIVENESS version rejected by watermark")
  END IF

  RETURN LivenessResult(ALIVE, entry.signerSubject)
END FUNCTION
```

Implementations MUST execute all 8 steps in the specified order. Short-circuit evaluation (returning early on the first failure) is permitted and encouraged.

### §10.2 Publication

An instance MUST publish a LIVENESS entry upon successful boot and registration. The publication process is:

1. Construct a LIVENESS payload with:
   - `status` = ALIVE (0x01)
   - `asOf` = current wall-clock time (milliseconds since epoch)
   - `validUntil` = `asOf` + `LIVENESS_VALIDITY_SECONDS × 1000`
2. Wrap the payload in an envelope with a monotonically increasing version.
3. Sign the envelope with the instance's private key.
4. Publish to the broker on the scope the instance is registered for.

The first LIVENESS entry published after boot MUST have a version strictly greater than any previously published version for the same `(subject, scope)` pair. Implementations SHOULD persist the last published version to stable storage to survive restarts.

### §10.3 Renewal Loop

After initial publication, the instance MUST enter a renewal loop:

```
FUNCTION livenessRenewalLoop(instance, broker, scope)
  validity ← LIVENESS_VALIDITY_SECONDS
  renewAt  ← validity × 0.80          ; 80% of validity duration

  LOOP FOREVER:
    sleep(renewAt seconds)

    IF instance.isRevoked() THEN
      ; Publish a final REVOKED entry and exit
      publishLiveness(instance, broker, scope, REVOKED)
      RETURN
    END IF

    publishLiveness(instance, broker, scope, ALIVE)
  END LOOP
END FUNCTION
```

If a renewal attempt fails (network error, broker unavailable), the implementation MUST retry with exponential backoff:

| Retry | Delay |
|---|---|
| 1 | 1 s |
| 2 | 2 s |
| 3 | 4 s |
| 4 | 8 s |
| 5+ | 16 s (capped) |

If renewal has not succeeded by the time `validUntil` is reached, the instance MUST cease publishing signed entries (except a final REVOKED liveness entry if connectivity is restored) and MUST log a security alert.

> **Rationale — 80% renewal threshold:** 80% leaves 20% of the validity duration as a grace window for network failures or scheduling delays. This is a standard practice: TLS OCSP stapling renews at ~50%, ACME (Let's Encrypt) recommends ~66%. 80% minimizes unnecessary renewals (compared to 50%) while maintaining a comfortable margin for 1–2 retry attempts before expiration.

---

## §11. Capacity Management

Capacity management controls how many concurrent sessions (instances) may operate within a given scope. The FENCE entry type provides distributed mutual exclusion, and the CONFIG entry's `max` field defines the session limit.

### §11.1 Enforcement Flow

When an instance attempts to join a scope, the capacity enforcement flow executes:

```
FUNCTION enforceCapacity(instance, scope, broker, config) → EnforcementResult
  MAX_RETRIES  ← 3
  BASE_BACKOFF ← 50   ; milliseconds

  FOR attempt ← 1 TO MAX_RETRIES DO
    ; Read the current FENCE entry for this scope
    fence ← broker.readFence(scope)

    ; Read the CONFIG entry for this scope
    maxSessions ← config.max
    IF maxSessions = 0 THEN
      RAISE V5301 CAPACITY_DISABLED("Capacity management disabled for scope")
    END IF

    ; Count active sessions (those with valid, non-revoked LIVENESS)
    activeSessions ← countActiveSessions(scope, broker)

    IF activeSessions < maxSessions THEN
      ; Attempt to acquire a slot
      newFence ← fence.incrementCounter()
      newFence.grantedTo ← instance.subject
      newFence.validUntil ← now() + FENCE_VALIDITY_SECONDS

      success ← broker.compareAndSwapFence(scope, fence, newFence)
      IF success THEN
        RETURN EnforcementResult(GRANTED, newFence.fenceCounter)
      END IF
      ; CAS failed — another instance raced us; retry
    ELSE
      ; At capacity — attempt eviction per policy
      evicted ← applyEvictionPolicy(scope, config.evictionPolicy, broker)
      IF NOT evicted THEN
        IF config.evictionPolicy = REJECT THEN
          RAISE V5302 CAPACITY_EXCEEDED(
            "Scope " ‖ scope ‖ " at capacity (" ‖ maxSessions ‖ ")"
          )
        END IF
      END IF
      ; Eviction succeeded — retry acquisition
    END IF

    sleep(BASE_BACKOFF × attempt)   ; linear backoff: 50ms, 100ms, 150ms
  END FOR

  RAISE V5303 CAPACITY_CONTENTION(
    "Failed to acquire capacity after " ‖ MAX_RETRIES ‖ " retries"
  )
END FUNCTION
```

> **Rationale — 3 retries, 50 ms × attempt backoff:** FENCE contention is rare (requires two instances writing the same scope simultaneously). 3 retries with linear backoff (50 ms + 100 ms + 150 ms = 300 ms total worst case) resolves contention without blocking the calling thread excessively. Exponential backoff (50 ms, 100 ms, 200 ms = 350 ms) provides marginal improvement for a window this short.

### §11.2 Eviction Policies

When a scope reaches its session capacity and a new instance requests admission, the eviction policy determines which existing session (if any) is removed.

| Code | Policy | Description |
|---|---|---|
| `0x01` | **FIFO** | Evict the **oldest** session (earliest `grantedAt` timestamp). |
| `0x02` | **LIFO** | Evict the **newest** session (latest `grantedAt` timestamp). |
| `0x03` | **LRU** | Evict the **least recently active** session (oldest last-LIVENESS `asOf`). |
| `0x04` | **REJECT** | Do not evict. Reject the incoming instance with `V5302 CAPACITY_EXCEEDED`. |

The eviction policy is specified in the CONFIG entry's `pol` field. If `pol` is absent or unrecognized, implementations MUST default to **REJECT**.

When evicting a session, the implementation MUST:

1. Publish a LIVENESS entry with `status = REVOKED` on behalf of the evicted instance (signed by the scope administrator or TAAS).
2. Update the FENCE entry to remove the evicted instance from `grantedTo`.
3. Log the eviction event including the evicted subject, scope, and policy applied.

### §11.3 FENCE Acquisition

The FENCE entry provides distributed compare-and-swap semantics. The wire format of the FENCE payload is:

| Tag | Field | Type | Description |
|---|---|---|---|
| 0x01 | `fenceCounter` | u64 BE | Monotonically increasing counter. |
| 0x02 | `grantedTo` | UTF-8 | Subject identifier of the instance holding the fence. |
| 0x03 | `validUntil` | u64 BE | Expiry timestamp (ms since epoch). |

FENCE acquisition is atomic at the broker level. The broker MUST support a compare-and-swap operation on the FENCE entry: the write succeeds only if the current `fenceCounter` matches the expected value. If the CAS fails, the broker MUST return a contention error, and the client MUST retry per §11.1.

A FENCE entry MUST be signed by the instance acquiring it. The broker MUST verify the signature before accepting the CAS operation. This prevents an attacker from forging a FENCE entry to steal another instance's slot.

FENCE entries expire at `validUntil`. Expired FENCE entries MUST be treated as released slots. Implementations SHOULD set `validUntil` to be slightly longer than the LIVENESS validity period to avoid premature slot release during liveness renewal.

---

## §12. Version Watermarks

Version watermarks are the primary defense against replay attacks. Each verifier maintains a set of watermarks keyed by `(entryType, subject, scope)` tuples, recording the highest version number accepted for each combination.

### §12.1 Invariants

The watermark system enforces three invariants:

1. **Monotonicity** — For any given watermark key, the accepted version MUST be strictly greater than the previously recorded version. Versions that are equal to or less than the recorded watermark MUST be rejected with `V5301 VERSION_REJECTED`.

2. **Atomicity** — Watermark reads and writes MUST be atomic. A version check and subsequent watermark update MUST execute as a single indivisible operation. Implementations MUST use atomic primitives (e.g., `ConcurrentHashMap.compute()`) to prevent TOCTOU races.

3. **Version 0 rejection** — Version 0 MUST be unconditionally rejected, regardless of the current watermark state.

```
FUNCTION watermarkAccept(watermarks, key, version) → boolean
  IF version = 0 THEN
    RETURN false    ; Invariant 3
  END IF

  RETURN watermarks.compute(key, λ(currentVersion) →
    IF currentVersion = NULL THEN
      ; First entry for this key — accept
      RETURN version
    END IF
    IF version > currentVersion THEN
      ; Monotonicity satisfied — accept and update
      RETURN version
    ELSE
      ; Replay or stale — reject (keep current)
      RETURN currentVersion
    END IF
  ) = version       ; Returns true only if the value was updated to `version`
END FUNCTION
```

> **Rationale — Version 0 unconditionally rejected:** See §3.6. If version 0 were accepted, an uninitialized watermark (default 0) would accept a version-0 replay, allowing an attacker to insert a "genesis" entry at any time.

### §12.2 Watermark Key Format

The watermark key is a composite string constructed as:

```
watermarkKey = entryTypeName ":" subject ":" scope
```

Where:

| Component | Type | Example |
|---|---|---|
| `entryTypeName` | ASCII string | `"liveness"`, `"signed_data"`, `"capability"` |
| `subject` | Subject identifier | `"myservice@a1b2c3d4..."` |
| `scope` | Scope string | `"group:payments"`, `"global"` |

Examples:

- `liveness:myservice@a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6:group:payments`
- `signed_data:gateway@x9y8z7w6v5u4t3s2r1q0p9o8n7m6l5k4:global`

The key format MUST be deterministic: the same `(entryType, subject, scope)` triple MUST always produce the same key string. Implementations MUST NOT include trailing whitespace or normalize Unicode.

### §12.3 Persistence

Watermark state MUST survive process restarts. Implementations MUST persist watermarks to stable storage. The following persistence strategies are acceptable:

| Strategy | Durability | Performance | Suitability |
|---|---|---|---|
| **Write-ahead log** | High | High | Production deployments |
| **Periodic snapshot** | Medium (up to 1 snapshot interval of loss) | High | Acceptable if replay of a small window is tolerable |
| **Synchronous write** | Highest | Low | High-security deployments |

If watermark state is lost (e.g., due to unrecoverable storage failure), the instance MUST perform a full reconciliation (§13) before accepting any new entries. Until reconciliation completes, the instance MUST reject all entries with `V5304 WATERMARK_RECOVERY_IN_PROGRESS`.

Implementations SHOULD use a compact binary format for watermark storage. A recommended layout is:

```
[keyLenU16 BE] [key: UTF-8] [version: u64 BE]
```

Entries are stored sequentially. On startup, the implementation reads all entries into the in-memory `ConcurrentHashMap`.

---

## §13. Reconciliation

Reconciliation is the process by which a verifier synchronizes its local state with the broker to recover from missed entries, network partitions, or restarts.

### §13.1 Trigger Conditions

Reconciliation MUST be triggered when:

1. **Instance startup** — After boot, before accepting any entries.
2. **Gap detection** — A version gap is detected (received version `n+k` where `k > 1` and version `n` was the last accepted).
3. **Periodic schedule** — At intervals defined by `RECONCILIATION_INTERVAL_SECONDS`.
4. **Manual trigger** — An operator explicitly requests reconciliation via the management API.

### §13.2 Reconciliation Protocol

```
FUNCTION reconcile(scope, broker, watermarks, taas) → ReconciliationResult
  ; Step 1: Fetch the latest SNAPSHOT for this scope (if available)
  snapshot ← broker.latestSnapshot(scope)

  ; Step 2: Determine the starting point
  IF snapshot ≠ NULL AND snapshot.version > watermarks.get("snapshot:" ‖ scope) THEN
    ; Apply snapshot — bulk-load watermarks
    FOR EACH (key, version) IN snapshot.watermarkEntries DO
      watermarks.computeIfGreater(key, version)
    END FOR
    startVersion ← snapshot.snapshotAt
  ELSE
    ; No usable snapshot — start from last known watermark
    startVersion ← watermarks.maxVersionForScope(scope)
    IF startVersion = NULL THEN
      startVersion ← 0
    END IF
  END IF

  ; Step 3: Fetch all entries after startVersion
  entries ← broker.fetchEntriesAfter(scope, startVersion)

  ; Step 4: Verify and apply each entry in order
  applied ← 0
  skipped ← 0
  FOR EACH entry IN entries (ordered by version ascending) DO
    result ← verifyEntry(entry, taas, watermarks)
    IF result.accepted THEN
      applied ← applied + 1
    ELSE
      skipped ← skipped + 1
      log.warn("Reconciliation skipped entry: " ‖ entry.version ‖
               " reason: " ‖ result.errorCode)
    END IF
  END FOR

  RETURN ReconciliationResult(applied, skipped, startVersion, entries.lastVersion)
END FUNCTION
```

### §13.3 Snapshot Entries

A SNAPSHOT entry is a point-in-time capture of all watermark state for a scope. SNAPSHOT entries are published by authorized instances (those holding a CAPABILITY with snapshot-publish permission).

| Tag | Field | Type | Description |
|---|---|---|---|
| 0x01 | `snapshotAt` | u64 BE | The version number at which the snapshot was taken. |
| 0x02 | `entryCount` | u32 BE | Number of watermark entries in the snapshot. |

The snapshot body follows the TLV header and contains `entryCount` watermark entries in the format defined in §12.3.

Implementations SHOULD publish SNAPSHOT entries periodically (e.g., every `SNAPSHOT_INTERVAL_ENTRIES` entries) to bound reconciliation time for new joiners.

### §13.4 Consistency Guarantees

Reconciliation provides **eventual consistency**. After a successful reconciliation cycle:

- All entries published before the reconciliation start time and still retained by the broker are reflected in the local watermark state.
- No replay of a previously-accepted entry is possible (watermark monotonicity is preserved).
- Entries published during reconciliation MAY be missed and will be caught by the next reconciliation cycle or by normal verification flow.

Reconciliation does NOT provide:

- **Strong consistency** — Two verifiers may temporarily disagree on which entries have been accepted.
- **Total ordering across scopes** — Reconciliation is per-scope. Cross-scope ordering is not guaranteed.

---

## §14. Encrypted Payloads (E2EE)

Veridot V5 supports end-to-end encryption of entry payloads, ensuring that the broker and intermediate infrastructure cannot read payload contents. Encryption is applied at the application layer, above the signing layer: the encrypted payload is the data that gets signed.

### §14.1 Modes

Veridot V5 supports three encryption modes:

| Mode | Description | Use Case |
|---|---|---|
| **NONE** | No encryption. Payload is plaintext. | Public data, non-sensitive configuration. |
| **SYMMETRIC** | Payload encrypted with a shared symmetric key. All authorized recipients possess the key. | Group communication where all members share a key. |
| **ASYMMETRIC** | Payload encrypted with a per-message symmetric key, which is then wrapped (encrypted) for each recipient's public key. | Point-to-point or selective-disclosure scenarios. |

The encryption mode is indicated by the `encAlg` field (tag 0x01) in the SECURE_PAYLOAD entry type:

| Value | Mode | Algorithm |
|---|---|---|
| 0x00 | NONE | — |
| 0x01 | SYMMETRIC | AES-256-GCM |
| 0x02 | ASYMMETRIC | AES-256-GCM + per-recipient key wrapping |

### §14.2 Symmetric Encryption

When `encAlg = 0x01`, the payload is encrypted using AES-256-GCM:

| Field | Size | Description |
|---|---|---|
| `nonce` | 12 bytes | Unique nonce for this encryption operation. |
| `data` | variable | AES-256-GCM ciphertext. |
| (trailing) | 16 bytes | GCM authentication tag (appended to ciphertext). |

The symmetric key is distributed out-of-band (e.g., via TAAS key distribution or a shared secret protocol). The key MUST be 256 bits (32 bytes).

Encryption:

```
FUNCTION symmetricEncrypt(plaintext, key) → EncryptedPayload
  nonce ← CSPRNG(12)                       ; 12 bytes from secure random
  ciphertext ‖ tag ← AES-256-GCM.encrypt(key, nonce, plaintext, aad="")
  RETURN EncryptedPayload(nonce, ciphertext ‖ tag)
END FUNCTION
```

Decryption:

```
FUNCTION symmetricDecrypt(payload, key) → bytes
  plaintext ← AES-256-GCM.decrypt(key, payload.nonce, payload.data, aad="")
  IF plaintext = FAILURE THEN
    RAISE V5501 DECRYPTION_FAILED("AES-256-GCM decryption or authentication failed")
  END IF
  RETURN plaintext
END FUNCTION
```

> **Security note — Nonce reuse:** AES-256-GCM with a reused (key, nonce) pair is catastrophically broken: the authentication tag becomes forgeable and plaintext can be recovered via XOR of ciphertexts. The 12-byte nonce MUST be generated from a cryptographically secure pseudorandom number generator (CSPRNG). With random nonces, the birthday bound limits safe usage to approximately 2³² encryptions per key before nonce collision probability exceeds 2⁻³². For high-volume producers, implementations SHOULD rotate the symmetric key after 2³⁰ encryptions.

### §14.3 RecipientBlock Wire Format

When `encAlg = 0x02` (ASYMMETRIC), the SECURE_PAYLOAD contains one or more `RecipientBlock` structures. Each block wraps the per-message symmetric key for a single recipient.

```
RecipientBlock ::=
  [recipientSidLen: u16 BE] [recipientSid: UTF-8]
  [recipientTrustVersion: u64 BE]
  [encryptedKeyLen: u16 BE] [encryptedKey: bytes]
```

| Field | Type | Description |
|---|---|---|
| `recipientSidLen` | u16 BE | Length of the `recipientSid` field in bytes. |
| `recipientSid` | UTF-8 | Subject identifier of the intended recipient. |
| `recipientTrustVersion` | u64 BE | The `TrustEntry.version` of the recipient at the time of encryption. |
| `encryptedKeyLen` | u16 BE | Length of the `encryptedKey` field in bytes. |
| `encryptedKey` | bytes | The per-message symmetric key, encrypted (wrapped) using the recipient's public key. |

`recipientTrustVersion` is the `TrustEntry.version` of the recipient at the time of encryption. The decrypting recipient uses this to resolve the correct private key if multiple key versions exist.

> **Rationale:** Each instance has a single keypair, but key rotation (re-registration with a new keypair) produces a new TrustEntry version. The trust version allows the recipient to identify which of its historical keypairs was used for wrapping, enabling decryption of messages encrypted before a key rotation.

The `recipients` field (tag 0x03) in the SECURE_PAYLOAD TLV contains the concatenation of all `RecipientBlock` structures, prefixed by a recipient count:

```
recipients ::=
  [recipientCount: u16 BE]
  RecipientBlock{recipientCount}
```

### §14.4 Key Wrapping

The per-message symmetric key is wrapped using the recipient's public key. The wrapping algorithm depends on the recipient's registered algorithm:

| Recipient Algorithm | Key Wrap Method |
|---|---|
| Ed25519 (0x01) | X25519 ECDH + HKDF-SHA-256, then AES-256-GCM key-wrap |
| ECDSA-P256 (0x02) | ECDH-P256 + HKDF-SHA-256, then AES-256-GCM key-wrap |
| RSA-PSS (0x04) | RSA-OAEP with SHA-256 |
| ML-DSA-65 (0x10) | ML-KEM-768 (companion KEM for ML-DSA) |

For Ed25519 recipients, the sender MUST convert the Ed25519 public key to an X25519 public key (per RFC 7748) before performing the ECDH exchange. The HKDF info string MUST be `"veridot-v5-key-wrap"`.

```
FUNCTION wrapKey(symmetricKey, recipientPublicKey, recipientAlgorithm) → bytes
  SWITCH recipientAlgorithm:
    CASE Ed25519:
      x25519Pub ← ed25519ToX25519(recipientPublicKey)
      ephemeralPriv, ephemeralPub ← X25519.generateKeypair()
      sharedSecret ← X25519.exchange(ephemeralPriv, x25519Pub)
      wrapKey ← HKDF-SHA-256(
        salt = ephemeralPub,
        ikm  = sharedSecret,
        info = "veridot-v5-key-wrap",
        len  = 32
      )
      nonce ← CSPRNG(12)
      wrapped ← AES-256-GCM.encrypt(wrapKey, nonce, symmetricKey, aad="")
      RETURN ephemeralPub ‖ nonce ‖ wrapped

    CASE ECDSA-P256:
      ephemeralPriv, ephemeralPub ← ECDH-P256.generateKeypair()
      sharedSecret ← ECDH-P256.exchange(ephemeralPriv, recipientPublicKey)
      wrapKey ← HKDF-SHA-256(
        salt = ephemeralPub,
        ikm  = sharedSecret,
        info = "veridot-v5-key-wrap",
        len  = 32
      )
      nonce ← CSPRNG(12)
      wrapped ← AES-256-GCM.encrypt(wrapKey, nonce, symmetricKey, aad="")
      RETURN ephemeralPub ‖ nonce ‖ wrapped

    CASE RSA-PSS:
      RETURN RSA-OAEP-SHA256.encrypt(recipientPublicKey, symmetricKey)

    CASE ML-DSA-65:
      ciphertext, wrappedKey ← ML-KEM-768.encapsulate(recipientPublicKey)
      RETURN ciphertext ‖ wrappedKey
  END SWITCH
END FUNCTION
```

### §14.5 Asymmetric Encryption Flow

The full asymmetric encryption flow for a sender:

```
FUNCTION asymmetricEncrypt(plaintext, recipients, taas) → SecurePayload
  ; Generate a random per-message symmetric key
  messageKey ← CSPRNG(32)

  ; Encrypt the plaintext
  nonce ← CSPRNG(12)
  ciphertext ‖ tag ← AES-256-GCM.encrypt(messageKey, nonce, plaintext, aad="")

  ; Wrap the message key for each recipient
  recipientBlocks ← []
  FOR EACH recipient IN recipients DO
    trustEntry ← taas.resolve(recipient.subject)
    IF trustEntry = NULL THEN
      RAISE V5101 TRUST_RESOLUTION_FAILED(
        "Cannot resolve trust for recipient: " ‖ recipient.subject
      )
    END IF
    encryptedKey ← wrapKey(messageKey, trustEntry.publicKey, trustEntry.algorithm)
    recipientBlocks.append(RecipientBlock(
      recipientSid         = recipient.subject,
      recipientTrustVersion = trustEntry.version,
      encryptedKey         = encryptedKey
    ))
  END FOR

  ; Zero the message key
  zeroMemory(messageKey)

  RETURN SecurePayload(
    encAlg     = 0x02,
    nonce      = nonce,
    recipients = recipientBlocks,
    data       = ciphertext ‖ tag
  )
END FUNCTION
```

### §14.6 Decryption Flow

```
FUNCTION asymmetricDecrypt(payload, mySubject, myKeyStore) → bytes
  ; Find our RecipientBlock
  block ← NULL
  FOR EACH rb IN payload.recipients DO
    IF rb.recipientSid = mySubject THEN
      block ← rb
      BREAK
    END IF
  END FOR

  IF block = NULL THEN
    RAISE V5502 NOT_A_RECIPIENT("No RecipientBlock found for subject: " ‖ mySubject)
  END IF

  ; Resolve the correct private key using the trust version
  privateKey ← myKeyStore.getKeyForTrustVersion(block.recipientTrustVersion)
  IF privateKey = NULL THEN
    RAISE V5503 KEY_VERSION_NOT_FOUND(
      "No private key for trust version: " ‖ block.recipientTrustVersion
    )
  END IF

  ; Unwrap the message key
  messageKey ← unwrapKey(block.encryptedKey, privateKey, myAlgorithm)

  ; Decrypt the payload
  plaintext ← AES-256-GCM.decrypt(messageKey, payload.nonce, payload.data, aad="")
  IF plaintext = FAILURE THEN
    RAISE V5501 DECRYPTION_FAILED("Payload decryption failed")
  END IF

  ; Zero the message key
  zeroMemory(messageKey)

  RETURN plaintext
END FUNCTION
```

---

## §15. Trust Authority & Attestation Service (TAAS)

TAAS is the centralized trust anchor for Veridot V5. It stores the mapping from subject identifiers to public keys, manages key lifecycle (registration, rotation, revocation), and optionally verifies hardware/software attestation during registration.

### §15.1 Data Model

The core data structure is the `TrustEntry`:

| Field | Type | Description |
|---|---|---|
| `subject` | string | Subject identifier (`CN@base64url(SHA-256(pk))[0:32]`). |
| `publicKey` | bytes | The instance's public key (DER-encoded). |
| `algorithm` | u8 | Signature algorithm identifier (see §6). |
| `version` | u64 | Monotonically increasing version. Incremented on each mutation (registration, rotation, revocation). |
| `status` | enum | `ACTIVE`, `ROTATED`, `REVOKED`. |
| `registeredAt` | u64 | Timestamp of initial registration (ms since epoch). |
| `notAfter` | u64 | Expiry timestamp. After this time, the entry SHOULD NOT be used for new operations. |
| `attestation` | bytes | Optional. Attestation evidence provided during registration. |

The `TrustIdentity` is the public-facing projection of a `TrustEntry`, containing:

| Field | Type | Description |
|---|---|---|
| `publicKey` | bytes | The instance's public key. |
| `isRoot` | boolean | Whether this identity is a root authority. |
| `algorithm` | enum | The signature algorithm. |

### §15.2 Registration

An instance registers with TAAS by presenting:

1. Its public key.
2. A desired common name (CN).
3. Optional attestation evidence.

TAAS MUST:

1. Validate the public key format for the declared algorithm.
2. Compute the subject identifier: `CN@base64url(SHA-256(publicKey))[0:32]`.
3. Verify attestation evidence (if required by the scope's CONFIG `attestationPlugin` setting).
4. Check that no ACTIVE entry exists for the same subject. If one exists, reject with `V5103 DUPLICATE_SUBJECT`.
5. Assign a version (starting at 1 for new subjects).
6. Persist the `TrustEntry` via Raft consensus.
7. Return the `TrustIdentity` and initial CAPABILITY (if configured).

### §15.3 Key Rotation

Key rotation replaces an instance's keypair while preserving its logical identity (common name). The rotation process:

1. The instance generates a new keypair.
2. The instance calls TAAS `rotate(oldSubject, newPublicKey, proofOfPossession)`.
3. `proofOfPossession` is a signature over `"rotate:" ‖ oldSubject ‖ ":" ‖ newPublicKey` using the **old** private key.
4. TAAS verifies the proof of possession using the old public key.
5. TAAS computes the new subject: `CN@base64url(SHA-256(newPublicKey))[0:32]`.
6. TAAS marks the old `TrustEntry` as `ROTATED` and creates a new `TrustEntry` with `version = oldVersion + 1`.
7. TAAS publishes a TRUST_REVOCATION entry for the old subject.

After rotation, the old private key SHOULD be destroyed. The old `TrustEntry` (status `ROTATED`) MUST be retained for the duration of `TRUST_STALE_WINDOW_SECONDS` to allow in-flight verifications and encrypted-message decryption to complete.

### §15.4 Follower Re-validation

In a Raft-based TAAS cluster, only the leader accepts writes. Followers serve reads. To detect a Byzantine leader that might accept invalid registrations, followers MUST re-validate entries upon replication:

```
FUNCTION followerRevalidate(entry) → boolean
  ; Re-check public key format
  IF NOT isValidPublicKey(entry.publicKey, entry.algorithm) THEN
    log.alert("Byzantine leader: invalid public key in TrustEntry " ‖ entry.subject)
    RETURN false
  END IF

  ; Re-check subject computation
  expectedSubject ← computeSubject(entry.cn, entry.publicKey)
  IF entry.subject ≠ expectedSubject THEN
    log.alert("Byzantine leader: subject mismatch in TrustEntry " ‖ entry.subject)
    RETURN false
  END IF

  ; Re-check attestation (if plugin configured)
  IF entry.attestation ≠ NULL THEN
    IF NOT verifyAttestation(entry.attestation, entry.publicKey) THEN
      log.alert("Byzantine leader: attestation failure in TrustEntry " ‖ entry.subject)
      RETURN false
    END IF
  END IF

  RETURN true
END FUNCTION
```

If re-validation fails, the follower MUST:

1. Log a security alert.
2. Refuse to serve the entry to clients.
3. Notify the cluster's administrative channel.

### §15.5 Resolution (Read Path)

Trust resolution is a read operation. The verifier queries TAAS with a subject identifier and receives the corresponding `TrustIdentity`. Resolution uses a tiered cache:

| Tier | Storage | Latency | TTL |
|---|---|---|---|
| **L1 — In-process** | `ConcurrentHashMap` | < 1 μs | `TRUST_CACHE_TTL_SECONDS` |
| **L2 — Shared cache** | Redis / Memcached | < 1 ms | `TRUST_CACHE_TTL_SECONDS × 2` |
| **Remote — TAAS** | Raft read | 1–10 ms | Authoritative |

Resolution algorithm:

```
FUNCTION resolve(subject) → TrustIdentity
  ; L1 lookup
  entry ← l1Cache.get(subject)
  IF entry ≠ NULL AND NOT isExpired(entry) THEN
    RETURN entry.identity
  END IF

  ; L2 lookup
  entry ← l2Cache.get(subject)
  IF entry ≠ NULL AND NOT isExpired(entry) THEN
    l1Cache.put(subject, entry)
    RETURN entry.identity
  END IF

  ; Remote lookup
  entry ← taasClient.lookup(subject)
  IF entry = NULL THEN
    RETURN NULL
  END IF

  IF entry.status = REVOKED THEN
    RETURN NULL     ; Revoked entries are not resolvable
  END IF

  l2Cache.put(subject, entry)
  l1Cache.put(subject, entry)
  RETURN entry.identity
END FUNCTION
```

### §15.6 Revocation

Revocation permanently disables a `TrustEntry`. Revocation is triggered by:

1. **Explicit revocation** — An administrator or the instance itself calls `revoke(subject)`.
2. **Key rotation** — The old entry is marked `ROTATED` (a form of soft revocation).
3. **Attestation failure** — Periodic re-attestation fails.
4. **Expiry** — The entry's `notAfter` timestamp is reached.

Upon revocation, TAAS MUST:

1. Update the `TrustEntry` status to `REVOKED`.
2. Increment the version.
3. Publish a TRUST_REVOCATION entry to all scopes where the subject had active CAPABILITYs.
4. Invalidate L2 cache entries for the subject.

### §15.7 Attestation Plugins

TAAS supports pluggable attestation verifiers. The attestation plugin is specified per-scope in the CONFIG entry (`attestationPlugin` field, tag 0x08).

| Plugin | Attestation Type | Description |
|---|---|---|
| `none` | — | No attestation required. |
| `tpm2` | TPM 2.0 Quote | Verifies a TPM 2.0 attestation quote, including PCR values and the AIK certificate chain. |
| `sgx` | Intel SGX Quote | Verifies an SGX DCAP quote, including MRENCLAVE/MRSIGNER and Intel's attestation service signature. |
| `sev-snp` | AMD SEV-SNP Report | Verifies a SEV-SNP attestation report, including measurement and VCEK certificate. |
| `custom` | Custom | Delegates to a user-provided attestation verifier implementing the `AttestationVerifier` interface. |

Attestation verification MUST occur during registration (§15.2). Implementations MAY additionally perform periodic re-attestation at intervals defined by `CONFIG.maxInstanceLifetime`.

### §15.8 Raft Consensus

TAAS nodes form a Raft consensus group. The following Raft parameters are recommended:

| Parameter | Recommended Value | Description |
|---|---|---|
| Election timeout | 150–300 ms | Time before a follower becomes a candidate. |
| Heartbeat interval | 50 ms | Leader heartbeat frequency. |
| Log compaction threshold | 10,000 entries | Snapshot after this many log entries. |
| Maximum batch size | 100 entries | Maximum entries per AppendEntries RPC. |

Write operations (register, rotate, revoke) are committed via Raft log replication. A write is considered durable when a majority of nodes have persisted the log entry.

Read operations MAY use one of two consistency levels:

| Level | Mechanism | Staleness |
|---|---|---|
| **Linearizable** | Leader read with ReadIndex or lease-based reads. | None. |
| **Stale** | Follower read without leader confirmation. | Up to election timeout. |

For trust resolution, **stale reads** are acceptable because the L1/L2 cache already introduces bounded staleness. For revocation checks, **linearizable reads** are RECOMMENDED to minimize the window during which a revoked key is still trusted.

> **Rationale — Raft consensus (not Paxos or PBFT):** Raft is chosen for implementation simplicity and operational maturity (used by etcd, CockroachDB, TiKV). Paxos provides equivalent safety guarantees but is notoriously difficult to implement correctly. PBFT tolerates Byzantine faults but requires O(n²) message complexity per consensus round, disproportionate for TAAS's write volume (~1 write per instance boot, O(minutes) interval). The follower re-validation mechanism (§15.4) compensates for Raft's lack of Byzantine tolerance with minimal overhead.

---

## §16. Error Codes

All Veridot V5 error codes are in the V5xxx namespace. Implementations MUST use these codes in error responses and log messages.

### §16.1 Trust Errors (V51xx)

| Code | Name | Description | Severity |
|---|---|---|---|
| V5101 | `TRUST_RESOLUTION_FAILED` | The subject's public key could not be resolved from TAAS or any cache tier. | ERROR |
| V5102 | `SIGNATURE_INVALID` | The envelope signature did not verify against the resolved public key. | ERROR |
| V5103 | `DUPLICATE_SUBJECT` | A registration was attempted for a subject that already has an ACTIVE TrustEntry. | ERROR |
| V5104 | `TRUST_ENTRY_REVOKED` | The resolved TrustEntry has status REVOKED. | ERROR |
| V5105 | `TRUST_ENTRY_EXPIRED` | The resolved TrustEntry's `notAfter` timestamp has passed. | WARN |
| V5106 | `ATTESTATION_FAILED` | The attestation evidence provided during registration failed verification. | ERROR |
| V5107 | `PROOF_OF_POSSESSION_INVALID` | The proof-of-possession signature during key rotation did not verify. | ERROR |
| V5108 | `ALGORITHM_MISMATCH` | The declared algorithm does not match the public key format. | ERROR |
| V5109 | `ALGORITHM_RESERVED` | The declared algorithm identifier is reserved and not supported. | ERROR |

### §16.2 Parsing Errors (V52xx)

| Code | Name | Description | Severity |
|---|---|---|---|
| V5201 | `MALFORMED_ENVELOPE` | The envelope binary could not be parsed (truncated, invalid lengths, etc.). | ERROR |
| V5202 | `WRONG_ENTRY_TYPE` | The entry type does not match the expected type for the operation. | ERROR |
| V5203 | `LIVENESS_STALE` | The LIVENESS entry's `asOf` timestamp is older than `MAX_LIVENESS_AGE_SECONDS`. | WARN |
| V5204 | `LIVENESS_EXPIRED` | The LIVENESS entry's `validUntil` timestamp has passed. | ERROR |
| V5205 | `UNKNOWN_ENTRY_TYPE` | The entry type byte is not recognized. | ERROR |
| V5206 | `RESERVED_ENTRY_TYPE` | The entry type byte is in a reserved range (0x00, 0xFF). | ERROR |
| V5207 | `TLV_PARSE_ERROR` | A TLV field could not be parsed (invalid tag, truncated value, etc.). | ERROR |
| V5208 | `DUPLICATE_TLV_TAG` | A TLV tag appeared more than once in a payload where duplicates are not permitted. | ERROR |
| V5209 | `TRAILING_BYTES` | Unexpected bytes remain after parsing the complete envelope. | ERROR |
| V5210 | `INVALID_SUBJECT_FORMAT` | The subject identifier does not conform to the `cn-part "@" hash-part` format. | ERROR |
| V5211 | `INVALID_SCOPE_FORMAT` | The scope string does not conform to the `scope` ABNF rule. | ERROR |
| V5212 | `COMPACT_SIG_MISMATCH` | The COMPACT_SIG flag is set but the signature length does not match the expected compact size for the algorithm. | ERROR |

### §16.3 Version & Watermark Errors (V53xx)

| Code | Name | Description | Severity |
|---|---|---|---|
| V5301 | `VERSION_REJECTED` | The entry's version was rejected by the watermark (replay or regression). | WARN |
| V5302 | `CAPACITY_EXCEEDED` | The scope has reached its maximum session capacity and the eviction policy is REJECT. | ERROR |
| V5303 | `CAPACITY_CONTENTION` | Failed to acquire a capacity slot after the maximum number of retries. | ERROR |
| V5304 | `WATERMARK_RECOVERY_IN_PROGRESS` | Watermark state is being recovered; entry verification is temporarily unavailable. | WARN |

### §16.4 Capability Errors (V54xx)

| Code | Name | Description | Severity |
|---|---|---|---|
| V5401 | `DELEGATION_DEPTH_EXCEEDED` | The capability delegation chain exceeded the maximum depth of 10. | ERROR |
| V5402 | `CIRCULAR_DELEGATION` | A circular reference was detected in the delegation chain. | ERROR |
| V5403 | `NO_CAPABILITY` | No capability was found for the subject and scope. | ERROR |
| V5404 | `OPERATION_DENIED` | The capability does not permit the requested operation. | ERROR |
| V5405 | `SCOPE_MISMATCH` | The target scope does not match any of the capability's scope patterns. | ERROR |
| V5406 | `CAPABILITY_EXPIRED` | The capability's `validUntil` timestamp has passed. | ERROR |

### §16.5 Encryption Errors (V55xx)

| Code | Name | Description | Severity |
|---|---|---|---|
| V5501 | `DECRYPTION_FAILED` | AES-256-GCM decryption or authentication tag verification failed. | ERROR |
| V5502 | `NOT_A_RECIPIENT` | No RecipientBlock was found for the decrypting subject. | ERROR |
| V5503 | `KEY_VERSION_NOT_FOUND` | No private key was found for the specified `recipientTrustVersion`. | ERROR |

---

## §17. Security Considerations

This section enumerates the security properties of the Veridot V5 protocol, attack surface analysis, and recommended mitigations.

### §17.1 Threat Model

Veridot V5 assumes the following threat model:

| Entity | Trust Level |
|---|---|
| **TAAS** | Trusted (integrity and availability). Byzantine leader detection via §15.4. |
| **Broker** | Semi-trusted. The broker faithfully delivers messages but may be compromised to observe, replay, or drop entries. |
| **Network** | Untrusted. All communication is assumed to traverse adversary-controlled networks. |
| **Instances** | Mutually distrustful. Each instance trusts only its own private key and TAAS-resolved public keys of peers. |

### §17.2 Signature Security

All entries are signed. The signature covers the canonical signing bytes (§3.4), which include:

- Protocol magic, version, and entry type.
- Signer subject, scope, and version number.
- Flags and payload.

This ensures that:

1. **Integrity** — Any modification to the entry is detected.
2. **Authentication** — The entry can only have been produced by the holder of the corresponding private key.
3. **Non-repudiation** — The signer cannot deny having produced the entry (assuming private key confidentiality).

Algorithm-specific considerations:

| Algorithm | Key Size | Security Level | Notes |
|---|---|---|---|
| Ed25519 (0x01) | 256-bit | ~128-bit | Deterministic signatures. No nonce generation required. |
| ECDSA-P256 (0x02) | 256-bit | ~128-bit | Requires secure nonce generation. Use RFC 6979 deterministic nonces. |
| RSA-PSS (0x04) | 2048+ bit | ~112-bit (2048) | Use salt length = hash length (32 bytes for SHA-256). |
| ML-DSA-65 (0x10) | — | NIST Level 3 | Post-quantum. Large signatures (~3,309 bytes). |
| FROST (0x08) | — | — | **RESERVED** for future RFC. MUST NOT be used. |

### §17.3 Replay Protection

Replay attacks are mitigated by three mechanisms:

1. **Version watermarks (§12)** — Each verifier tracks the highest accepted version per `(entryType, subject, scope)`. Replayed entries have a version ≤ the watermark and are rejected.

2. **Liveness freshness (§10)** — LIVENESS entries include `asOf` and `validUntil` timestamps. Stale entries are rejected even if the version is novel (e.g., from a restored backup).

3. **FENCE counters (§11)** — FENCE entries use monotonic counters with compare-and-swap semantics. Replaying an old FENCE entry fails the CAS check at the broker.

### §17.4 Key Compromise Recovery

If an instance's private key is compromised:

1. The operator MUST immediately revoke the corresponding `TrustEntry` via TAAS (§15.6).
2. TAAS publishes TRUST_REVOCATION entries to all relevant scopes.
3. Verifiers receiving the revocation invalidate cached trust entries and reject future entries from the compromised subject.
4. The operator SHOULD register a new instance with a fresh keypair.

The window of vulnerability is bounded by:

- **Revocation propagation delay** — Time for TRUST_REVOCATION entries to reach all verifiers. Bounded by broker delivery latency + reconciliation interval.
- **Cache TTL** — Stale L1/L2 cache entries may continue to resolve the compromised key for up to `TRUST_CACHE_TTL_SECONDS`.

To minimize this window, implementations SHOULD use short cache TTLs (≤ 300 s) and trigger immediate cache invalidation upon receiving TRUST_REVOCATION entries.

### §17.5 Cryptographic Agility

Veridot V5 supports multiple signature algorithms (§6) and provides a hybrid signature mode (§6.2) for post-quantum transition. The HYBRID_SIG flag (bit 1) allows an entry to carry both a classical signature (e.g., Ed25519) and a post-quantum signature (e.g., ML-DSA-65). Verifiers that support the post-quantum algorithm verify both; verifiers that do not MAY fall back to verifying only the classical signature.

This design enables a gradual migration path:

1. **Phase 1** — All entries use classical algorithms only.
2. **Phase 2** — Signers begin producing hybrid signatures. Verifiers that support PQ algorithms verify both; others verify only the classical component.
3. **Phase 3** — Once all verifiers support PQ algorithms, the classical component MAY be dropped (by defining a new algorithm code for standalone ML-DSA).

> **Note:** Phase 3 requires a future specification update. Veridot V5 does not define standalone post-quantum algorithm codes.

### §17.6 Concurrency Model

The following table specifies the thread-safety guarantees and mechanisms for all shared mutable state in a Veridot V5 implementation:

| Operation | Thread Safety | Mechanism |
|---|---|---|
| `sign()` | Thread-safe | Per-group `ReentrantLock` with reference counting. Only one thread signs for a given groupId at a time. |
| `verify()` | Thread-safe | Read-only except for watermark update, which uses atomic `ConcurrentHashMap.compute()`. |
| `revoke()` | Thread-safe | Acquires the per-group lock, publishes LIVENESS(REVOKED), stops renewal loop. |
| Watermark read | Lock-free | `ConcurrentHashMap.get()` |
| Watermark write | Atomic | `ConcurrentHashMap.compute()` with monotonic guard |
| Capability cache | Lock-free reads | `ConcurrentHashMap` with atomic `compute()` for writes |
| Liveness renewal | Non-blocking | `ScheduledExecutorService` with per-entry `ScheduledFuture` |
| Reconciliation | Non-blocking | `ScheduledExecutorService` with per-scope scheduling |

Implementations in languages other than Java MUST provide equivalent guarantees using language-appropriate primitives (e.g., `sync.Map` in Go, `RwLock<HashMap>` in Rust, `concurrent.futures` in Python).

The per-group `ReentrantLock` for `sign()` prevents two threads from simultaneously incrementing the version counter for the same group, which could produce duplicate versions or version gaps. The lock is scoped per `groupId` to avoid contention between independent groups.

### §17.7 Known Limitations and Mitigations

| Limitation | Description | Mitigation |
|---|---|---|
| **DoS via capacity exhaustion** | An attacker with a valid CAPABILITY can create `max` sessions, forcing legitimate instances into eviction or rejection (V5302). | Deploy per-subject session quotas at the broker or TAAS level. The protocol itself does not enforce per-subject limits — this is a deployment concern. |
| **Timing side-channel on watermarks** | `watermark.accept()` execution time may vary depending on whether the key exists. | Watermarks are not secrets. The information leakage (whether a watermark exists for a given entry) is equivalent to knowing whether the entry has been verified before — public information. |
| **Key material zeroing in JVM** | Java does not guarantee memory zeroing. The GC may copy `PrivateKey` objects, and `PrivateKey.destroy()` is not supported by all JCA providers. | Use `byte[]` for sensitive material and zero it manually in a `finally` block. Prefer `EdDSAPrivateKey` implementations that support `destroy()`. For highest assurance, use HSM-backed keys via PKCS#11. |
| **Clock synchronization** | Version watermarks and LIVENESS freshness depend on wall-clock time. Clock skew > `MAX_CLOCK_DRIFT_SECONDS` may cause false rejections. | Deploy NTP or PTP. The default `MAX_CLOCK_DRIFT_SECONDS` = 300 (5 minutes) accommodates typical cloud environments. |

---

# Appendices

---

## Appendix A: Identifiers Grammar (ABNF — RFC 5234)

The following grammar defines all identifier formats used in Veridot V5. The grammar conforms to RFC 5234 (Augmented BNF for Syntax Specifications).

```abnf
; ==========================================================
; Veridot V5 Identifier Grammar
; RFC 5234 ABNF
; ==========================================================

; ----------------------------------------------------------
; Core character classes
; ----------------------------------------------------------

identifier-char  = %x21-39 / %x3B-FF
                 ; All octets except NUL (%x00), controls (%x01-1F),
                 ; space (%x20), and colon (%x3A)

base64url-char   = ALPHA / DIGIT / "-" / "_"
                 ; RFC 4648 Section 5, no padding ("=" omitted)

; ----------------------------------------------------------
; Identifiers
; ----------------------------------------------------------

identifier       = 1*125identifier-char

; ----------------------------------------------------------
; Scopes
; ----------------------------------------------------------

scope            = "global" / group-scope / site-scope
group-scope      = "group:" identifier
site-scope       = "site:" identifier

; ----------------------------------------------------------
; Subjects
; ----------------------------------------------------------

subject          = cn-part "@" hash-part
cn-part          = 1*92identifier-char
                 ; max 92 chars so that total subject <= 125
hash-part        = 32base64url-char
                 ; base64url(SHA-256(publicKey))[0:32]

; ----------------------------------------------------------
; Message IDs
; ----------------------------------------------------------

message-id       = "3:" group-id ":" sequence-id
group-id         = identifier
sequence-id      = identifier

; ----------------------------------------------------------
; Tokens
; ----------------------------------------------------------

direct-token     = jwt-token
native-token     = "8:" scope ":" key
private-token    = "7:" scope ":" key
key              = identifier

jwt-token        = 1*base64url-char "." 1*base64url-char "." 1*base64url-char
                 ; Three base64url-encoded segments separated by "."
                 ; (header.payload.signature per RFC 7519)

; ----------------------------------------------------------
; Reserved sequences
; ----------------------------------------------------------

reserved-seq     = "__" identifier "__"
                 ; Double-underscore-wrapped identifiers are reserved
                 ; for protocol-internal use. Implementations MUST NOT
                 ; use reserved sequences in user-facing identifiers.
```

> **Note:** The constraint that `len(cn-part) + 1 + 32 <= 125` means `cn-part` can be at most 92 characters. This is a prose rule that cannot be expressed in pure ABNF, since ABNF repeat operators (`1*92`) specify *repetition counts*, not *byte lengths*. For single-byte characters in the `identifier-char` range, the two are equivalent.

---

## Appendix B: Test Vectors

Each conformance test vector MUST include:

1. **Signing keypair** — Private key and public key in hex-encoded DER format.
2. **Subject** — Computed from CN + public key per the subject format: `CN@base64url(SHA-256(pk))[0:32]`.
3. **Raw envelope bytes** — The complete binary envelope in hex encoding.
4. **Canonical signing bytes** — The byte sequence that is input to the signature algorithm, in hex encoding.
5. **Signature** — The signature value in hex encoding.
6. **Expected parse result** — All parsed fields with their types and values.
7. **Expected verification result** — `ACCEPT` or `REJECT` with the specific error code.

Test vectors SHALL be provided for the following cases:

### B.1 Positive Cases (Algorithm × EntryType)

| Algorithm | Entry Types |
|---|---|
| Ed25519 (0x01) | CAPABILITY, LIVENESS, SIGNED_DATA |
| ECDSA-P256 (0x02) | CAPABILITY, LIVENESS, SIGNED_DATA |
| RSA-PSS (0x04) | CAPABILITY, LIVENESS, SIGNED_DATA |

### B.2 Hybrid Cases

| Classical | Post-Quantum | Entry Type |
|---|---|---|
| Ed25519 (0x01) | ML-DSA-65 (0x10) | SIGNED_DATA (with HYBRID_SIG flag set) |

### B.3 Negative Cases

| Case | Expected Error |
|---|---|
| Version = 0 | V5301 `VERSION_REJECTED` |
| Trailing bytes after envelope | V5209 `TRAILING_BYTES` |
| COMPACT_SIG flag set but wrong signature length | V5212 `COMPACT_SIG_MISMATCH` |
| Entry type = 0x01 (reserved) | V5206 `RESERVED_ENTRY_TYPE` |
| Singleton scope entry with non-empty key | V5211 `INVALID_SCOPE_FORMAT` |
| Duplicate TLV tag in payload | V5208 `DUPLICATE_TLV_TAG` |
| Corrupted signature (bit flip) | V5102 `SIGNATURE_INVALID` |
| Expired LIVENESS entry | V5204 `LIVENESS_EXPIRED` |
| Subject format violation (missing `@`) | V5210 `INVALID_SUBJECT_FORMAT` |

Test vectors will be published in a companion document: **`test-vectors-v5.json`**.

---

## Appendix C: Protocol Registries

This appendix defines registries for all extensible protocol elements. Implementations MUST reject values in reserved ranges unless a future specification assigns them.

### C.1 Entry Type Registry

| Range | Allocation Policy |
|---|---|
| 0x01–0x3F | Standards Track (this specification and future RFCs) |
| 0x40–0x7F | Reserved for future standards |
| 0x80–0xFE | Experimental / Private Use |
| 0xFF | Reserved (MUST NOT be used) |
| 0x00 | Reserved (MUST NOT be used) |

Currently assigned entry types:

| Code | Name | Specification |
|---|---|---|
| 0x02 | CAPABILITY | §5.1 |
| 0x03 | CONFIG | §5.2 |
| 0x04 | LIVENESS | §5.3 |
| 0x05 | FENCE | §5.4 |
| 0x06 | SNAPSHOT | §5.5 |
| 0x07 | SECURE_PAYLOAD | §14 |
| 0x08 | SIGNED_DATA | §5.6 |
| 0x09 | AUDIT_ANCHOR | §5.7 |
| 0x0A | TRUST_REVOCATION | §5.8 |

### C.2 Signature Algorithm Registry

| Range | Allocation Policy |
|---|---|
| 0x01–0x1F | Standards Track |
| 0x20–0x7F | Reserved for future standards |
| 0x80–0xFE | Experimental / Private Use |
| 0x00, 0xFF | Reserved (MUST NOT be used) |

Currently assigned algorithms:

| Code | Name | Specification |
|---|---|---|
| 0x01 | Ed25519 | §6.1, RFC 8032 |
| 0x02 | ECDSA-P256 | §6.1, FIPS 186-5 |
| 0x04 | RSA-PSS-SHA256 | §6.1 |
| 0x08 | FROST | **RESERVED** for future RFC |
| 0x10 | ML-DSA-65 | §6.1, FIPS 204 |

### C.3 TLV Tag Cross-Reference

The following table lists all TLV tags across all payload types. Tags are scoped per entry type; the same tag value in different entry types denotes unrelated fields.

| Tag | CAPABILITY | CONFIG | LIVENESS | FENCE | SNAPSHOT | SECURE_PAYLOAD | SIGNED_DATA | AUDIT_ANCHOR | TRUST_REVOCATION |
|---|---|---|---|---|---|---|---|---|---|
| 0x01 | subjectSid | max | status | fenceCounter | snapshotAt | encAlg | data | merkleRoot | revokedSubject |
| 0x02 | scopePatterns | pol | asOf | grantedTo | entryCount | nonce | contentType | treeSize | revokedAt |
| 0x03 | maxDelegationDepth | dttl | validUntil | validUntil | — | recipients | validUntil | anchoredAt | reason |
| 0x04 | validUntil | name | — | — | — | data | groupId | — | — |
| 0x05 | subjectPattern | description | — | — | — | payloadType | sequenceId | — | — |
| 0x06 | — | validity | — | — | — | — | — | — | — |
| 0x07 | — | maxInstanceLifetime | — | — | — | — | — | — | — |
| 0x08 | — | attestationPlugin | — | — | — | — | — | — | — |

> **Note:** Tags are scoped per entry type. Tag 0x01 in CAPABILITY (`subjectSid`) is unrelated to tag 0x01 in CONFIG (`max`). There is no cross-type tag collision concern. This table is provided solely as an implementation aid to detect accidental reuse when extending payload types.

### C.4 Flags Bit Registry

| Bit | Name | Specification |
|---|---|---|
| 0 | `COMPACT_SIG` | §3.3 |
| 1 | `HYBRID_SIG` | §3.3, §6.2 |
| 2 | `DETACHED_PAYLOAD` | §3.3 |
| 3 | `INSTANCE_SCOPED` | §3.3, §5.1 |
| 4–15 | *(reserved)* | MUST be zero on write; MUST be ignored on read. |

Implementations MUST reject envelopes where any reserved flag bit (4–15) is set to 1, unless a future specification assigns meaning to that bit. This ensures forward-compatible evolution: old implementations fail loudly on new flags rather than silently ignoring them.

### C.5 Error Code Registry

The complete set of error codes is defined in §16. The table is reproduced here for single-point reference.

| Code | Name | Section |
|---|---|---|
| V5101 | `TRUST_RESOLUTION_FAILED` | §16.1 |
| V5102 | `SIGNATURE_INVALID` | §16.1 |
| V5103 | `DUPLICATE_SUBJECT` | §16.1 |
| V5104 | `TRUST_ENTRY_REVOKED` | §16.1 |
| V5105 | `TRUST_ENTRY_EXPIRED` | §16.1 |
| V5106 | `ATTESTATION_FAILED` | §16.1 |
| V5107 | `PROOF_OF_POSSESSION_INVALID` | §16.1 |
| V5108 | `ALGORITHM_MISMATCH` | §16.1 |
| V5109 | `ALGORITHM_RESERVED` | §16.1 |
| V5201 | `MALFORMED_ENVELOPE` | §16.2 |
| V5202 | `WRONG_ENTRY_TYPE` | §16.2 |
| V5203 | `LIVENESS_STALE` | §16.2 |
| V5204 | `LIVENESS_EXPIRED` | §16.2 |
| V5205 | `UNKNOWN_ENTRY_TYPE` | §16.2 |
| V5206 | `RESERVED_ENTRY_TYPE` | §16.2 |
| V5207 | `TLV_PARSE_ERROR` | §16.2 |
| V5208 | `DUPLICATE_TLV_TAG` | §16.2 |
| V5209 | `TRAILING_BYTES` | §16.2 |
| V5210 | `INVALID_SUBJECT_FORMAT` | §16.2 |
| V5211 | `INVALID_SCOPE_FORMAT` | §16.2 |
| V5212 | `COMPACT_SIG_MISMATCH` | §16.2 |
| V5301 | `VERSION_REJECTED` | §16.3 |
| V5302 | `CAPACITY_EXCEEDED` | §16.3 |
| V5303 | `CAPACITY_CONTENTION` | §16.3 |
| V5304 | `WATERMARK_RECOVERY_IN_PROGRESS` | §16.3 |
| V5401 | `DELEGATION_DEPTH_EXCEEDED` | §16.4 |
| V5402 | `CIRCULAR_DELEGATION` | §16.4 |
| V5403 | `NO_CAPABILITY` | §16.4 |
| V5404 | `OPERATION_DENIED` | §16.4 |
| V5405 | `SCOPE_MISMATCH` | §16.4 |
| V5406 | `CAPABILITY_EXPIRED` | §16.4 |
| V5501 | `DECRYPTION_FAILED` | §16.5 |
| V5502 | `NOT_A_RECIPIENT` | §16.5 |
| V5503 | `KEY_VERSION_NOT_FOUND` | §16.5 |

### C.6 Eviction Policy Registry

| Code | Name | Specification |
|---|---|---|
| 0x01 | FIFO | §11.2 |
| 0x02 | LIFO | §11.2 |
| 0x03 | LRU | §11.2 |
| 0x04 | REJECT | §11.2 |
| 0x05–0xFF | *(reserved)* | — |

---

## Appendix D: Metrics Registry

Implementations SHOULD expose the following metrics using a Prometheus-compatible format. Metric names use the `veridot_` prefix to avoid collisions with other instrumentation.

| Metric Name | Type | Unit | Labels | Description |
|---|---|---|---|---|
| `veridot_envelopes_accepted_total` | Counter | — | `scope`, `entry_type` | Envelopes that passed full verification pipeline. |
| `veridot_envelopes_rejected_total` | Counter | — | `scope`, `entry_type`, `error_code` | Envelopes that failed verification, labeled by error code. |
| `veridot_reconciliations_total` | Counter | — | `scope` | Completed reconciliation cycles. |
| `veridot_liveness_renewals_total` | Counter | — | `scope` | Liveness renewal publications (both successful and failed). |
| `veridot_capability_cache_hits_total` | Counter | — | `cache_level` (`positive`, `negative`) | Capability cache hits, by cache level. |
| `veridot_capability_cache_misses_total` | Counter | — | — | Capability cache misses requiring chain walk. |
| `veridot_trust_resolution_duration_seconds` | Histogram | seconds | `source` (`l1`, `l2`, `remote`) | TrustRoot resolution latency, by cache tier. |
| `veridot_attestation_verifications_total` | Counter | — | `plugin`, `result` (`success`, `failure`) | Attestation verification outcomes, by plugin and result. |
| `veridot_security_alerts_total` | Counter | — | `reason` | Security alerts generated (e.g., Byzantine leader detection, key compromise). |

### D.1 Label Cardinality Guidelines

Implementations MUST bound label cardinality to prevent metric explosion:

- `scope` — Bound by the number of scopes the instance participates in (typically < 100).
- `entry_type` — Bound by the entry type registry (currently 9 assigned types).
- `error_code` — Bound by the error code registry (currently 30 codes).
- `plugin` — Bound by the attestation plugin registry (currently 5 plugins).

Implementations MUST NOT use unbounded values (e.g., subject identifiers, message IDs) as metric labels.

---

## Appendix E: Normative References

| Reference | Title |
|---|---|
| [RFC 2119] | Bradner, S., "Key words for use in RFCs to Indicate Requirement Levels", BCP 14, RFC 2119, March 1997. |
| [RFC 4648] | Josefsson, S., "The Base16, Base32, and Base64 Data Encodings", RFC 4648, October 2006. |
| [RFC 5234] | Crocker, D., Ed. and P. Overell, "Augmented BNF for Syntax Specifications: ABNF", STD 68, RFC 5234, January 2008. |
| [RFC 5869] | Krawczyk, H. and P. Eronen, "HMAC-based Extract-and-Expand Key Derivation Function (HKDF)", RFC 5869, May 2010. |
| [RFC 7519] | Jones, M., Bradley, J., and N. Sakimura, "JSON Web Token (JWT)", RFC 7519, May 2015. |
| [RFC 7748] | Langley, A., Hamburg, M., and S. Turner, "Elliptic Curves for Security", RFC 7748, January 2016. |
| [RFC 8032] | Josefsson, S. and I. Liusvaara, "Edwards-Curve Digital Signature Algorithm (EdDSA)", RFC 8032, January 2017. |
| [FIPS 180-4] | National Institute of Standards and Technology, "Secure Hash Standard (SHS)", FIPS PUB 180-4, August 2015. |
| [FIPS 186-5] | National Institute of Standards and Technology, "Digital Signature Standard (DSS)", FIPS PUB 186-5, February 2023. |
| [FIPS 197] | National Institute of Standards and Technology, "Advanced Encryption Standard (AES)", FIPS PUB 197, November 2001. |
| [FIPS 204] | National Institute of Standards and Technology, "Module-Lattice-Based Digital Signature Standard", FIPS PUB 204, August 2024. |
| [NIST SP 800-38D] | Dworkin, M., "Recommendation for Block Cipher Modes of Operation: Galois/Counter Mode (GCM) and GMAC", NIST SP 800-38D, November 2007. |
| [NIST SP 800-56A] | Barker, E., Chen, L., Roginsky, A., Vassilev, A., and R. Davis, "Recommendation for Pair-Wise Key-Establishment Schemes Using Discrete Logarithm Cryptography", NIST SP 800-56A Rev. 3, April 2018. |

---

## Appendix F: Informative References

| Reference | Title |
|---|---|
| [Raft] | Ongaro, D. and Ousterhout, J., "In Search of an Understandable Consensus Algorithm", USENIX ATC 2014. |
| [RocksDB] | Facebook, "RocksDB: A Persistent Key-Value Store for Fast Storage Environments", https://rocksdb.org/. |
| [FROST] | Komlo, C. and Goldberg, I., "FROST: Flexible Round-Optimized Schnorr Threshold Signatures", SAC 2020. |
| [RFC 6979] | Pornin, T., "Deterministic Usage of the Digital Signature Algorithm (DSA) and Elliptic Curve Digital Signature Algorithm (ECDSA)", RFC 6979, August 2013. |

---

## Appendix G: Configuration Constants

The following table lists all configurable constants, their environment variable overrides, default values, and valid ranges.

| Constant | Environment Variable | Default | Valid Range | Description |
|---|---|---|---|---|
| `MAX_DELEGATION_DEPTH` | `VDOT_MAX_DELEGATION_DEPTH` | 10 | 1–255 | Maximum capability delegation chain depth. |
| `MAX_LIVENESS_AGE_SECONDS` | `VDOT_MAX_LIVENESS_AGE_SECONDS` | 120 | ≥ 10 | Maximum age (in seconds) of a LIVENESS `asOf` timestamp before it is considered stale. |
| `LIVENESS_VALIDITY_SECONDS` | `VDOT_LIVENESS_VALIDITY_SECONDS` | 300 | ≥ 60 | Duration (in seconds) for which a LIVENESS entry is valid after publication. |
| `LIVENESS_RENEWAL_RATIO` | `VDOT_LIVENESS_RENEWAL_RATIO` | 0.80 | 0.50–0.95 | Fraction of `LIVENESS_VALIDITY_SECONDS` at which renewal is triggered. |
| `FENCE_VALIDITY_SECONDS` | `VDOT_FENCE_VALIDITY_SECONDS` | 600 | ≥ 60 | Duration (in seconds) for which a FENCE entry is valid. |
| `RECONCILIATION_INTERVAL_SECONDS` | `VDOT_RECONCILIATION_INTERVAL_SECONDS` | 60 | ≥ 10 | Interval (in seconds) between periodic reconciliation cycles. |
| `SNAPSHOT_INTERVAL_ENTRIES` | `VDOT_SNAPSHOT_INTERVAL_ENTRIES` | 10000 | ≥ 100 | Number of entries between automatic SNAPSHOT publications. |
| `TRUST_CACHE_TTL_SECONDS` | `VDOT_TRUST_CACHE_TTL_SECONDS` | 300 | ≥ 0 | Time-to-live (in seconds) for L1 trust cache entries. 0 disables caching. |
| `CAPABILITY_CACHE_TTL_SECONDS` | `VDOT_CAPABILITY_CACHE_TTL_SECONDS` | 300 | ≥ 0 | Time-to-live (in seconds) for positive capability cache entries. |
| `CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS` | `VDOT_CAPABILITY_NEG_CACHE_TTL_SECONDS` | 30 | 0–60 | Time-to-live (in seconds) for negative capability cache entries. |
| `MAX_CLOCK_DRIFT_SECONDS` | `VDOT_MAX_CLOCK_DRIFT_SECONDS` | 300 | ≥ 0 | Maximum tolerated clock skew (in seconds) between instances. |
| `CAPACITY_MAX_RETRIES` | `VDOT_CAPACITY_MAX_RETRIES` | 3 | 1–10 | Maximum retries for capacity slot acquisition. |
| `CAPACITY_BASE_BACKOFF_MS` | `VDOT_CAPACITY_BASE_BACKOFF_MS` | 50 | 10–1000 | Base backoff (in milliseconds) for capacity contention retries. |
| `MAX_ENVELOPE_SIZE_BYTES` | `VDOT_MAX_ENVELOPE_SIZE_BYTES` | 1048576 | ≥ 4096 | Maximum envelope size (in bytes). Envelopes exceeding this size MUST be rejected. |
| `TRUST_CACHE_SYNC_HOURS` | `VDOT_TRUST_CACHE_SYNC_HOURS` | 6 | ≥ 1 | Periodic sync interval (in hours) for L2 cache refresh from TAAS. |
| `TRUST_STALE_WINDOW_SECONDS` | `VDOT_TRUST_STALE_WINDOW_SECONDS` | 3600 | ≥ 0 | Duration (in seconds) after `notAfter` during which stale entries are still served from cache. Allows in-flight verifications to complete after key rotation or expiry. |

Implementations MUST use the default values if the corresponding environment variable is not set. Implementations MUST validate that configured values fall within the valid range and MUST reject out-of-range values at startup with a clear error message.

---

## Appendix H: Document History

| Version | Date | Author | Changes |
|---|---|---|---|
| 5.0.0-draft | 2026-07-10 | Frank KOSSI | Initial draft. Core envelope format, signature algorithms, entry types, trust system, capability delegation, liveness, capacity management, E2EE, reconciliation, and security considerations. |
| 5.0.0-draft-2 | 2026-07-11 | Frank KOSSI | RFC-compliance audit fixes: added rationale blocks for all design decisions; eliminated INDIRECT authentication mode (only DIRECT/JWT, NATIVE, PRIVATE remain); replaced `recipientKeyVersion` with `recipientTrustVersion` in RecipientBlock (§14.3); marked FROST (0x08) as RESERVED for future RFC; defined `TrustIdentity` with 3 fields (`publicKey`, `isRoot`, `algorithm`); fixed subject format to `CN@base64url(SHA-256(pk))[0:32]`; added ABNF grammar (Appendix A); added protocol registries (Appendix C); added metrics registry (Appendix D); added normative and informative references (Appendices E, F); added configuration constants table (Appendix G); added document history (Appendix H); added concurrency model (§17.6); added known limitations and mitigations (§17.7); eliminated all references to KEY_EPOCH. |

---

*End of Veridot V5 Protocol Specification — Sections 9–17 & Appendices*
