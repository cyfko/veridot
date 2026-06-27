# Veridot Protocol Specification — Version 3

```
Title:        Veridot Protocol v3 — Distributed Token Verification
Version:      3.0
Status:       Standards Track
Author:       Frank Cyrille KOSSI KOSSI
Created:      2026-04-10
Last Revised: 2026-06-27
Changes:      v3.0 — Universal `sig` field for all message types (§4.2, §6.3, §5.4)
                     Config message signing via `sid` and `sig` (§5.4)
                     Universal canonical encoding (§11.5)
                     TrustAnchor security model (§11.4)
                     Shortened property names (`alg`, `pk`, `ts`, `sid`, `sig`, `max`, `pol`, `dttl`, `exp`)
```

## Status of This Memo

This document specifies the Veridot Protocol version 3 (V3), a canonical
message format for distributing cryptographic verification metadata
across nodes. It defines the syntax, semantics, and processing rules
that all conforming implementations MUST follow.

## Abstract

The Veridot Protocol provides a self-describing, binary-safe message
format enabling distributed verification of signed objects (JWTs, API
keys, documents, etc.) without shared secrets. It separates
cryptographic key distribution from business logic, supports hierarchical
configuration, structured revocation, and session capacity management.

---

## 1. Introduction

### 1.1 Purpose

The Veridot Protocol defines a lightweight, transport-agnostic format
for distributing public-key verification metadata. It enables any node
with access to the broker to independently verify signed objects
produced by any other node.

### 1.2 Scope

This specification covers:

- The canonical message format and its three message types
- Metadata encoding and property definitions
- Configuration hierarchy and resolution
- Revocation semantics
- Session capacity management and eviction policies
- Implementation requirements and broker capabilities

This specification does NOT cover:

- Transport-layer implementation details
- Application-level authorization or business rules
- Signed object formats (JWT structure, API key encoding, etc.)

### 1.3 Design Principles

- **Deny by default**: Any non-conforming or ambiguous message MUST be
  rejected.
- **Explicit authorization**: Only strictly conforming messages MUST be
  accepted.
- **Strict validation**: No tolerance for format deviations.
- **Availability over consistency**: In distributed deployments,
  availability is prioritized over strict consistency (§8.3).

## 2. Terminology

### 2.1 Key Words

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT",
"SHOULD", "SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this
document are to be interpreted as described in [RFC 2119].

### 2.2 Definitions

**Broker**
:   A transport and storage component that provides message persistence,
    retrieval by key, and prefix-based listing. The broker is responsible
    for delivery guarantees and distributed consistency.

**Processor**
:   A software component that implements this protocol. A processor
    performs signing (creating messages) and/or verification (reading
    and validating messages). A single application MAY contain multiple
    processors.

**Group** (groupId)
:   A logical namespace that aggregates related sessions. A group
    typically maps to a business entity such as a user account, a
    service instance, an API client, or a device. A group is identified
    by a `groupId` string conforming to §3.2.

**Sequence** (sequenceId)
:   A unique identifier for a single session within a group. Each
    sequence corresponds to one signing key pair and its associated
    metadata. A sequence is identified by a `sequenceId` string
    conforming to §3.2.

**Session**
:   An active, non-expired, non-revoked (groupId, sequenceId) pair
    stored in the broker. A session represents a currently valid
    verification context.

**Site** (siteId)
:   A logical partition within a deployment. Sites enable shared
    configuration across multiple groups (e.g., all groups in a
    microservice cluster). A group declares its site membership via the
    `site` metadata property. A group without a `site` property belongs
    to no site.

**Message**
:   A complete Veridot protocol unit consisting of a header (version,
    groupId, sequenceId) and metadata. There are three message types:
    normal (§4), configuration (§5), and revocation (§6).

**MessageId**
:   The unique key identifying a message in the broker, formatted as
    `3:<groupId>:<sequenceId>`. The messageId serves both as the broker
    storage key and as the reference linking a signed object to its
    verification metadata.

**Reserved Sequence**
:   A sequenceId matching the pattern `__<NAME>__` (double underscores).
    Reserved sequences carry protocol-level semantics and MUST NOT be
    used as regular session identifiers. See §7.

**Eviction**
:   The automatic revocation of an existing session to make room for a
    new one when the session capacity limit (`max`) is reached.
    Eviction is performed by the signing processor according to the
    configured policy.

