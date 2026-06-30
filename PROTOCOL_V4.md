# Veridot Protocol Specification — Version 4

```
Title:        Veridot Protocol v4 — Distributed Token Verification
Version:      4.0
Status:       Standards Track
Author:       Frank Cyrille KOSSI KOSSI
Created:      2026-06-28
```

## Status of This Memo

This document specifies the Veridot Protocol version 4 (V4), a
self-describing binary message format for distributing cryptographic
verification metadata across nodes. It defines the wire format,
semantics, state model, and processing rules that all conforming
implementations MUST follow.

## Abstract

The Veridot Protocol provides a binary, self-describing message format
enabling distributed verification of signed objects (JWTs, API keys,
documents, etc.) without shared secrets. It separates cryptographic
key distribution from business logic, supports hierarchical
configuration under cryptographic authorization, monotonic state
resolution, positive-proof liveness attestation, and fenced session
capacity management. The protocol is designed so that any node
holding write access to the broker, but lacking the corresponding
long-term private key material, is structurally incapable of producing
a state transition accepted by a conforming processor.

---

## 1. Introduction

### 1.1 Purpose

The Veridot Protocol defines a transport-agnostic binary format for
distributing public-key verification metadata, authorization
capabilities, liveness attestations, and capacity-fencing tokens. It
enables any node with read access to the broker to independently
verify signed objects produced by any other node, and to independently
determine the current authoritative state of a session, a
configuration scope, or a capacity quota — without trusting the broker
itself.

### 1.2 Scope

This specification covers:

- The canonical binary envelope format and the entry-type registry
- Key epoch distribution and ephemeral key verification
- Capability-based authorization for configuration scopes
- Hierarchical configuration and its resolution rules
- Liveness attestation and revocation semantics
- Session capacity management and fenced eviction
- The state consistency model (monotonicity, idempotence,
  reconciliation)
- Implementation and broker requirements

This specification does NOT cover:

- Transport-layer implementation details (Kafka, SQL, or otherwise)
- Application-level business rules unrelated to session verification
- Signed object formats (JWT structure, API key encoding, etc.)
- Root-of-trust key management and provisioning procedures

### 1.3 Design Principles

- **Deny by default**: Any entry that is malformed, unauthorized,
  stale, or for which authoritative state cannot be positively
  established MUST be rejected. Absence of information MUST NOT be
  interpreted as a permissive state.
- **Structural authorization**: Authorization to act on a scope MUST
  be established by a verifiable cryptographic capability, never by an
  implementation-defined callback or default.
- **Monotonic state**: For any given scope and entry type, state MUST
  only move forward. No operation defined by this protocol permits a
  conforming processor to regress to an earlier known state.
- **Positive liveness proof**: A session or scope is considered valid
  only when a fresh, signed, positive attestation of validity is held
  by the verifying processor. Expiration, absence, or invalidity of
  such an attestation MUST produce the same outcome: rejection.
- **Uniform envelope**: All information exchanged through the broker,
  regardless of purpose, uses one canonical signed envelope and one
  verification pipeline. No entry type may bypass cryptographic
  verification through an alternate, lighter-weight read path.
- **Availability over consistency for non-authoritative reads**: A
  processor reading a single key from the broker MAY be eventually
  consistent. Authoritative decisions (revocation, capacity fencing,
  configuration authorization) MUST NOT rely on single-read
  consistency; they rely on monotonicity (§11) and periodic
  reconciliation (§11.4) instead.

---

## 2. Terminology

### 2.1 Key Words

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT",
"SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this
document are to be interpreted as described in [RFC 2119].

### 2.2 Definitions

**Broker**
:   A transport and storage component providing entry persistence,
    retrieval by key, and full-scope enumeration (§13.2). The broker
    is responsible for delivery and durability; it is NOT a trusted
    component and holds no authority over the validity of any entry
    it stores or transmits.

**Processor**
:   A software component implementing this protocol. A processor
    performs issuance (creating entries) and/or verification (reading
    and validating entries). A single application MAY contain
    multiple processors.

**TrustRoot**
:   An out-of-band trust store resolving a long-term identifier
    (`issuer`) to a long-term public key, independent of the broker.
    The TrustRoot is the sole source of cryptographic trust in the
    system.

**Scope**
:   A typed, hierarchical namespace an entry applies to. A scope is
    one of `group:<groupId>`, `site:<siteId>`, or `global`. A scope
    identifies the set of sessions or configuration targets an entry
    governs.

**Group** (groupId)
:   A logical namespace aggregating related sessions, typically
    mapping to a business entity such as a user account, a service
    instance, an API client, or a device.

**Session** (key)
:   A single verification context within a group, identified by a
    `key` value unique within that group's `KEY_EPOCH` entries. A
    session is active if and only if a fresh, valid `LIVENESS` entry
    with status `ACTIVE` exists for it (§8).

**Site** (siteId)
:   A logical partition enabling shared configuration across multiple
    groups. A group declares site membership through its `KEY_EPOCH`
    payload (§5.2).

**Entry**
:   A single signed unit of protocol state, conforming to the
    canonical envelope (§3) and belonging to one registered entry type
    (§4).

**EntryId**
:   The tuple `(scope, entryType, key)` uniquely identifying an entry's
    logical position. The broker storage key is derived deterministically
    from the EntryId (§3.3).

**Version**
:   A 64-bit unsigned integer carried by every entry, strictly
    increasing per EntryId, independent of wall-clock time. Versions
    establish the total order used for monotonic state resolution
    (§11).

**Epoch**
:   A time-bounded validity window for a piece of cryptographic
    material or an attestation, delimited by `validFrom`/`validUntil`
    fields carried in the entry payload.

**Capability**
:   A signed grant authorizing a specific issuer identity to act
    (publish configuration or fencing entries) within one or more
    scope patterns (§6).

**Liveness Attestation**
:   A signed, positive statement that a session or scope is in a
    given status (`ACTIVE` or `REVOKED`) as of a given time, valid
    until a given expiry (§8).

**Fence Token**
:   A signed, monotonically increasing counter scoped to a single
    `(scope)`, used to totally order capacity-affecting mutations
    across concurrent processors (§9).

**Snapshot**
:   A complete, point-in-time enumeration of all entries for a given
    scope, used for periodic state reconciliation (§11.4).

---

## 3. Wire Format

### 3.1 Envelope Structure

Every Veridot V4 entry is encoded as a single binary envelope:

