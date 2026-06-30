# FAQ & Troubleshooting

This section compiles frequently asked questions and troubleshooting guides for Veridot V4 deployments.

---

## 1. Troubleshooting Error Codes

### Why do I see `V4201 (STALE_VERSION)` errors?
- **Cause**: An Issuer attempted to publish an envelope with a version number less than or equal to the watermark currently accepted by the Verifier.
- **Resolution**:
  - Check if two Issuer instances are writing to the same logical `EntryId` without coordination.
  - If re-signing a capability or configuration, ensure you increment the version number relative to the previously published value. Verifiers will reject updates that do not increase the version.

### What causes `RECONCILIATION_STALE` exceptions on the Verifier?
- **Cause**: The Verifier node has not successfully run its periodic reconciliation snapshot scan against the Broker within the maximum allowed staleness window (60 minutes). To prevent rollback attacks, the Verifier fails closed and rejects all validations.
- **Resolution**:
  - Verify that the Broker is online and reachable from the Verifier pods.
  - Inspect verifier logs for `TRANSPORT_UNAVAILABLE (V4401)` errors during snapshot scans.
  - Check the network latency and JVM garbage collection pauses on the Verifier.

### Why is my token rejected with `V4202 (LIVENESS_NOT_ESTABLISHED)`?
- **Cause**: The liveness check for the session failed because either:
  1. The `LIVENESS` entry is missing from the Broker.
  2. The liveness entry status is `REVOKED`.
  3. The liveness attestation validity window has expired (`now >= validUntil`).
- **Resolution**:
  - Verify that the Issuer's background liveness renewal loop is running.
  - Ensure there is no severe network lag preventing the Issuer from publishing liveness renewals before the last attestation expires.
  - Verify that the Issuer's system clock is synchronized with the Verifier's system clock (NTP).

---

## 2. Common Integration Questions

### Is Veridot thread-safe?
Yes. The main orchestrator `GenericSignerVerifier` is thread-safe. All verification and signing operations can be shared safely across your application's worker threads.

### Can I run Veridot in a multi-tenant environment?
Yes. Group scopes (`group:<groupId>`) provide isolation. You can configure different capacities and eviction policies for each tenant by publishing `CONFIG` entries at the group level.

### How do I configure cache TTLs?
You can control caching behavior using environment variables:
- **`VDOT_CAPABILITY_CACHE_TTL_SECONDS`**: Controls how long verifiers cache valid capability chains (default: 60s).
- **`VDOT_CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS`**: Controls how long verifiers cache denied capability results (default: 5s) to avoid hammering the Broker.
- **`VDOT_CLOCK_DRIFT_TOLERANCE_SECONDS`**: Clock drift tolerance window (default: 300s).
- **`VDOT_RECONCILIATION_INTERVAL_MINUTES`**: Frequency of reconciliation runs (default: 15m).