**TTL** (Time To Live)
:   The duration in seconds for which a session's public key remains
    valid, starting from the session's `ts`. After `ts +
    ttl`, the session is considered expired.

---

## 3. Message Format

### 3.1 Canonical Form

All Veridot V3 messages conform to the following canonical form:

```
message = version ":" groupId ":" sequenceId "|" metadata
```

Example:

```
3:user123:session001|alg:cnNh,pk:TUlJQ...,ts:MTcwNjcxMjAwMA,ttl:MzYwMA
```

### 3.2 Identifier Constraints

GroupId and sequenceId MUST satisfy the following constraints:

- **Allowed characters**: Any printable Unicode character EXCEPT the
  protocol delimiters `:` (U+003A), `,` (U+002C), `|` (U+007C), and
  whitespace characters.
- **Length**: 1 to 125 UTF-8 characters.
- **Empty strings**: MUST NOT be empty.
- **Regex**: `[^:,|\s]{1,125}`

### 3.3 Version Field

- The version field MUST be the ASCII character `3`.
- A processor receiving a message with any other version MUST reject it.

### 3.4 Separators

| Position | Character | Name |
|----------|:---------:|------|
| Between version and groupId | `:` | Colon |
| Between groupId and sequenceId | `:` | Colon |
| Between header and metadata | `\|` | Pipe |
| Between metadata properties | `,` | Comma |
| Between property name and value | `:` | Colon |

No whitespace is permitted around separators.

### 3.5 MessageId

The messageId is the concatenation of version, groupId, and sequenceId:

```
messageId = "3:" groupId ":" sequenceId
```

The messageId serves as the broker key for storage and retrieval.

**Examples**:

- `3:user123:session001`
- `3:payment.service:tx_789abc`
- `3:192.168.1.1:conn_456`

---

## 4. Normal Messages

### 4.1 Purpose

A normal message distributes the cryptographic elements required to
verify a signed object. It provides the public key, the algorithm, and
the temporal validity window.

A normal message is NOT responsible for:

- The business-level expiration of the signed object (e.g., JWT `exp`
  claim)
- User permissions or roles
- Application-specific validation logic

### 4.2 Properties

| Property | Type | Required | Description |
|----------|------|:--------:|-------------|
| `alg` | string | REQUIRED | Signature algorithm (`rsa`, `ecdsa`) |
| `pk` | string | REQUIRED | Ephemeral public key (DER binary, Base64url-encoded) |
| `ts` | number | REQUIRED | Unix timestamp of creation (seconds) |
| `ttl` | number | OPTIONAL | Time to live in seconds |
| `site` | string | OPTIONAL | Site identifier for group membership |
| `token` | string | OPTIONAL | The signed object (used in indirect distribution mode) |
| `sid` | string | REQUIRED | Identifier of the signing service that produced this message |
| `sig` | bytes | REQUIRED | Long-term RSA signature over the canonical message bytes (§11.5) |

> The `sig` field is universal — it signs all metadata properties of the message
> (excluding `sig` itself and `token`) using the universal canonical encoding (§11.5).
> Processors MUST reject any normal message missing `sid` or `sig` when a `TrustAnchor`
> is configured. Processors without a `TrustAnchor` configured MAY treat these
> fields as OPTIONAL for backward compatibility, but SHOULD emit a warning.

> **Note on `token`**: When the signed object is not returned directly
> to the caller but stored in the broker, the `token` property carries
> the complete signed object. The verifier retrieves the token from the
> metadata instead of receiving it from the caller.

### 4.3 Temporal Validity

A normal message is considered **active** if and only if:

1. The message exists in the broker and is not empty.
2. `ts` does not exceed `now + 300` seconds (clock drift
   tolerance of ±5 minutes, see §9.1).
3. If `ttl` is present: `now < ts + ttl`.
4. If `ttl` is absent: the message does not expire (or a `dttl`
   from configuration applies).

### 4.4 Verification Process

1. **Retrieval**: The signed object references a `messageId`.
2. **Extraction**: Retrieve the message from the broker by `messageId`.
3. **Temporal validation**: Verify that the message is active (§4.3).
4. **Trust validation**: Resolve `sid` via the configured `TrustAnchor`
   and verify `sig` over the canonical message bytes (§11.5).
   If validation fails, the message MUST be rejected with error `V3011` or `V3012`.
5. **Cryptographic validation**: Use `pk` and `alg` to verify the
   object's signature.
6. **Business validation**: The application applies its own rules (JWT
   expiration, permissions, etc.).

The signed object MUST reference the complete `messageId`
(`3:<groupId>:<sequenceId>`). The mechanism for carrying this reference
is implementation-defined (e.g., JWT `sub` claim, HTTP header, document
field), but the identifier MUST be transmitted in full and unmodified.

### 4.5 Examples

#### JWT verification message (with trust fields)

```
3:user123:session001|alg:cnNh,pk:TUlJQ...,ts:MTcwNjcxMjAwMA,ttl:MzYwMA,sid:YXV0aC1zdmM,sig:U0hBMjU2d2l0aFJTQQ
```

- Public key valid for 1 hour (`ttl=3600`)
- `sid` = `"auth-svc"` — the signing service identifier
- `sig` — RSA signature verifiable by the TrustAnchor (§11.5)
- JWTs signed with the corresponding private key are verifiable during this window
- After 1 hour, even a non-expired JWT is rejected (Veridot key expired)

#### API key without explicit TTL

```
3:API_SERVICE:key_789|alg:cnNh,pk:TUlJQ...,ts:MTcwNjcxMjAwMA,sid:YXBpLWd3,sig:U0hBMjU2d2l0aFJTQQ
```

- No `ttl`: the key remains valid per `dttl` from configuration
- The API key itself MAY have its own expiration logic

#### Message with site membership

```
3:USER_123:session001|alg:cnNh,site:bXMtYXV0aC12MQ,pk:TUlJQ...,ts:MTcwNjcxMjAwMA,ttl:MzYwMA,sid:YXV0aC1zdmM,sig:U0hBMjU2d2l0aFJTQQ
```

- `site` = `"ms-auth-v1"`: the group belongs to site `ms-auth-v1`
- Configuration from site `ms-auth-v1` applies to this group (§5)

---

## 5. Configuration Messages

### 5.1 Hierarchy

Configuration follows a three-level hierarchy with strict precedence:

#### 5.1.1 Local Configuration (Precedence: 1 — Highest)

```
3:<groupId>:__CONFIG__|<metadata>
```

Scope: Applies to the specific group `groupId` only.

#### 5.1.2 Site Configuration (Precedence: 2 — Intermediate)

```
3:__CONFIG__:<siteId>|<metadata>
```

Scope: Applies to all groups declaring membership in site `siteId`.

#### 5.1.3 Global Configuration (Precedence: 3 — Lowest)

```
3:__CONFIG__:__ALL__|<metadata>
```

Scope: Applies to all groups across all sites.

### 5.2 Resolution Rules

1. Local configuration overrides site configuration.
2. Site configuration overrides global configuration.
3. Global configuration overrides implementation defaults.

A group declares its site membership via the `site` property in its
normal messages. A group without a `site` property MUST NOT receive
site-level configuration.

### 5.3 Temporal Resolution

- Each configuration message MUST include a `ts`.
- When multiple configurations exist at the same level, the most recent
  `ts` takes precedence.
- If two configurations at the same level have identical timestamps, the
  message MUST be rejected (unresolvable conflict).
- Configuration messages without a valid `ts` MUST be rejected.

### 5.4 Properties

| Property | Type | Required | Default | Description |
|----------|------|:--------:|---------|-------------|
| `ts` | number | REQUIRED | — | Unix timestamp of configuration |
| `exp` | number | REQUIRED | — | Expiration timestamp of this configuration |
| `max` | number | OPTIONAL | ∞ | Maximum active sessions per group |
| `pol` | string | OPTIONAL | `FIFO` | Eviction policy (only meaningful when `max` is set) |
| `dttl` | number | OPTIONAL | — | Default TTL in seconds for normal messages |
| `name` | string | OPTIONAL | — | Descriptive name |
| `description` | string | OPTIONAL | — | Description |
| `sid` | string | REQUIRED | — | Identifier of the signing service that produced this config |
| `sig` | bytes | REQUIRED | — | Long-term RSA signature over the canonical message bytes (§11.5) |

> **Config signing**: Configuration messages now include `sid` and `sig` fields,
> making them verifiable through the TrustAnchor. This ensures that configuration
> changes can only be made by authorized signing services that possess the
> corresponding long-term private key registered in the TrustAnchor. The `sig`
> field signs all metadata properties (excluding `sig` itself and `token`) using
> the universal canonical encoding (§11.5).

### 5.5 Eviction Policies

When `max` is defined and the session count for a group reaches
the limit, the signing processor MUST apply the configured eviction
policy before publishing a new session.

| Policy | Description |
|--------|-------------|
| `FIFO` | First In, First Out — evicts the oldest session (by `ts`) |
| `LIFO` | Last In, First Out — evicts the newest session |
| `LRU` | Least Recently Used — evicts the least recently accessed session |
| `REJECT` | Rejects the new session creation entirely; no existing session is evicted |

> When `pol` is `REJECT` and `max` is reached, the processor
> MUST throw an error and MUST NOT create the new session or evict any
> existing session.

### 5.6 Validation

A configuration is **valid** if and only if:

- Syntactically correct per §3.
- `ts` is present and is a valid positive Unix timestamp.
- `exp` is present and is greater than `now`.
- All metadata properties are well-formed per §8.

---

## 6. Revocation Messages

### 6.1 Purpose

A revocation message explicitly invalidates one or more sessions within
a group. After a processor processes a revocation, any subsequent
verification against the targeted sessions MUST fail.

### 6.2 Structure

```
3:<groupId>:__REVOKE__|<metadata>
```

### 6.3 Properties

| Property | Type | Required | Description |
|----------|------|:--------:|-------------|
| `target` | string | REQUIRED | The `sequenceId` to revoke, or `__ALL__` to revoke all sessions |
| `ts` | number | REQUIRED | Unix timestamp of the revocation request (epoch seconds) |
| `sid` | string | REQUIRED | Identifier of the signing service that issued this tombstone |
| `sig` | bytes | REQUIRED | Long-term RSA signature over the canonical message bytes (§11.5) |

> Processors with a `TrustAnchor` configured
> MUST reject revocation messages missing `sid` or `sig`.

### 6.4 Semantics

- Upon receiving a `__REVOKE__` message, a processor MUST delete the
  targeted metadata from its store.
- **Single-sequence revocation**: Only the session identified by
  `target` is invalidated.
- **Group-wide revocation**: `target=__ALL__` invalidates all active
  sessions of the group.
- Revocation is **irreversible** within the scope of the targeted
  `sequenceId`. To re-authorize, a new session MUST be created.
- A processor MUST process revocations **atomically**: the deletion
  MUST be complete or MUST NOT take place at all.
- **Conflict resolution**: When two revocation messages arrive for the
  same `groupId`/`target`, the message with the **highest `ts`** MUST
  take precedence (latest-timestamp-wins). This makes replay of older tombstones
  harmless.

### 6.5 Examples

#### Single-session revocation (with trust fields)

```
3:user123:__REVOKE__|target:c2Vzc2lvbjAwMQ,ts:MTcwNjcxNTYwMA,sid:YXV0aC1zdmM,sig:U0hBMjU2d2l0aFJTQQ
```

- `target` = `"session001"` → revokes only `3:user123:session001`
- `sig` verifiable by TrustAnchor using the canonical bytes (§11.5)

#### Group-wide revocation

```
3:user123:__REVOKE__|target:X19BTExfXw,ts:MTcwNjcxNTYwMA,sid:YXV0aC1zdmM,sig:U0hBMjU2d2l0aFJTQQ
```

- `target` = `"__ALL__"` → revokes all active sessions of group `user123`

---

## 7. Reserved Sequences

### 7.1 Naming Convention

Reserved sequences follow the pattern `__<NAME>__` (double underscores
on each side). The `<NAME>` portion MUST consist of uppercase ASCII
letters only (`[A-Z]+`).

### 7.2 Registry

| Sequence | Purpose | Specification |
|----------|---------|:-------------:|
| `__CONFIG__` | Configuration messages | §5 |
| `__REVOKE__` | Revocation messages | §6 |
| `__ALL__` | Universal target (used in `__CONFIG__` and `__REVOKE__`) | §5.1.3, §6.4 |

### 7.3 Extension Rules

Any new reserved sequence MUST:

- Follow the `__<NAME>__` naming pattern.
- Be fully specified (format, properties, semantics) in this document.
- Not collide with existing reserved sequences.
- Be registered in the table above.

Implementations MUST NOT treat unknown reserved sequences as normal
sessions. Unknown reserved sequences MUST be ignored.

---

## 8. Metadata Format

### 8.1 Structure

Metadata is a comma-separated list of key-value pairs:

```
metadata = property *("," property)
property = prop-name ":" base64url-value
```

### 8.2 Property Names

- **Format**: `[a-zA-Z][a-zA-Z0-9_]*` (starts with a letter)
- **Length**: 1 to 32 characters
- **Case sensitivity**: Names are case-sensitive (`ts` ≠
  `Ts`)

### 8.3 Property Values

- **Encoding**: All values MUST be encoded using Base64url [RFC 4648 §5]
  without padding.
- **Content**: The UTF-8 representation of the value. Numbers MUST be
  encoded as their decimal string representation. Strings MUST be
  encoded as-is. Complex structures MUST be serialized as JSON.
- **Maximum size**: 1024 octets per value (after decoding).

### 8.4 Decoding Process

A processor MUST decode metadata following these steps:

1. **Parse**: Split by commas to obtain individual `name:value` pairs.
2. **Validate names**: Verify regex conformance and uniqueness.
3. **Decode values**: Validate Base64url encoding and decode.
4. **Semantic validation**: Verify type and range for known properties.

### 8.5 Error Handling

- **Invalid name**: MUST reject the entire message.
- **Invalid Base64url**: MUST reject the entire message.
- **Invalid value** (unexpected type, out of range): MUST reject the
  entire message.
- **Unknown property**: MUST ignore the property (forward
  compatibility).

---

## 9. Session Capacity Management

### 9.1 Counting

The number of active sessions for a group is derived from the broker
state, not from a local counter. Before publishing a new session, the
signing processor MUST query the broker for the list of active (non-
expired, non-revoked) sessions of the target group.

Expired sessions detected during counting SHOULD be garbage-collected
(deleted from the broker) to prevent stale accumulation.

### 9.2 Eviction Trigger

Eviction is triggered **at signing time** by the signing processor:

1. The processor retrieves active sessions for the group from the
   broker.
2. If `count >= max`:
   - If `pol` is `REJECT`: the processor MUST throw an error and
     MUST NOT create the new session.
   - Otherwise: the processor MUST emit a `__REVOKE__` message (§6)
     for the session selected per the configured `pol`, **before**
     publishing the new session.
3. If `max` is not defined, no capacity check is performed.

### 9.3 Distributed Consistency

Session counting consistency is **eventual**. Two processors signing
simultaneously for the same group MAY temporarily exceed `max`.
After broker convergence, the effective count will stabilize. This is a
deliberate design choice: **availability over strict consistency**.

---

## 10. Implementation Requirements

### 10.1 Processor Responsibilities

#### 10.1.1 Message Validation

A processor MUST:

- Validate syntax per the constraints in §3.
- Reject any non-conforming message.
- Log rejections with a detailed reason.
- NEVER process a partially valid message.

#### 10.1.2 Configuration Management

A processor MUST:

- Apply the most recent valid configuration.
- Maintain a cache of active configurations.
- Clean up expired configurations (`exp` exceeded).
- Guarantee atomicity of configuration changes.

#### 10.1.3 Normal Message Handling

A processor MUST:

- Check temporal validity: `now < ts + ttl`.
- Reject messages whose TTL has elapsed.
- Extract the public key and algorithm for cryptographic validation.
- Separate Veridot validation (message) from business validation
  (signed object).

#### 10.1.4 Session Management

A processor MUST:

- Derive session counts from broker state (not from a local counter).
- Apply eviction per the configured policy at signing time.
- Process revocations atomically.

### 10.2 Broker Requirements

The broker MUST provide the following capabilities:

| Capability | Description |
|------------|-------------|
| **Send** | Publish a message associated with a key (`messageId`) |
| **Get by key** | Retrieve a message by its exact `messageId` |
| **List by prefix** | Retrieve the list of active `messageId`s for a given `groupId` prefix |

The **list by prefix** capability is necessary for:

- Session counting (§9.1)
- Group-wide revocation (`__ALL__`) (§6.4)
- Active group state inspection

### 10.3 Consistency Properties

#### 10.3.1 Local Consistency

- **Atomicity**: Configuration changes and revocations MUST be atomic
  at the processor level.
- **Isolation**: Intermediate states MUST NOT be visible.
- **Durability**: Validated configurations MUST survive restarts.

#### 10.3.2 Distributed Consistency

- **Eventual**: All processors MUST converge to the same state.
- **Transport**: Strong consistency is the broker's responsibility, not
  the protocol's.
- **Partition**: In case of network partition, availability SHOULD be
  prioritized.

### 10.4 Error Handling

#### 10.4.1 Error Classification

| Category | Examples |
|----------|---------:|
| Protocol errors | Syntax, format, validation failures |
| Transport errors | Network issues, broker unavailability |
| Implementation errors | Memory, disk, data corruption |

#### 10.4.2 Recovery Strategies

- **Retry**: For transient transport errors.
- **Fallback**: Use default configuration when no valid configuration
  exists.
- **Circuit breaker**: Isolate failing components.
- **Graceful degradation**: Maintain service in degraded mode.

### 10.5 Observability

#### 10.5.1 Metrics

A conforming implementation SHOULD expose:

- Number of messages processed/rejected by type.
- Message processing latency.
- Error rate by category.
- Active session count per group.

#### 10.5.2 Logging

A conforming implementation MUST log:

- Message rejections with detailed reason.
- Configuration changes with timestamp.
- Session revocations with identifier.
- Transport errors with context.

---

## 11. Security Considerations

### 11.1 Cryptographic Validation

- Signatures MUST be validated according to the `alg` specified.
- Public keys MUST be validated for format and algorithm compatibility.
- Timestamps MUST be validated against clock drift (tolerance: ±5
  minutes). A message with `ts > now + 300` MUST be rejected.
- TTL values MUST be strictly enforced.
- `sig` MUST be validated via the configured `TrustAnchor` before
  the ephemeral public key is accepted (§11.4).
- For revocation messages, `sig` MUST be validated via the configured `TrustAnchor` before
  a revocation tombstone is applied (§11.4).

### 11.2 Threat Mitigation

| Threat | Mitigation |
|--------|-----------|
| Replay attacks | Timestamps and TTL enforcement |
| Broker injection (key forgery) | TrustAnchor + `sig` validation (§11.4) |
| Tombstone forgery | `sig` validation (§11.4) |
| Tombstone replay | Latest-timestamp-wins conflict resolution (§6.4) |
| Injection attacks | Strict format validation (§3.2, §8) |
| Denial of Service | Size limits and rate limiting (implementation-specific) |
| Tampering | Mandatory cryptographic signatures |

### 11.3 Audit and Traceability

- Each message has a unique, deterministic identifier (`messageId`).
- Audit logs MUST provide complete traceability of operations.
- Cryptographic signatures provide non-repudiation.
- Log retention policies SHOULD be defined by the deployment.

### 11.4 TrustAnchor — Broker is Transport Only

Prior to the TrustAnchor model, a processor with write access to the broker could inject arbitrary
key announcements and obtain valid verification results from conforming consumers.
This constitutes a critical security vulnerability.

Every normal message MUST carry `sid` and `sig`.
The verifying processor MUST resolve the long-term public key for `sid` from
an out-of-band trust store (the `TrustAnchor`) and verify `sig` over
the canonical message bytes (§11.5) **before** accepting the ephemeral
`pk`.

The `TrustAnchor` is independent of the broker. Broker write-access alone is
insufficient to produce a valid `sig` without also possessing the
signer's long-term private key.

Failure modes:
- **`TrustAnchor` infrastructure unavailable** — the processor MUST fail safe:
  reject the token. It MUST NOT fall back to accepting unverified announcements.
- **`sig` invalid** — the processor MUST reject the message and MUST
  log a SEVERE security event. This indicates a possible broker-injection attack.

### 11.5 Universal Canonical Encoding

All message types (announcements, tombstones, and configs) use the same universal
canonical encoding for computing `sig`. The bytes signed by `sig` are encoded as
follows:

```
len(messageId) [4 bytes, big-endian unsigned] ‖ messageId [UTF-8]
‖ for each (key, value) sorted alphabetically (excluding 'sig' and 'token'):
    len(key)   [4 bytes, big-endian unsigned] ‖ key   [UTF-8]
  ‖ len(value) [4 bytes, big-endian unsigned] ‖ value [UTF-8]