| Field | Size | Type | Description |
|---|---|---|---|
| `magic` | 2 bytes | fixed | `0x56 0x44` (`"VD"`) — protocol marker |
| `protoVersion` | 1 byte | u8 | MUST be `0x04` |
| `entryType` | 1 byte | u8 | One of the registered entry types (§4) |
| `flags` | 1 byte | bitfield | bit 0: `COMPACT_SIG` — MUST be `1` if and only if `sigAlg = 0x04` (Ed25519), and `0` if `sigAlg = 0x01` (RSA-SHA256) or `sigAlg = 0x03` (RSA-PSS); a mismatch between `flags` bit 0 and `sigAlg` MUST result in rejection with `V4005`; bits 1–7: reserved, MUST be zero |
| `scopeLen` | 2 bytes | u16 | Length in bytes of `scope` |
| `scope` | variable | UTF-8 | Typed scope identifier (§3.5) |
| `keyLen` | 2 bytes | u16 | Length in bytes of `key` |
| `key` | variable | UTF-8 | Entry key within scope (§3.5); zero-length permitted for entry types that are singletons per scope (§4) |
| `version` | 8 bytes | u64, big-endian | Monotonic version (§11.1) |
| `timestamp` | 8 bytes | i64, big-endian | Issuer's wall-clock time, milliseconds since epoch; advisory only — MUST NOT be used in place of `version` for ordering decisions |
| `issuerLen` | 2 bytes | u16 | Length in bytes of `issuer` |
| `issuer` | variable | UTF-8 | Long-term identifier resolved by the TrustRoot |
| `payloadLen` | 4 bytes | u32, big-endian | Length in bytes of `payload` |
| `payload` | variable | binary TLV | Entry-type-specific fields (§5–§9) |
| `sigAlg` | 1 byte | u8 | `0x01` = RSA-SHA256, `0x02` = ECDSA-SHA256, `0x03` = RSA-PSS, `0x04` = Ed25519 |
| `sigLen` | 2 bytes | u16 | Length in bytes of `signature` |
| `signature` | variable | binary | Signature over the canonical bytes (§3.4) |

All multi-byte integers are big-endian. There is no implicit padding
or alignment between fields.

### 3.2 Field Constraints

- `magic` and `protoVersion` MUST be validated before any other field
  is interpreted. A mismatch MUST result in immediate rejection with
  error `V4001` (Appendix B), without attempting to parse the
  remainder of the envelope.
- `entryType` MUST be one of the values registered in §4. An
  unregistered value MUST result in rejection with `V4002`.
- `scopeLen` MUST be in the range `1–4096`. `keyLen` MUST be in the range
  `0–4096`. `issuerLen` MUST be in the range `1–4096`. Values outside these ranges MUST result in rejection with `V4003`.
- `payloadLen` MUST be in the range `0–65536`. Values outside this
  range MUST result in rejection with `V4004`.
- `flags` bits 1–7, if set, MUST cause rejection with `V4005`
  (forward-compatibility is handled at the major-version level, not
  through silently ignored flag bits).

### 3.3 EntryId and Broker Storage Key

The EntryId is the triple `(scope, entryType, key)`. The broker
storage key is computed as:

```
storageKey = scope || 0x00 || entryType || 0x00 || key
```

where `0x00` is a single NUL byte separator. Because `scope` and `key`
are length-prefixed UTF-8 strings validated against §3.5 (which
excludes NUL), this construction is unambiguous and injective.

### 3.4 Canonical Signing Bytes

The `signature` field covers every byte of the envelope preceding
`sigAlg`, in encoded order, including `magic`, `protoVersion`,
`entryType`, `flags`, `scopeLen`, `scope`, `keyLen`, `key`, `version`,
`timestamp`, `issuerLen`, `issuer`, `payloadLen`, and `payload`. No
field is excluded from the signed region, and no field is signed in
isolation from the others. This eliminates any possibility of
relocating a valid signature to a different scope, key, version, or
payload than the one it was produced for.

### 3.5 Identifier Constraints

`scope` and `key` MUST satisfy:

- **Character set**: any UTF-8 codepoint except `0x00` (NUL) and
  ASCII control characters `0x01`–`0x1F`.
- **Length**: `scope` 1–4096 bytes; `key` 0–4096 bytes (zero permitted
  per §4 for singleton entry types).
- **Scope grammar**: `scope` MUST match one of:
  - `"group:" 1*125(identifier-char)`
  - `"site:" 1*125(identifier-char)`
  - `"global"`

  where `identifier-char` excludes `:` in addition to the constraints
  above. A scope not matching this grammar MUST be rejected with
  `V4006`.

---

## 4. Entry Type Registry

| Code | Name | Singleton per scope | Specification |
|---|---|:---:|:---:|
| `0x01` | `KEY_EPOCH` | No (one per session `key`) | §5 |
| `0x02` | `CAPABILITY` | No (one per `issuer`/grant) | §6 |
| `0x03` | `CONFIG` | Yes (`key` MUST be empty) | §7 |
| `0x04` | `LIVENESS` | Yes per session (`key` = session key) | §8 |
| `0x05` | `FENCE` | Yes (`key` MUST be empty) | §9 |
| `0x06` | `SNAPSHOT_MARKER` | Yes (`key` MUST be empty) | §11.4 |
| `0x07` | `SECURE_PAYLOAD` | No (one per target `key`) | §12 |

Any new entry type MUST be fully specified in this document (payload
layout, validation rules, semantics) before assignment of a new code.
Codes `0x08`–`0xFF` are unassigned and reserved for future
specification; a processor receiving an unassigned code MUST reject
the entry with `V4002` rather than ignore it. Unlike metadata fields
within an existing payload (which support forward-compatible
extension, see §5.2), entry types are closed for extension without a
specification update — this is a deliberate restriction preventing
undocumented, unauthenticated semantics from entering the protocol
surface.

### 4.1 TLV Payload Encoding

The `payload` field of every entry type is encoded as a contiguous sequence of
TLV (Tag–Length–Value) fields. Each TLV field has the following structure:

| Sub-field | Size | Type | Description |
|---|---|---|---|
| `tag` | 1 byte | u8 | Field identifier, entry-type-specific; `0x00` is reserved and MUST NOT appear |
| `len` | 2 bytes | u16, big-endian | Length in bytes of `value` |
| `value` | variable | binary | Field value, big-endian for numeric types, UTF-8 for strings |

Processing rules:

- A `tag` value of `0x00` MUST result in rejection with `V4007`.
- All REQUIRED fields for the entry type MUST be present; a missing REQUIRED field
  MUST result in rejection with `V4007`.
