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

### 1. Register Instance (Attestation)

**Endpoint:** `POST /v2/trust-entries`

**Request:**
```json
{
  "subject": "api-gateway@a1b2c3d4e5f6...",
  "publicKey": "BASE64_ENCODED_DER",
  "attestationType": "K8S_SAT",
  "attestationProof": "BASE64_ENCODED_PROOF"
}
```

**Response (201 Created):**
```json
{
  "subject": "api-gateway@a1b2c3d4e5f6...",
  "status": "COMMITTED",
  "version": 1
}
```

**Error Codes:**
- `400 Bad Request`: Format invalid (`V5102`)
- `403 Forbidden`: Attestation verification failed (`V5101`)

### 2. Resolve Public Key

**Endpoint:** `GET /v2/trust-entries/{subject}`

Retrieves the active public key for a given identity. This endpoint is extremely fast as it serves directly from the TAAS node's in-memory state machine.

**Response (200 OK):**
```json
{
  "subject": "api-gateway@a1b2c3d4e5f6...",
  "publicKey": "BASE64_ENCODED_DER",
  "algorithm": "ED25519"
}
```

## Java DTOs

The `taas-api` module provides Java 21 records mapping to these JSON payloads:

```java
public record TrustEntryRegistrationRequest(
    String subject,
    byte[] publicKey,
    String attestationType,
    byte[] attestationProof
) {}

public record TrustEntryResolutionResponse(
    String subject,
    byte[] publicKey,
    Algorithm algorithm
) {}
```
