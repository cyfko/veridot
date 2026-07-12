---
title: "Chapter 7: Going to Production"
description: "Deploy Veridot V5 safely: Distribution modes, error codes, and operational best practices."
sidebar_position: 7
pagination_prev: learn/session-management
pagination_next: null
---

# Chapter 7: Going to Production

You are ready to deploy Veridot Protocol V5. Here is what you need to know for a production rollout.

## 1. Distribution Modes

Veridot supports three ways to distribute verification metadata:

- **NATIVE (Default):** The payload is stored in the broker as `SIGNED_DATA`. The client receives a compact reference token like `8:<scope>:<key>`. This avoids large HTTP headers and keeps JWT overhead zero.
- **DIRECT:** The verifier receives a self-contained JWT. Use this when integrating with legacy systems or API gateways that do not speak Veridot native.
- **PRIVATE:** The payload is encrypted (E2EE) with AES-256-GCM. Stored in the broker as `SECURE_PAYLOAD`. The token looks like `7:<scope>:<key>`.

*Note: There is no INDIRECT mode in V5.*

## 2. Error Codes

When things fail, they fail predictably. V5 standardizes error handling with `V5xxx` codes:
- **`V5001`**: Envelope Magic/Version mismatch.
- **`V5002`**: Unregistered entry type.
- **`V5005`**: Signature or structural validation failure (e.g., trailing bytes).
- **`V5007`**: TLV Codec error (e.g., missing required tags or `0x00` tag).

Design your applications to log these codes and alert on anomalous spikes, which could indicate a misconfigured broker or an active attack.

## 3. Monitoring the Broker and TAAS

- **TAAS Cluster:** Monitor Raft consensus latency and attestation rejection rates. 
- **Broker Cache:** Monitor the local RocksDB/Memory cache lag. Veridot reads are sub-millisecond *because* they are local. If the cache falls behind the broker, verifications might fail due to missing `LIVENESS` entries.

With TAAS anchoring your trust, `CN@hash(pk)` identities keeping things ephemeral, and strictly monotonic liveness entries, your system is now secure, distributed, and incredibly fast.