- OPTIONAL fields MAY be absent; their absence is equivalent to applying the
  documented default.
- A `tag` not recognized by the processor for the current entry type MUST be
  silently ignored (forward compatibility). This applies only to payload fields,
  not to entry types themselves (§4).
- A `tag` appearing more than once within the same payload MUST result in
  rejection with `V4007`.
- All fixed-width numeric `value` fields (u8, u16, u32, u64, i64) are big-endian.
- String `value` fields are UTF-8 encoded without a null terminator.
- List-of-string fields are encoded as a concatenation of `(u16 length ‖ UTF-8 bytes)`
  pairs; the outer `len` covers the entire serialized list.

The `FieldTag` values for each entry type are defined in their respective sections
(§5.2, §6.2, §7.3, §8.2, §9.2, §11.5). Tags `0xF0`–`0xFF` within any entry type
are reserved for future extension and MUST be ignored if unknown.

---

## 5. Key Epoch Entries

### 5.1 Purpose

A `KEY_EPOCH` entry distributes the cryptographic elements required to
verify a signed object: the ephemeral public key, its algorithm, and
its temporal validity window. A `KEY_EPOCH` entry is NOT responsible
for the business-level expiration of the signed object itself (e.g., a
JWT `exp` claim), for authorization, or for any application-specific
validation logic.

### 5.2 Payload Fields

The `payload` of a `KEY_EPOCH` entry is a TLV sequence of the
following fields (encoding per §4.1):

| FieldTag | Field | Type | Required | Description |
|---|---|---|:---:|---|
| `0x01` | `alg` | enum(u8) | REQUIRED | `0x01` = RSA-SHA256, `0x02` = ECDSA-SHA256, `0x03` = RSA-PSS, `0x04` = Ed25519 |
| `0x02` | `epochId` | u64 | REQUIRED | Monotonic identifier of this key epoch, scoped to `(scope, key)` |
| `0x03` | `pk` | bytes | REQUIRED | Ephemeral public key, DER-encoded |
| `0x04` | `validFrom` | i64 | REQUIRED | Epoch validity start, milliseconds since epoch |
| `0x05` | `validUntil` | i64 | REQUIRED | Epoch validity end, milliseconds since epoch |
| `0x06` | `site` | string | OPTIONAL | Site identifier for configuration inheritance (§7.2); valid only when `scope` starts with `group:` |

Unknown `FieldTag` values within a `KEY_EPOCH` payload MUST be ignored by a
conforming processor (forward compatibility per §4.1); unknown entry types
(§4) MUST NOT be ignored.

### 5.3 Temporal Validity

A `KEY_EPOCH` entry is **active** if and only if:

1. `now ≥ validFrom - 300000` (five-minute clock-drift tolerance,
   milliseconds).
2. `now < validUntil`.
3. No `LIVENESS` entry with status `REVOKED` and a higher or equal
   `version` exists for the same `(scope, key)` (§8.4).

### 5.4 Verification Process

A conforming processor verifying a signed object MUST, in order:

1. **Extract** the complete EntryId `(scope, KEY_EPOCH, key)` referenced
   by the signed object.
2. **Retrieve** the corresponding entry from the broker.
3. **Structural validation**: parse the envelope per §3; reject on any
   violation with the corresponding error from Appendix B.
4. **Trust validation**: resolve `issuer` through the TrustRoot and
   verify `signature` over the canonical bytes (§3.4). Resolution
   failure or signature failure MUST result in rejection with `V4101`.
5. **Capability validation**: confirm the `issuer` holds a valid,
   unexpired `CAPABILITY` entry whose `scopePatterns` cover `scope`
   (§6.4). Absence of such a capability MUST result in rejection with
   `V4102`.
6. **Temporal validation**: confirm the entry is active per §5.3.
7. **Liveness validation**: confirm a fresh `ACTIVE` liveness
   attestation exists for `(scope, key)` per §8.3.
8. **Cryptographic validation**: use `pk` and `alg` to verify the
   signed object's own signature.
9. **Business validation**: the application applies its own rules
   (JWT expiration, claims, permissions, etc.), strictly after and
   independently of steps 1–8.

Steps 4 through 7 MUST each independently produce rejection on
failure; none of them may be skipped, reordered to occur after step 8,
or replaced by an alternate, non-equivalent check.

---

## 6. Capability Entries

### 6.1 Purpose

A `CAPABILITY` entry is a signed grant establishing that a given
`issuer` identity is authorized to publish entries of the types
`KEY_EPOCH`, `LIVENESS`, `CONFIG`, and `FENCE` within one or more scope
patterns. Authorization is never established by application-supplied
logic; it is established exclusively by the existence of a valid,
unexpired, properly-chained `CAPABILITY` entry, or by root-identity
status per §6.5.

### 6.2 Payload Fields

The `key` field of a `CAPABILITY` entry's envelope MUST be set to the
UTF-8 encoding of `subjectSid`. This ensures the broker storage key
(§3.3) is unique per `(scope, subjectSid)` pair, allowing direct
retrieval of all capabilities granted to a given identity within a
scope.

The `payload` is a TLV sequence of the following fields (encoding per §4.1):

| FieldTag | Field | Type | Required | Description |
|---|---|---|:---:|---|
| `0x01` | `subjectSid` | string | REQUIRED | Identifier of the identity being granted the capability |
| `0x02` | `scopePatterns` | list of strings | REQUIRED | One or more scope patterns this capability authorizes (§6.3); MUST contain at least one entry; encoded as a concatenation of `(u16 length ‖ UTF-8 bytes)` pairs |
| `0x03` | `maxDelegationDepth` | u8 | REQUIRED | Maximum number of further delegation hops permitted from this grant; `0` = no further delegation |
| `0x04` | `validUntil` | i64 | REQUIRED | Expiry, milliseconds since epoch |

### 6.3 Scope Pattern Matching

A scope pattern is a scope string (§3.5) with an optional trailing
`*` wildcard matching any suffix. A pattern `group:42:*` matches
`group:42` and any scope formed by appending further path segments to
it under future extensions of the scope grammar; in the present
version, since `group:<groupId>` carries no sub-path, `group:42:*`
matches `group:42` exactly. Patterns MUST NOT use `*` other than as a
single trailing character.

### 6.4 Verification Process

A `CAPABILITY` entry authorizes `subjectSid` for `scope` if and only
if:

1. The `CAPABILITY` entry itself passes structural and trust
   validation (§3.4, TrustRoot resolution of its own `issuer`).
