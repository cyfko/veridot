---
title: Environment Variables
description: Complete reference of all VDOT_* environment variables with defaults, descriptions, and Config class mappings.
keywords: [veridot, configuration, environment variables, VDOT, Kafka, tuning]
sidebar_position: 16
---

# Environment Variables

Veridot resolves runtime configuration from environment variables at class loading time. Every variable has a sensible default that works for development and single-node deployments. Override them for production tuning.

:::info Resolution order
Veridot checks `System.getenv(key)` first, then falls back to `System.getProperty(key)`. This means you can configure via environment variables **or** JVM system properties (`-DVDOT_...=value`).
:::

## Core Variables

These variables are defined in the `Config` class (`veridot-core`) and apply to all broker implementations.

| Variable | Config Constant | Default | Type | Description |
|---|---|---|---|---|
| `VDOT_KEYS_ROTATION_MINUTES` | `Config.KEYS_ROTATION_MINUTES` | `1440` (24h) | `long` | Interval between automatic ephemeral key pair rotations. Reducing this increases forward secrecy but invalidates older tokens sooner. |
| `VDOT_RECONCILIATION_INTERVAL_MINUTES` | `Config.RECONCILIATION_INTERVAL_MINUTES` | `15` | `long` | Interval between periodic version watermark reconciliations against the broker. Lower values reduce cross-instance staleness at the cost of more `snapshot()` calls. |
| `VDOT_RECONCILIATION_MAX_STALENESS_MINUTES` | `Config.RECONCILIATION_MAX_STALENESS_MINUTES` | `60` | `long` | Maximum allowed staleness before verification is rejected. If the last successful reconciliation was more than this many minutes ago, `verify()` throws `VeridotException` with code `V4402`. |
| `VDOT_CAPABILITY_CACHE_TTL_SECONDS` | `Config.CAPABILITY_CACHE_TTL_SECONDS` | `10` | `long` | How long verified (positive) capability results are cached in memory. |
| `VDOT_CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS` | `Config.CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS` | `5` | `long` | How long denied (negative) capability results are cached to prevent hammering the broker. |
| `VDOT_CLOCK_DRIFT_TOLERANCE_SECONDS` | `Config.MAX_CLOCK_DRIFT_SECONDS` | `300` (5 min) | `long` | Maximum allowed clock drift between signer and verifier. Range: 0–600. |
| `VDOT_ALLOWED_SIG_ALGS` | `Config.ALLOWED_SIG_ALGS` | `RSA_PSS, ED25519` | CSV | Comma-separated list of allowed signature algorithms. Accepted values: `RSA`, `RSA_SHA256`, `ECDSA`, `ECDSA_SHA256`, `RSA_PSS`, `ED25519`, `EDDSA`, or their wire codes (`1`–`4`). |
| `VDOT_MIN_RSA_KEY_LENGTH` | `Config.MIN_RSA_KEY_LENGTH` | `2048` | `int` | Minimum accepted RSA public key size in bits. Must be ≥ 1024. |
| `VDOT_WATERMARK_PERSISTENCE_FILE` | `Config.WATERMARK_PERSISTENCE_FILE` | `null` | `String` | File path to save/load version watermark snapshots for persistent monotonicity across restarts. If unset and the broker implements `WatermarkStore`, that is used instead. |

## Kafka Variables

These variables are defined in the `Constant` class (`veridot-kafka`) and apply only when using the Kafka broker implementation.

| Variable | Constant | Default | Type | Description |
|---|---|---|---|---|
| `VDOT_KAFKA_BOOSTRAP_SERVERS` | `Constant.KAFKA_BOOSTRAP_SERVERS` | `localhost:9092` | `String` | Comma-delimited `host:port` pairs for the initial Kafka cluster connection. |
| `VDOT_TOKEN_VERIFIER_TOPIC` | `Constant.KAFKA_TOKEN_VERIFIER_TOPIC` | `token-verifier` | `String` | Kafka topic for producing and consuming verification metadata. All services sharing this topic can cross-verify tokens. |
| `VDOT_EMBEDDED_DATABASE_PATH` | `Constant.EMBEDDED_DATABASE_PATH` | `veridot_db_data` | `String` | File-system path for the embedded RocksDB directory that persists verification metadata locally. Created automatically if it doesn't exist. |