```

The `messageId` is the full message identifier (e.g., `3:user123:session001`).
All properties are iterated in **lexicographic order** by key name. The `sig`
field itself and the `token` field are excluded from the canonical encoding.
All multi-byte integers are encoded big-endian.

This universal approach eliminates the need for separate per-message-type
canonical encodings and ensures that any future message types automatically
benefit from a consistent, unambiguous signing format.

---

## 12. Evolution and Compatibility

### 12.1 Protocol Versioning

- **Major version**: Incompatible changes to the canonical format.
- **Minor version**: Backward-compatible extensions (new properties, new
  reserved sequences).
- **Patch version**: Clarifications and corrections.

### 12.2 Extensibility

- **New properties**: MAY be added to metadata without breaking existing
  implementations (unknown properties are ignored per §8.5).
- **New reserved sequences**: MUST be registered and fully specified in
  this document.
- **New message types**: Require a new major protocol version.

### 12.3 Migration

- Multiple protocol versions MAY coexist in the same broker.
- Version detection is automatic via the first field of the message.
- Progressive migration is supported.

---

## Appendix A. ABNF Grammar (RFC 5234)

```abnf
message           = version ":" pci "|" metadata
version           = "3"
pci               = groupId ":" sequenceId
groupId           = identifier
sequenceId        = identifier / reserved-sequence
identifier        = 1*125(VCHAR) ; excluding ":" / "," / "|" / WSP
reserved-sequence = "__" 1*28(ALPHA) "__"
metadata          = property *("," property)
property          = prop-name ":" base64url-value
prop-name         = ALPHA 0*31(ALPHA / DIGIT / "_")
base64url-value   = 1*(ALPHA / DIGIT / "-" / "_")
```

> Note: `VCHAR` is defined in RFC 5234 as any visible (printable) ASCII
> character (0x21-0x7E). The `identifier` rule further excludes `:`
> (0x3A), `,` (0x2C), and `|` (0x7C) from `VCHAR`.

## Appendix B. Error Codes

| Code | Name | Description |
|------|------|-------------|
| `V3001` | `INVALID_SYNTAX` | Invalid syntactic structure |
| `V3002` | `INVALID_VERSION` | Unsupported version |
| `V3003` | `INVALID_IDENTIFIER` | Invalid groupId or sequenceId |
| `V3004` | `INVALID_METADATA` | Invalid metadata format |
| `V3005` | `MISSING_REQUIRED_PROPERTY` | Missing required property (including `sid`, `sig`) |
| `V3006` | `INVALID_TIMESTAMP` | Invalid or expired timestamp |
| `V3007` | `INVALID_SIGNATURE` | Invalid cryptographic signature (JWT / ephemeral key) |
| `V3008` | `CONFIGURATION_CONFLICT` | Unresolvable configuration conflict |
| `V3009` | `SESSION_CAPACITY_EXCEEDED` | Session limit reached (REJECT policy) |
| `V3010` | `REVOCATION_FAILED` | Revocation could not be processed |
| `V3011` | `TRUST_ANCHOR_UNAVAILABLE` | TrustAnchor infrastructure temporarily unreachable (fail safe) |
| `V3012` | `SIGNATURE_REJECTED` | `sig` failed TrustAnchor validation (security event) |

## Appendix C. Normative References

- **[RFC 2119]** Bradner, S., "Key words for use in RFCs to Indicate
  Requirement Levels", BCP 14, RFC 2119, March 1997.
- **[RFC 4648]** Josefsson, S., "The Base16, Base32, and Base64 Data
  Encodings", RFC 4648, October 2006. (§5: Base64url)
- **[RFC 5234]** Crocker, D., Ed. and P. Overell, "Augmented BNF for
  Syntax Specifications: ABNF", STD 68, RFC 5234, January 2008.
- **[RFC 8259]** Bray, T., Ed., "The JavaScript Object Notation (JSON)
  Data Interchange Format", STD 90, RFC 8259, December 2017.

---

*Veridot Protocol Specification — Version 3.0*
*Last revised: 2026-06-27*
*Changes in v3.0: Universal `sig` field for all message types (§4.2, §5.4, §6.3), config message signing (§5.4), universal canonical encoding (§11.5), TrustAnchor security model (§11.4), shortened property names.*