2. `now < validUntil`.
3. `scope` matches at least one pattern in `scopePatterns` (§6.3).
4. The issuing chain terminates at the TrustRoot within
   `maxDelegationDepth` hops. A capability issued directly by an
   identity resolvable in the TrustRoot has depth `0`; a capability
   issued by `subjectSid` of another, still-valid capability has depth
   `n+1`, bounded by the granting capability's own
   `maxDelegationDepth`.

There is no default-authorized scope and no fallback grant. Absence
of a valid `CAPABILITY` entry for the required scope and identity MUST
result in rejection of the dependent entry, with no exception. For
the bootstrap case (first capability issued in a deployment), see §6.5.

### 6.5 Bootstrap and Root Authorization

An identity directly and successfully resolvable in the TrustRoot is
called a **root identity**. A root identity:

- Is unconditionally authorized to publish `CAPABILITY` entries for any
  scope, without itself holding a prior `CAPABILITY` entry.
- Does NOT require a `CAPABILITY` entry to issue `KEY_EPOCH`, `LIVENESS`,
  `CONFIG`, or `FENCE` entries for any scope.
- Is treated as having delegation depth `0` for the purposes of §6.4
  condition 4.

All non-root identities MUST derive their authorization from a `CAPABILITY`
entry issued — directly or transitively within `maxDelegationDepth` hops
— by a root identity. A processor MUST NOT grant authorization to any
identity that cannot establish this chain terminating at a root identity.

---

## 7. Configuration Entries

### 7.1 Hierarchy

Configuration applies at exactly one of three scope levels, expressed
through the `scope` field itself rather than through a reserved key
pattern:

| Scope | Precedence | Applies to |
|---|:---:|---|
| `group:<groupId>` | 1 (highest) | The specific group only |
| `site:<siteId>` | 2 | All groups declaring membership in this site (§5.2 `site` field) |
| `global` | 3 (lowest) | All groups across all sites |

A `CONFIG` entry is a singleton per scope: `key` MUST be empty. A
processor holding multiple `CONFIG` entries for the same scope MUST
retain only the one with the highest `version` (§11.1); this is a
direct consequence of monotonic resolution, not a separate
conflict-resolution rule.

### 7.2 Resolution Rules

1. Group-level configuration overrides site-level configuration for
   groups declaring membership in that site.
2. Site-level configuration overrides global configuration.
3. Global configuration overrides implementation defaults.
4. A scope level absent a valid `CONFIG` entry contributes no
   override; resolution falls through to the next-lower-precedence
   level.

### 7.3 Payload Fields

| FieldTag | Field | Type | Required | Default | Description |
|---|---|---|:---:|---|---|
| `0x01` | `max` | u32 | OPTIONAL | unbounded | Maximum active sessions per group |
| `0x02` | `pol` | enum(u8) | OPTIONAL | `0x01` (FIFO) | Eviction policy (§10.2): `0x01`=FIFO, `0x02`=LIFO, `0x03`=LRU, `0x04`=REJECT; meaningful only when `max` is set |
| `0x03` | `dttl` | u64 | OPTIONAL | — | Default key-epoch validity duration, milliseconds |
| `0x04` | `name` | string | OPTIONAL | — | Descriptive name |
| `0x05` | `description` | string | OPTIONAL | — | Description |

### 7.4 Authorization Requirement

A `CONFIG` entry MUST be rejected unless its `issuer` holds a valid
`CAPABILITY` entry covering its `scope`, verified per §6.4. There is
no implementation-level override of this requirement; it is a
structural precondition of acceptance, evaluated identically by every
conforming processor.

### 7.5 Validation

A `CONFIG` entry is valid if and only if it satisfies §3 (envelope),
§6.4 (capability authorization), and all fields in §7.3 conform to
their declared types and ranges. An invalid `CONFIG` entry MUST be
rejected outright; it MUST NOT be partially applied, and the
previously resolved configuration for that scope (if any) remains in
effect.

---

## 8. Liveness Entries

### 8.1 Purpose

A `LIVENESS` entry is a signed, positive statement of a session's
current status. The absence of a `LIVENESS` entry, an expired one, or
one that fails verification NEVER constitutes evidence that a session
is valid. Validity is established exclusively by the presence of a
fresh, positively verified `ACTIVE` attestation.

### 8.2 Payload Fields

| FieldTag | Field | Type | Required | Description |
|---|---|---|:---:|---|
| `0x01` | `status` | enum(u8) | REQUIRED | `0x01` = `ACTIVE`, `0x02` = `REVOKED` |
| `0x02` | `asOf` | i64 | REQUIRED | Time the issuing authority asserts this status, milliseconds since epoch |
| `0x03` | `validUntil` | i64 | REQUIRED | Expiry of this attestation's freshness window, milliseconds since epoch |

### 8.3 Default-Deny Semantics

A session identified by `(scope, key)` is considered valid for
verification purposes if and only if all of the following hold:

1. A `LIVENESS` entry exists for `(scope, key)` whose `version` is the
   highest among all entries the verifying processor has observed for
   that EntryId (§11.1).
2. That entry passes structural and trust validation (§3.4).
3. `status = ACTIVE`.
4. `now < validUntil`.

Any failure of conditions 1–4 — including broker unavailability,
network failure, signature failure, or simple absence of any entry —
MUST produce the same outcome: the session is treated as **not
valid**. A conforming processor MUST NOT distinguish "no attestation
found" from "attestation invalid" for the purpose of deciding the
default outcome; both produce rejection.

### 8.4 Monotonicity and Conflict Resolution

For a given `(scope, key)`, an incoming `LIVENESS` entry is accepted
only if its `version` is strictly greater than the highest `version`
previously accepted for that EntryId (§11.1). An entry with an equal
or lower `version` — regardless of its `status`, its `asOf` value, or
the validity of its own signature — MUST be discarded without
altering the processor's recorded state. This is what prevents a
later entry, however it was produced, from reverting a previously
established `REVOKED` status back to `ACTIVE`.

### 8.5 Renewal

Because validity additionally requires `now < validUntil` (§8.3,
condition 4), an issuing authority MUST periodically publish renewed
`ACTIVE` attestations (each with a strictly increasing `version`) for
every session it continues to consider valid. A session for which
renewal has lapsed becomes unverifiable, indistinguishably from a
revoked one, until a new attestation is published.

An issuing authority SHOULD publish a renewed `ACTIVE` attestation no
later than `validUntil − 0.2 × (validUntil − asOf)` milliseconds after
`asOf` — i.e., within the last 20% of the attestation's validity window.
This margin accommodates propagation latency and transient broker
unavailability. A processor MAY cache a positively verified attestation
for the full duration of its `validUntil` window without re-querying the
broker on every verification call.

