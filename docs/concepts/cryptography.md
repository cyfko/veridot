# Cryptographic Model

Veridot V4 relies on a dual-layer cryptographic model to ensure that even if the transport layer (Broker) is fully compromised, attackers cannot inject false identities or forge session states.

---

## 1. Asymmetric Algorithm Registry

The protocol supports four public-key signature algorithms, represented by the `sigAlg` field in the envelope header and the `alg` field in the `KEY_EPOCH` payload:

| Code | Algorithm | Description | Standard Reference |
|---|---|---|---|
| `0x01` | **RSA-SHA256** | Traditional RSASSA-PKCS1-v1_5 signature scheme | RFC 8017 |
| `0x02` | **ECDSA-SHA256** | Elliptic Curve Digital Signature Algorithm using Curve P-256 | FIPS 186-5 |
| `0x03` | **RSA-PSS** | RSA Signature Scheme with Appendix (Probabilistic Signature Scheme) | RFC 8017 / NIST SP 800-56B |
| `0x04` | **Ed25519** | Edwards-Curve Digital Signature Algorithm (EdDSA) over Curve25519 | RFC 8032 / NIST SP 800-186 |

> [!TIP]
> **Ed25519 (0x04) is highly recommended** for both long-term and ephemeral keys. It provides fast, constant-time verification, making implementations mathematically immune to cache-timing side-channel attacks.

---

## 2. Key Hierarchy & Ephemeral Key Rotation

Veridot separates signing authority into two distinct cryptographic layers:

```
+---------------------------------------+
|  Root KMS/HSM Private Key             |
+---------------------------------------+
                    | (signs delegation)
                    v
+---------------------------------------+
|  Delegated Long-Term Private Key      |
+---------------------------------------+
                    | (signs epoch metadata)
                    v
+---------------------------------------+
|  Short-Lived Ephemeral Private Key    | (automatically rotated)
+---------------------------------------+
                    | (signs application JWT)
                    v
          [Signed App Token]
```

1. **Long-Term Keys**: Managed securely (HSM or cloud KMS) by the root authority or delegated administrators. These keys sign configuration, capabilities, and key epoch metadata.
2. **Ephemeral Keys**: Generated dynamically by the `KeyRotationService` inside the Issuer microservice. They sign individual user JWTs or API keys. By default, ephemeral keys rotate every **24 hours** (configurable via `VDOT_KEYS_ROTATION_MINUTES`).

---

## 3. Algorithm Confusion Mitigations

Algorithm confusion is a classic vulnerability where an attacker converts a public key into a symmetric HMAC key, or signs a token using a weaker algorithm than configured.

Veridot V4 explicitly prevents this:
- **Envelope Coherence**: The `flags` bit 0 (`COMPACT_SIG`) must align with `sigAlg`. Ed25519 (`0x04`) requires the flag to be `1`; RSA (`0x01` and `0x03`) requires it to be `0`. A mismatch triggers a `V4005` rejection.
- **JWT Alg Matching**: During `TokenVerifier#verify`, the verifier decodes the header of the application JWT and asserts that the `alg` attribute matches the expected code in the `KEY_EPOCH` entry:
  - If epoch `alg` = `0x01` (RSA-SHA256) -> JWT header `alg` MUST be `RS256`.
  - If epoch `alg` = `0x02` (ECDSA-SHA256) -> JWT header `alg` MUST be `ES256`.
  - If epoch `alg` = `0x03` (RSA-PSS) -> JWT header `alg` MUST be `PS256`.
  - If epoch `alg` = `0x04` (Ed25519) -> JWT header `alg` MUST be `EdDSA`.
  - Any mismatch triggers token rejection, preventing algorithm substitution attacks.

---

## 4. Side-Channel Timing Protections

To protect against side-channel analysis, Veridot verifiers use mathematically constant-time cryptographic signatures and timing-safe operations.

Verifiers:
- Implement constant-time signature verification for all asymmetric envelopes.
- Reject the use of timing-dependent string checks when parsing signatures and tokens.
- Recommend the use of Curve25519 (`Ed25519`) because its mathematical structure is naturally resistant to timing and cache attacks.

---

## 5. Temporal Constraints & Clock Drift

Temporal validation prevents key epochs or liveness attestations from being replayed indefinitely.

- **Clock Drift Tolerance**: Veridot permits a clock drift offset of **5 minutes** (300 seconds) between issuers and verifiers (defined in `Config#MAX_CLOCK_DRIFT_SECONDS`).
- **Temporal Checks**:
  - `validFrom - 300,000 <= now` (validity has begun, allowing for clock drift).
  - `now < validUntil` (validity has not expired).
- Clock drift can be customized via the `VDOT_CLOCK_DRIFT_TOLERANCE_SECONDS` environment variable (bounded between `0` and `600` seconds).
