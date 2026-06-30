# Java API Reference Overview

The Veridot Java SDK exposes a modular, thread-safe, and highly-optimized API designed to be integrated into any microservice or standalone application.

---

## 1. Package Structure

The public API is divided into the following packages:

- **`io.github.cyfko.veridot.core`**: Holds the primary interfaces and domain models:
  - `DataSigner`: Contract for issuing tokens.
  - `TokenVerifier`: Contract for verifying tokens.
  - `TokenTracker`: Contract for checking token liveness.
  - `TokenRevoker`: Contract for revoking sessions.
  - `Broker`: Interface for custom transport implementations.
  - `TrustRoot`: Interface for validating long-term keys.
- **`io.github.cyfko.veridot.core.exceptions`**: Holds the exception hierarchy:
  - `VeridotException`: Root runtime exception.
  - `BrokerExtractionException`: Thrown when token metadata extraction/validation fails.
  - `BrokerTransportException`: Thrown when the Broker is unavailable during writes.
  - `SessionCapacityExceededException`: Thrown when session creation violates the active quota.
- **`io.github.cyfko.veridot.core.impl`**: Concrete implementations:
  - `GenericSignerVerifier`: Orchestrates all V4 protocol rules.
  - `BasicConfigurer`: Fluent builder for signing requests.
  - `FileWatermarkStore`: Watermark persistence file with integrity protection.

---

## 2. API Design Principles

- **Thread Safety**: All implementations of `DataSigner`, `TokenVerifier`, `TokenTracker`, and `TokenRevoker` are fully thread-safe and can be shared across multiple worker threads.
- **Asynchronous Writes**: Writing entries to the broker returns `CompletableFuture<Void>`, preventing calling threads from blocking on network IO during key epoch publication.
- **Fail-Closed**: If trust resolution fails, or the broker cannot be reached during a verification check, Veridot raises a `BrokerExtractionException` and refuses token authorization.