### 8.6 Authorization Requirement

A `LIVENESS` entry MUST be rejected unless its `issuer` holds a valid
`CAPABILITY` entry whose `scopePatterns` cover the `LIVENESS` entry's
`scope`, verified per §6.4 (or the `issuer` is a root identity per §6.5).
This requirement applies identically to `ACTIVE` and `REVOKED`
attestations; the authorization check is evaluated before the
`status` field is interpreted.

---

## 9. Fence Entries

### 9.1 Purpose

A `FENCE` entry totally orders capacity-affecting mutations (session
creation under quota, eviction) within a single scope across
concurrent processors, without requiring those processors to share a
synchronous lock.

### 9.2 Payload Fields

| FieldTag | Field | Type | Required | Description |
|---|---|---|:---:|---|
| `0x01` | `fenceCounter` | u64 | REQUIRED | Strictly increasing per scope |
| `0x02` | `grantedTo` | string | REQUIRED | Identifier of the processor instance this counter value was granted to |
| `0x03` | `validUntil` | i64 | REQUIRED | Expiry of the grant, milliseconds since epoch |

`FENCE` is a singleton entry type per scope: `key` MUST be empty.

### 9.3 Authority Election

Exactly one processor instance acts as the fencing authority for a
given scope at any time. Election of this authority is
implementation-defined (e.g., transport-level partition affinity
keyed by scope, or a dedicated sequence row under exclusive lock) and
is explicitly outside the normative scope of this protocol (§1.2).
What this protocol specifies is the entry format and the acceptance
rule (§9.4) that make the result of that election verifiable and
enforceable by any processor, independent of how the election itself
was performed.

### 9.4 Acceptance Rule

A capacity-affecting mutation (publication of a new `KEY_EPOCH` entry
under quota, or a quota-driven `LIVENESS` revocation, §10.2) MUST be
accompanied by a `FENCE` entry whose `fenceCounter` is strictly
greater than the highest `fenceCounter` previously accepted for that
scope. A mutation presented without such a `FENCE` entry, or with a
stale one, MUST be rejected with `V4301`, independent of whether a
genuine race actually occurred between two processors.

Ordering and failure handling: the `FENCE` entry MUST be durably
stored by the broker before the accompanying mutation entry is
submitted. If the mutation fails after the `FENCE` entry has been
committed, the consumed `fenceCounter` value MUST NOT be reused; the
processor MUST obtain a new FENCE grant with a strictly greater
`fenceCounter` before retrying the mutation.

---

## 10. Session Capacity Management

### 10.1 Counting

The number of active sessions for a group is derived strictly from
`LIVENESS` entries satisfying §8.3, never from a local, unverified
counter and never from `KEY_EPOCH` presence alone. A `KEY_EPOCH` entry
for which no corresponding `ACTIVE` `LIVENESS` entry can be verified
MUST NOT be counted as an active session, regardless of what raw data
a broker read returns.

### 10.2 Eviction Trigger

Eviction is evaluated by the processor holding the current `FENCE`
grant (§9.3) for the target scope, at the time a new session is
requested:

1. The processor establishes the active session count per §10.1.
2. If `count ≥ max` (from the resolved `CONFIG`, §7):
   - If `pol = REJECT` (`0x04`): the processor MUST refuse to create the new
     session and MUST NOT publish any `LIVENESS` revocation.
   - If `pol = FIFO` (`0x01`): the session with the lowest `asOf` value in
     its most recent `LIVENESS(ACTIVE)` entry is selected for eviction.
   - If `pol = LIFO` (`0x02`): the session with the highest `asOf` value in
     its most recent `LIVENESS(ACTIVE)` entry is selected for eviction.
   - If `pol = LRU` (`0x03`): the session whose most recent `LIVENESS(ACTIVE)`
     entry carries the lowest `asOf` value among all active sessions counted
     per §10.1 is selected for eviction. For the purposes of this rule,
     "most recent" means the `LIVENESS(ACTIVE)` entry with the highest
     accepted `version` for that session's `(scope, key)`. An issuing
     authority SHOULD update `asOf` to reflect the most recent verification
     event when it publishes renewal attestations, enabling accurate LRU
     tracking across distributed processors.
   - For `FIFO`, `LIFO`, and `LRU`: the processor MUST publish a `LIVENESS`
     entry with `status = REVOKED` for the selected session, with a `version`
     strictly greater than that session's last known `version`, accompanied by
     a valid `FENCE` entry (§9.4), **before** publishing the new session's
     `KEY_EPOCH` and initial `ACTIVE` `LIVENESS` entries.
3. If `max` is not defined in the resolved configuration, no capacity
   check is performed.

### 10.3 Distributed Consistency

Because every capacity-affecting mutation requires a valid `FENCE`
entry per §9.4, two processors concurrently attempting to mutate the
same scope's capacity cannot both succeed for the same logical
admission slot: exactly one holds the next valid `fenceCounter`, and
the other's attempt is rejected and MUST be retried against the
then-current counter value. This holds regardless of the transport's
own consistency model; the protocol's ordering guarantee does not
depend on the broker providing strong consistency.

---

## 11. State Consistency Model

### 11.1 Monotonic Version Invariant

For every EntryId, a conforming processor maintains the highest
`version` it has accepted. An incoming entry for that EntryId is
accepted only if its `version` is strictly greater than the currently
recorded value. This rule is evaluated **before** any semantic
interpretation of the entry's `payload`, and applies uniformly across
all entry types (§4).

The initial recorded version for an EntryId for which no entry has been
previously accepted is defined as zero (`0`). Accordingly, the minimum
valid `version` for any entry that can be accepted is `1`. An entry
carrying `version = 0` MUST be rejected with `V4201` regardless of
any other field.

### 11.2 Idempotent Application

Applying an already-accepted entry a second time MUST have no
additional effect beyond the first application. A conforming
implementation MUST treat application as an upsert gated by §11.1,
never as an unconditional merge of payload fields.

### 11.3 Non-Rollback Invariant

No operation defined by this protocol permits the highest recorded
`version` for an EntryId to decrease. This invariant holds
independent of broker behavior: even if the broker's stored value for
an EntryId is overwritten, replaced, corrupted, or deleted, a
processor's locally recorded `version` watermark for that EntryId MUST
NOT regress as a result of any subsequent read.

### 11.4 Reconciliation via Snapshot

