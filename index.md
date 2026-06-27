---
layout: home
title: Enterprise-Grade Distributed Token Verification
---

# Veridot

[![Java](https://img.shields.io/badge/Java-25%2B-ED8B00.svg)](https://openjdk.java.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-core)](https://central.sonatype.com/search?q=io.github.cyfko.veridot)
[![Protocol V3](https://img.shields.io/badge/Protocol-V3-green.svg)](PROTOCOL_V3.html)
[![Trust Architecture](https://img.shields.io/badge/Trust-TrustAnchor%20v3.0-purple.svg)](docs/adr/)

**Veridot** is a production-ready Java library for **secure, distributed token verification** in microservices architectures. It eliminates shared secrets, enables instant network-wide revocation, and enforces a zero-trust key-announcement model — without a centralized authentication chokepoint.

---

## 🚀 Quick Start (Java — Maven)

```xml
<!-- Core API (Java 25+) -->
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>3.0.2</version>
</dependency>

<!-- Kafka + RocksDB broker -->
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-kafka</artifactId>
    <version>3.0.2</version>
</dependency>
```

```java
// 1. Configure your TrustAnchor (validates key announcements from the broker)
TrustAnchor anchor = (TrustAnchor.PublicKeyResolver) signerId ->
    loadPublicKeyFromVault(signerId);

// 2. Build the signer/verifier
var sv = new GenericSignerVerifier(broker, anchor, "my-service-id", longTermPrivateKey);

// 3. Sign
String token = sv.sign("user@example.com",
    BasicConfigurer.builder().groupId("user-123").validity(3600).build());

// 4. Verify (on any service sharing the same broker + trust anchor)
VerifiedData<String> result = sv.verify(token, s -> s);

// 5. Revoke
sv.revoke("user-123", null); // revoke all sessions for the group
```

---

## 🎯 Why Veridot?

<div class="feature-grid">
  <div class="feature">
    <h3>🔐 Zero Shared Secrets</h3>
    <p>Every service instance generates its own short-lived RSA-3072 key pairs. No shared HMAC secrets. No secret rotation across services.</p>
  </div>

  <div class="feature">
    <h3>🛡️ Zero-Trust Key Announcements</h3>
    <p>v3.0: every key announcement is validated by a <code>TrustAnchor</code> before use. Broker write-access can no longer forge valid verifications.</p>
  </div>

  <div class="feature">
    <h3>⚡ Sub-Millisecond Verification</h3>
    <p>Public keys are cached locally in RocksDB. Token verification never hits the network — it resolves in &lt;1ms from the local store.</p>
  </div>

  <div class="feature">
    <h3>🔄 Instant Revocation</h3>
    <p>A single <code>revoke()</code> call broadcasts a signed tombstone across the cluster. All nodes invalidate the session within one Kafka poll interval (~1s p99).</p>
  </div>

  <div class="feature">
    <h3>📊 Session Capacity Management</h3>
    <p>Enforce max concurrent sessions per user (FIFO, LIFO, LRU, or REJECT). Session counting is derived from broker state — no local counters that drift.</p>
  </div>

  <div class="feature">
    <h3>🔌 Pluggable Brokers</h3>
    <p>Kafka + RocksDB (recommended), SQL databases, or any custom <code>MetadataBroker</code> implementation. The core protocol is broker-agnostic.</p>
  </div>
</div>

---

## 📖 Documentation

<div class="doc-links">
  <a href="{{ '/docs/getting-started' | relative_url }}" class="doc-link">
    <h3>⚡ Getting Started</h3>
    <p>Installation, first sign/verify/revoke, prerequisites</p>
  </a>

  <a href="{{ '/docs/java-guide' | relative_url }}" class="doc-link">
    <h3>☕ Java Guide</h3>
    <p>Complete implementation guide: TrustAnchor, session management, Spring Boot, migration from v3.0.1</p>
  </a>

  <a href="{{ '/docs/api-reference' | relative_url }}" class="doc-link">
    <h3>📚 API Reference</h3>
    <p>All interfaces, classes, constructors, exceptions, and environment variables</p>
  </a>

  <a href="{{ '/docs/security' | relative_url }}" class="doc-link">
    <h3>🔒 Security Model</h3>
    <p>Threat model, TrustAnchor architecture, production hardening checklist</p>
  </a>

  <a href="{{ '/docs/adr' | relative_url }}" class="doc-link">
    <h3>🏛️ Architecture Decisions</h3>
    <p>ADR-001 TrustAnchor · ADR-002 RSA-3072 · ADR-003 Signed Tombstones</p>
  </a>

  <a href="{{ '/PROTOCOL_V3' | relative_url }}" class="doc-link">
    <h3>📐 Protocol V3 Specification</h3>
    <p>Message format, revocation semantics, configuration hierarchy, ABNF grammar</p>
  </a>
</div>

---

## 🔐 Trust Architecture (v3.0)

Prior to v3.0, any node with Kafka write-access could inject fraudulent key announcements and obtain valid verification results. **v3.0 closes this gap.**

```
Before v3.0:
  Broker write-access → forge key announcement → valid verification ✗

After v3.0 (TrustAnchor):
  Broker write-access → forge key announcement → TrustAnchor rejects it ✓
```

The `TrustAnchor` validates every announcement's long-term RSA signature before the ephemeral key is accepted. Integrate with Vault, AWS KMS, Google Cloud KMS, Azure Key Vault, or any HSM via 5 lines of code.

🔗 [ADR-001: TrustAnchor decision record]({{ '/docs/adr/001-trust-anchor' | relative_url }}) · [Security Model]({{ '/docs/security' | relative_url }})

---

<div class="cta-section">
  <h2>Ready to get started?</h2>
  <p>Read the Java guide and have your first signed token in under 10 minutes.</p>
  <div class="cta-buttons">
    <a href="{{ '/docs/getting-started' | relative_url }}" class="btn btn-primary">Get Started</a>
    <a href="https://github.com/cyfko/veridot" class="btn btn-secondary">View on GitHub</a>
  </div>
</div>

<style>
.feature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 1.5rem;
  margin: 2rem 0;
}

.feature {
  padding: 1.5rem;
  border: 1px solid #e1e5e9;
  border-radius: 8px;
  background: #f8f9fa;
}

.feature h3 {
  margin-top: 0;
  color: #2c3e50;
}

.doc-links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 1.25rem;
  margin: 2rem 0;
}

.doc-link {
  display: block;
  padding: 1.25rem;
  border: 1px solid #e1e5e9;
  border-radius: 8px;
  text-decoration: none;
  color: inherit;
  transition: all 0.2s ease;
}

.doc-link:hover {
  border-color: #007bff;
  box-shadow: 0 4px 8px rgba(0,123,255,0.1);
  text-decoration: none;
}

.doc-link h3 {
  margin-top: 0;
  color: #007bff;
  font-size: 1rem;
}

.doc-link p {
  margin-bottom: 0;
  font-size: 0.9rem;
  color: #555;
}

.cta-section {
  text-align: center;
  padding: 3rem 2rem;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
  color: white;
  border-radius: 8px;
  margin: 3rem 0;
}

.cta-section h2 { color: white; }

.cta-buttons { margin-top: 2rem; }

.btn {
  display: inline-block;
  padding: 0.75rem 1.5rem;
  margin: 0 0.5rem;
  border-radius: 4px;
  text-decoration: none;
  font-weight: 600;
  transition: all 0.2s ease;
}

.btn-primary {
  background-color: #e94560;
  color: white;
  border: 2px solid #e94560;
}

.btn-primary:hover {
  background-color: #c73652;
  border-color: #c73652;
  text-decoration: none;
  color: white;
}

.btn-secondary {
  background-color: transparent;
  color: white;
  border: 2px solid white;
}

.btn-secondary:hover {
  background-color: white;
  color: #0f3460;
  text-decoration: none;
}

@media (prefers-color-scheme: dark) {
  .feature {
    background: #2d3748;
    border-color: #4a5568;
  }
  .feature h3 { color: #e2e8f0; }
  .doc-link {
    background: #2d3748;
    border-color: #4a5568;
    color: #e2e8f0;
  }
  .doc-link:hover { border-color: #4299e1; }
  .doc-link h3 { color: #4299e1; }
  .doc-link p { color: #a0aec0; }
}
</style>