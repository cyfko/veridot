---
title: taas-api
description: HTTP and Java API definitions for interacting with the TAAS in Veridot V5.
keywords: [taas-api, REST API, TAAS, veridot, V5]
sidebar_position: 3
---

# taas-api

`taas-api` defines the data transfer objects (DTOs) and API contracts used to communicate with the TAAS cluster.

## HTTP REST API

The TAAS exposes a RESTful interface for instances to register themselves and for verification nodes to resolve public keys.

### 1. Register or Rotate Instance (Attestation)

**Endpoint:** `POST /v2/trust-entries` (Registration) or `PUT /v2/trust-entries/{subject}` (Rotation)

**Request:**
The request expects a JSON payload containing the `TrustEntry` object and an `attestationProof`.
```json
{
  "entry": {
    "_schemaVersion": 2,
    "subject": "api-gateway@a1b2c3d4e5f6...",
    "publicKeyEncoded": "BASE64_ENCODED_DER",
    "algorithm": "ED25519",
    "notBefore": "2026-07-16T00:00:00Z",
    "notAfter": "2027-07-16T00:00:00Z",
    "version": 1,
    "fingerprint": "a1b2c3...",
    "issuerSignature": "BASE64_SIG",
    "publishedAt": "2026-07-16T00:00:00Z",
    "isRoot": false,
    "isInstanceScoped": true,
    "attestationPlugin": "K8S_SAT"
  },
  "attestationProof": "BASE64_ENCODED_PROOF"
}
```

**Response (201 Created or 200 OK):**
Returns the committed `TrustEntry` or a status indicating success.

**Error Codes:**
- `400 Bad Request`: Format invalid (`V5102`)
- `403 Forbidden`: Attestation verification failed (`V5101`)

### 2. Resolve Public Key

**Endpoint:** `GET /v2/trust-entries/{subject}`

Retrieves the active `TrustEntry` for a given identity. This endpoint is extremely fast as it serves directly from the TAAS node's in-memory state machine.

## Java DTOs

The `taas-api` module provides a central Java 21 record mapping to the `TrustEntry` payload:

```java
public record TrustEntry(
    @JsonProperty("_schemaVersion") int schemaVersion,
    String subject,
    String publicKeyEncoded,
    KeyAlgorithm algorithm,
    Instant notBefore,
    Instant notAfter,
    long version,
    String fingerprint,
    String issuerSignature,
    Instant publishedAt,
    boolean isRoot,
    boolean isInstanceScoped,
    String attestationPlugin,
    String attestationRef,
    byte[] kemPublicKey,
    Map<String, String> metadata
) {}
```