A processor SHOULD periodically retrieve a full `snapshot` (§13.2) of
each scope it actively verifies against, and reconcile its locally
recorded version watermarks against the highest `version` observed in
the snapshot for each EntryId, applying §11.1–§11.3 identically to
snapshot-sourced entries as to incrementally-delivered ones.
Reconciliation closes the gap that may arise when an individual entry
delivery is lost in transit; it is a recommended operational safeguard
and does not itself relax any acceptance rule defined elsewhere in
this document.

A conforming processor SHOULD reconcile each actively monitored scope
at intervals no greater than the minimum `validUntil − now` across all
held `LIVENESS(ACTIVE)` entries for that scope, and in any case at
least once every 60 minutes per scope. An implementation MAY apply a
smaller interval to reduce staleness.

### 11.5 Snapshot Marker Entries

A `SNAPSHOT_MARKER` entry is a signed record that a complete,
consistent point-in-time enumeration of a scope was performed and
committed to the broker. It allows reconciling processors to bound
their staleness window and validate the completeness of a snapshot.

`SNAPSHOT_MARKER` is a singleton per scope: `key` MUST be empty.

The `payload` is a TLV sequence of the following fields (encoding per
§4.1):

| FieldTag | Field | Type | Required | Description |
|---|---|---|:---:|---|
| `0x01` | `snapshotAt` | i64 | REQUIRED | Wall-clock time the snapshot enumeration was initiated, milliseconds since epoch |
| `0x02` | `entryCount` | u32 | REQUIRED | Number of distinct EntryIds captured in this scope snapshot |

A `SNAPSHOT_MARKER` MUST be published by the snapshotting processor
immediately after all entries in the scope have been committed to the
broker, with a `version` strictly greater than any previously accepted
`SNAPSHOT_MARKER` for the same scope. Processors performing
reconciliation per §11.4 SHOULD use the `snapshotAt` and `entryCount`
fields to detect incomplete or superseded snapshots.

---

## 12. Secure Payload Entries

### 12.1 Purpose

A `SECURE_PAYLOAD` entry transports an application-level object (payload) directly through the broker. To maintain zero-trust security and prevent unauthorized access by the broker or other scopes, the payload data can be end-to-end encrypted (E2EE) using hybrid encryption for one or more specific recipients. If no recipients are specified, the payload data is transported in plaintext (public broadcast within the scope).

### 12.2 Payload Fields

The `payload` of a `SECURE_PAYLOAD` entry is a TLV sequence of the following fields (encoding per §4.1):

| FieldTag | Field | Type | Required | Description |
|---|---|---|:---:|---|
| `0x01` | `encAlg` | enum(u8) | OPTIONAL | Encryption algorithm: `0x01` = AES-256-GCM, `0x02` = ChaCha20-Poly1305. REQUIRED if `recipients` is present. |
| `0x02` | `nonce` | bytes | OPTIONAL | Initialization vector / nonce for symmetric encryption. REQUIRED if `recipients` is present. |
| `0x03` | `recipients` | bytes | OPTIONAL | Concatenation of one or more `RecipientBlock`s (§12.3). If absent, the payload is in plaintext. |
| `0x04` | `data` | bytes | REQUIRED | The payload data. Encrypted (ciphertext) if `recipients` is present; plaintext if `recipients` is absent. |
| `0x05` | `payloadType` | string | OPTIONAL | MIME type or format identifier of the decrypted data (e.g., `"application/json"`). |

Unknown `FieldTag` values within a `SECURE_PAYLOAD` payload MUST be ignored by a conforming processor (forward compatibility).

### 12.3 Recipient Block Structure

When `recipients` (Tag `0x03`) is present, it contains a contiguous concatenation of `RecipientBlock` structures. Each `RecipientBlock` is defined as:

| Sub-field | Size | Type | Description |
|---|---|---|---|
| `recipientSidLen` | 2 bytes | u16, big-endian | Length in bytes of `recipientSid` |
| `recipientSid` | variable | UTF-8 | Identity of the authorized recipient (`subjectSid`) |
| `recipientKeyEpochId`| 8 bytes | u64, big-endian | `epochId` of the recipient's `KEY_EPOCH` used for encryption |
| `encryptedKeyLen` | 2 bytes | u16, big-endian | Length in bytes of `encryptedKey` |
| `encryptedKey` | variable | bytes | Symmetric session key encrypted with the recipient's public key |

### 12.4 Processing Rules

A processor receiving a `SECURE_PAYLOAD` entry MUST, in order:

1. **Verify Envelope**: Perform envelope and trust validation per §3 and §5.4.
2. **Authorization**: Verify that the `issuer` is authorized to publish entries in the target `scope` (§6.4).
3. **Decryption** (if `recipients` is present):
   - Locate the `RecipientBlock` where `recipientSid` matches the processor's own `subjectSid`. If no such block exists, reject or ignore the entry (access denied).
   - Resolve the recipient's private key for the epoch specified by `recipientKeyEpochId`.
   - Decrypt `encryptedKey` using the private key to recover the symmetric session key.
   - Decrypt the `data` field using the symmetric key, `nonce`, and the algorithm specified in `encAlg`.
4. **Delivery**: Pass the decrypted (or plaintext) `data` and `payloadType` to the application layer.

---

## 13. Implementation Requirements

### 13.1 Processor Responsibilities

A processor MUST:

- Validate envelope structure per §3 before interpreting any payload
  field.
- Resolve `issuer` through the TrustRoot and verify `signature` for
  every entry, of every type, before any other use of its contents.
- Enforce §11.1 (monotonic version invariant) uniformly across all
  entry types, with no exception for entry types deemed
  "informational."
- Apply §8.3's default-deny semantics for all liveness determinations;
  a processor MUST NOT implement an alternate code path that treats
  absence of a `LIVENESS` entry as `ACTIVE`.
- Apply §6.4's capability check for every `CONFIG` and `FENCE` entry;
  a processor MUST NOT expose a configuration option that disables
  this check.
- Log every rejection with its corresponding error code (Appendix B)
  and the EntryId involved.

### 13.2 Broker Requirements

The broker MUST provide:

| Capability | Description |
|---|---|
| **Put** | Accept a byte sequence for storage under a derived storage key (§3.3), rejecting it at write time if it does not parse as a structurally valid envelope per §3 (magic, protoVersion, registered entryType, length-field consistency). The broker performs no trust or capability validation; it enforces envelope well-formedness only. |
| **Get** | Retrieve the most recently stored bytes for a given storage key. This read MAY be eventually consistent. |
| **Snapshot** | Enumerate, for a given scope, every distinct EntryId and its most recently stored bytes, as of a single consistent point in time as observed by the broker. Used for reconciliation (§11.4). |