## Configuration Examples

### Development (defaults are fine)

```bash
# No environment variables needed — defaults work out of the box
java -jar my-service.jar
```

### Production — Minimal

```bash
export VDOT_KAFKA_BOOSTRAP_SERVERS="kafka-1:9092,kafka-2:9092,kafka-3:9092"
export VDOT_TOKEN_VERIFIER_TOPIC="veridot-prod"
export VDOT_EMBEDDED_DATABASE_PATH="/var/lib/veridot/rocksdb"
export VDOT_WATERMARK_PERSISTENCE_FILE="/var/lib/veridot/watermark.dat"
```

### Production — Hardened

```bash
# Kafka
export VDOT_KAFKA_BOOSTRAP_SERVERS="kafka-1:9092,kafka-2:9092,kafka-3:9092"
export VDOT_TOKEN_VERIFIER_TOPIC="veridot-prod"
export VDOT_EMBEDDED_DATABASE_PATH="/var/lib/veridot/rocksdb"

# Security
export VDOT_ALLOWED_SIG_ALGS="ED25519"           # Ed25519 only
export VDOT_MIN_RSA_KEY_LENGTH="3072"             # NIST post-2030 recommendation
export VDOT_CLOCK_DRIFT_TOLERANCE_SECONDS="60"    # Tight clock sync (NTP required)
export VDOT_WATERMARK_PERSISTENCE_FILE="/var/lib/veridot/watermark.dat"

# Performance
export VDOT_KEYS_ROTATION_MINUTES="720"           # Rotate every 12 hours
export VDOT_RECONCILIATION_INTERVAL_MINUTES="5"   # Frequent reconciliation
export VDOT_RECONCILIATION_MAX_STALENESS_MINUTES="15"
export VDOT_CAPABILITY_CACHE_TTL_SECONDS="30"     # Cache capabilities longer
export VDOT_CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS="10"
```

### JVM System Properties (Alternative)

```bash
java -DVDOT_KAFKA_BOOSTRAP_SERVERS="kafka:9092" \
     -DVDOT_ALLOWED_SIG_ALGS="ED25519" \
     -jar my-service.jar
```

## Config Class Constants

For programmatic access, all resolved values are available as `public static final` fields on the `Config` class:

```java
import io.github.cyfko.veridot.core.impl.Config;

long rotationMinutes   = Config.KEYS_ROTATION_MINUTES;        // 1440
long reconcileMinutes  = Config.RECONCILIATION_INTERVAL_MINUTES; // 15
int  protocolVersion   = Config.PROTOCOL_VERSION;              // 4
long clockDriftSeconds = Config.MAX_CLOCK_DRIFT_SECONDS;       // 300
Set<Algorithm> algs    = Config.ALLOWED_SIG_ALGS;              // [RSA_PSS, ED25519]
```

:::warning
Config values are resolved **once** at class loading time and are immutable thereafter. Changing an environment variable after the JVM starts has no effect. Restart the JVM to pick up changes.
:::

## Validation Rules

All numeric variables are validated at class loading time:

| Variable | Minimum | Maximum | On invalid value |
|---|---|---|---|
| `VDOT_KEYS_ROTATION_MINUTES` | 1 | — | Falls back to default (1440) with warning |
| `VDOT_RECONCILIATION_INTERVAL_MINUTES` | 1 | — | Falls back to default (15) with warning |
| `VDOT_CLOCK_DRIFT_TOLERANCE_SECONDS` | 0 | 600 | Falls back to default (300) with warning |
| `VDOT_MIN_RSA_KEY_LENGTH` | 1024 | — | Falls back to default (2048) with warning |
| `VDOT_CAPABILITY_CACHE_TTL_SECONDS` | 0 | — | Falls back to default (10) with warning |
| `VDOT_CAPABILITY_NEGATIVE_CACHE_TTL_SECONDS` | 0 | — | Falls back to default (5) with warning |
| `VDOT_RECONCILIATION_MAX_STALENESS_MINUTES` | 1 | — | Falls back to default (60) with warning |

## Next Steps

- [Core Concepts](./core-concepts.md) — protocol foundations these variables configure
- [Error Handling](./error-handling.md) — how configuration errors surface at runtime