A broker implementation that cannot guarantee envelope well-formedness
at write time MUST reject the **Put** operation outright rather than
accept and store non-conforming bytes.

### 13.3 Consistency Properties

#### 13.3.1 Local Consistency

- **Atomicity**: acceptance of an entry and the corresponding update
  of the local version watermark (§11.1) MUST be atomic from the
  point of view of any concurrent verification on the same processor.
- **Durability**: a processor SHOULD persist its version watermarks
  across restarts; absence of persisted watermarks MUST be treated as
  "no entry previously accepted," which, combined with §8.3, defaults
  to rejection rather than to acceptance.
- **Integrity**: the persisted watermark snapshot MUST be cryptographically
  protected (e.g., signed or HMAC'ed using a key derived from the processor's
  long-term private key) to prevent local tampering and rollback attacks. If
  the integrity check fails, the snapshot MUST be discarded and the system
  MUST trigger full reconciliation.

#### 13.3.2 Distributed Consistency

- **Eventual** for broker reads (§13.2 Get).
- **Strongly ordered** for capacity-affecting mutations, by
  construction of §9 and §10.3, independent of the broker's own
  consistency model.
- **Reconciled** periodically via §11.4, bounding the duration for
  which a missed delivery can remain undetected.

### 13.4 Error Handling

| Category | Examples |
|---|---|
| Envelope errors | Magic mismatch, unregistered entry type, length-field violations |
| Trust errors | TrustRoot resolution failure, signature verification failure |
| Authorization errors | Missing or expired capability for a `CONFIG`/`FENCE` entry |
| Ordering errors | Stale `version`, stale `fenceCounter` |
| Liveness errors | No fresh `ACTIVE` attestation available |
| Transport errors | Broker unavailability, network failure |

A transport error MUST be classified separately from a definitive
rejection in logs and metrics, but MUST NOT receive different
treatment in the verification outcome: both result in rejection per
§8.3.

### 13.5 Observability

A conforming implementation SHOULD expose:

- Count of entries accepted/rejected, by entry type and error code.
- Distribution of version-watermark staleness relative to the most
  recent snapshot reconciliation.
- Count of `FENCE` grant contentions (rejected mutations due to a
  stale `fenceCounter`).
- Active session count per group, as derived per §10.1.

---

## 14. Security Considerations

### 14.1 Cryptographic Validation

- Signatures MUST be validated according to `sigAlg`; a mismatch
  between `sigAlg` and the key type resolved for `issuer` MUST result
  in rejection.
- All cryptographic signature verifications (both envelope signatures and
  application JWT signatures) MUST be performed using timing-safe operations
  (such as constant-time digest comparison or mathematically timing-safe
  signature schemes like Ed25519) to prevent side-channel timing attacks.
- Implementations SHOULD prefer Ed25519 (`sigAlg = 0x04`) for all long-term
  and ephemeral keys, as recommended by NIST SP 800-186, because Ed25519
  verification is mathematically constant-time and immune to timing attacks.
- Verifiers MUST extract the header of the application JWT and verify that
  the `alg` attribute matches the expected JWT algorithm derived from the
  KEY_EPOCH's `alg` property (e.g., `RS256` for `0x01`, `ES256` for `0x02`,
  `PS256` for `0x03`). Any mismatch MUST result in token rejection to prevent
  algorithm confusion attacks.
- Timestamps (`timestamp`, `asOf`, `validFrom`, `validUntil`) are
  advisory for human-readable auditing and freshness windows; they
  MUST NOT be used as the basis for ordering or conflict-resolution
  decisions, which rely exclusively on `version` (§11.1).
- Clock drift tolerance for temporal-window checks (§5.3, §8.3) is
  fixed at five minutes.

### 14.2 Threat Mitigation

| Threat | Mitigation |
|---|---|
| Broker injection of forged key material | TrustRoot resolution + mandatory signature verification (§3.4, §5.4) |
| Forged or unauthorized configuration change | Mandatory capability verification, no default grant (§6.4, §7.4) |
| State rollback via overwrite of stored bytes | Monotonic version invariant, enforced independent of broker content (§11) |
| Silent treatment of revoked sessions as valid | Positive-proof liveness model; absence or invalidity defaults to rejection (§8.3) |
| Race between concurrent processors on capacity mutation | Mandatory fence token with strict ordering (§9.4, §10.3) |
| Replay of a stale entry | Monotonic version invariant (§11.1); `timestamp` plays no role in this defense |
| Undetected loss of an individual entry in transit | Periodic full-scope snapshot reconciliation (§11.4, §13.2) |
| Injection / malformed input | Strict envelope validation prior to any payload interpretation (§3.2) |

### 14.3 Audit and Traceability

- Every entry carries a deterministic EntryId and an `issuer`,
  providing non-repudiation for every state transition.
- Implementations MUST log rejections with their error code and
  EntryId.
- Log retention policy is a deployment concern, outside this
  specification's normative scope.

### 14.4 Broker Trust Model

The broker is transport and storage only. It is never granted
authority over the meaning of the bytes it stores or relays. A
processor with write access to the broker but without the long-term
private key material corresponding to a TrustRoot-resolvable `issuer`
is, by construction of §3.4 and §11.1, unable to produce an entry that
a conforming verifier will accept as a new, valid state for any
EntryId it does not already control.

Failure modes:

- **TrustRoot unavailable**: a processor MUST fail closed — reject
  pending verifications dependent on resolution through the TrustRoot.
  It MUST NOT fall back to accepting an entry without trust
  resolution.
- **Broker unavailable**: a processor MUST fail closed with respect
  to liveness determination (§8.3); it MUST NOT default to treating
  an unreachable session as active.

### 14.5 Residual Risk Disclosure

This protocol protects the integrity and ordering of state as
distributed through an untrusted broker. It does not, and cannot,
protect against:

- Compromise of a TrustRoot-resolvable long-term private key itself;
  key custody is outside this specification's scope.
- Unavailability of the fencing authority for a scope (§9.3) causing
  capacity-affecting mutations for that scope to stall; this is a
  deliberate consistency-over-availability trade-off for that
  specific class of operation, distinct from the
  availability-favoring default applied to ordinary reads (§1.3).
- Resource exhaustion from unbounded entry volume; deployments SHOULD
  apply rate limiting and storage quotas at the transport layer,
  outside this specification's normative scope.

---

## 15. Evolution and Compatibility

### 15.1 Protocol Versioning

- **Major version** (`protoVersion`): incompatible changes to the
  envelope structure or to the acceptance semantics of §8, §9, or §11.
- **Minor revision**: addition of new, optional payload fields within
  an existing entry type, ignored by processors that do not recognize
  them (§5.2).
- **Entry type extension**: requires a specification update and
  registration in §4; entry types are closed for silent extension
  (§4).

### 15.2 Extensibility

- New optional payload fields MAY be added to an existing entry type's
  TLV payload without breaking conforming implementations.
- New entry types MUST be fully specified and registered in §4 before
  use; an unregistered entry type MUST be rejected, not ignored.
- New scope kinds (beyond `group:`, `site:`, `global`) require a
  specification update to §3.5 and §6.3.

### 15.3 Coexistence

Multiple major protocol versions MAY coexist on the same transport,
distinguished unambiguously by `magic` and `protoVersion` at the start
of every envelope. A processor encountering an envelope of an
unsupported `protoVersion` MUST reject it with `V4001` rather than
attempt best-effort interpretation.

---

## Appendix A. Binary Grammar (Formal)

```
Envelope        := Magic ProtoVersion EntryType Flags
                    ScopeLen Scope KeyLen Key
                    Version Timestamp
                    IssuerLen Issuer
                    PayloadLen Payload
                    SigAlg SigLen Signature

Magic           := 0x56 0x44
ProtoVersion    := 0x04
EntryType       := 0x01..0x07               ; see §4 registry
Flags           := 1*OCTET                  ; bit 0 defined, bits 1-7 MUST be zero
ScopeLen        := 2*OCTET                  ; u16, big-endian
Scope           := ScopeGrammar              ; see below, length = ScopeLen
KeyLen          := 2*OCTET                  ; u16, big-endian
Key             := *identifier-char          ; length = KeyLen
Version         := 8*OCTET                  ; u64, big-endian
Timestamp       := 8*OCTET                  ; i64, big-endian
IssuerLen       := 2*OCTET                  ; u16, big-endian
Issuer          := *identifier-char          ; length = IssuerLen
PayloadLen      := 4*OCTET                  ; u32, big-endian
Payload         := *TLVField                ; entry-type-specific, see §5-§9
SigAlg          := 0x01 / 0x02 / 0x03 / 0x04
SigLen          := 2*OCTET                  ; u16, big-endian
Signature       := *OCTET                   ; length = SigLen

TLVField        := FieldTag FieldLen FieldValue
FieldTag        := 1*OCTET
FieldLen        := 2*OCTET                  ; u16, big-endian
FieldValue      := *OCTET                   ; length = FieldLen

ScopeGrammar    := "group:" identifier
                  / "site:" identifier
                  / "global"
identifier      := 1*125(identifier-char)
identifier-char := %x21-FF                   ; excludes NUL and C0 controls,
                                              ; further excludes ":" within
                                              ; identifier (see §3.5)
```

## Appendix B. Error Codes

| Code | Name | Description |
|---|---|---|
| `V4001` | `INVALID_ENVELOPE` | Magic or protocol version mismatch |
| `V4002` | `UNREGISTERED_ENTRY_TYPE` | `entryType` not present in the registry (§4) |
| `V4003` | `INVALID_IDENTIFIER_LENGTH` | `scope` or `key` length outside permitted bounds |
| `V4004` | `INVALID_PAYLOAD_LENGTH` | `payload` length outside permitted bounds |
| `V4005` | `RESERVED_FLAG_SET` | A reserved bit in `flags` was set, or `flags` bit 0 (`COMPACT_SIG`) is inconsistent with `sigAlg` |
| `V4006` | `INVALID_SCOPE_GRAMMAR` | `scope` does not match the grammar in §3.5 |
| `V4007` | `MALFORMED_PAYLOAD` | Payload TLV does not conform to the entry type's field table |
| `V4101` | `TRUST_RESOLUTION_FAILED` | `issuer` could not be resolved, or `signature` verification failed |
| `V4102` | `CAPABILITY_NOT_FOUND` | No valid `CAPABILITY` entry authorizes the `issuer` for `scope` |
| `V4103` | `CAPABILITY_EXPIRED` | A `CAPABILITY` entry was found but `now ≥ validUntil` |
| `V4104` | `DELEGATION_DEPTH_EXCEEDED` | Capability delegation chain exceeds `maxDelegationDepth` |
| `V4201` | `STALE_VERSION` | Incoming entry's `version` is not strictly greater than the recorded watermark, or `version = 0` |
| `V4202` | `LIVENESS_NOT_ESTABLISHED` | No fresh, valid `ACTIVE` `LIVENESS` entry available for the target session (§8.3) |
| `V4203` | `KEY_EPOCH_EXPIRED` | `now` outside `[validFrom, validUntil)` for the referenced `KEY_EPOCH` |
| `V4204` | `SIGALG_KEY_MISMATCH` | `sigAlg` value is unknown, or is inconsistent with the key type resolved for `issuer` via the TrustRoot |
| `V4205` | `DECRYPTION_FAILED` | Cryptographic decryption of `encryptedKey` or `data` failed |
| `V4301` | `FENCE_TOKEN_STALE` | `fenceCounter` not strictly greater than the recorded watermark for the scope |
| `V4302` | `CAPACITY_EXCEEDED` | `max` reached under `pol = REJECT` |
| `V4401` | `TRANSPORT_UNAVAILABLE` | Broker read or write failure; treated as rejection per §8.3/§14.4, logged separately per §13.4 |

## Appendix C. Normative References

- **[RFC 2119]** Bradner, S., "Key words for use in RFCs to Indicate
  Requirement Levels", BCP 14, RFC 2119, March 1997.
- **[RFC 5234]** Crocker, D., Ed. and P. Overell, "Augmented BNF for
  Syntax Specifications: ABNF", STD 68, RFC 5234, January 2008.
- **[FIPS 186-5]** National Institute of Standards and Technology,
  "Digital Signature Standard (DSS)", FIPS PUB 186-5, February 2023.
  (RSA and ECDSA signature schemes referenced in §5.2, §14.1.)
- **[RFC 8032]** Josefsson, S. and I. Liusvaara, "Edwards-Curve Digital
  Signature Algorithm (EdDSA)", RFC 8032, January 2017. (Ed25519
  signature scheme referenced in §3.1 `flags`, §14.1.)

---

*Veridot Protocol Specification — Version 4.0*
