---
layout: home
hero:
  name: Veridot
  text: Distributed Token Verification
  tagline: Ephemeral public key distribution, capability authorization, monotonic watermarks, and fenced capacity management for Java microservices.
  image:
    src: /logo.svg
    alt: Veridot Logo
  actions:
    - theme: brand
      text: Get Started
      link: /guide/getting-started
    - theme: alt
      text: Why Veridot?
      link: /guide/why
    - theme: alt
      text: GitHub
      link: https://github.com/cyfko/veridot
features:
  - icon: 🛡️
    title: Cryptographically Decoupled
    details: Verify signed objects (JWTs, API keys) across distributed microservices without direct database reads or shared HMAC secrets.
  - icon: ⌛
    title: Monotonic & Rollback Resistant
    details: Active state tracking using local version watermarks. Verifiers reject any attempt to roll back session states or capabilities.
  - icon: 🚀
    title: Fenced Session Capacity
    details: Set hard bounds on active sessions per user group with eviction policies (FIFO, LIFO, LRU, REJECT), ordered using Fence Tokens.
  - icon: 🔌
    title: Production-Ready Transport
    details: Pluggable storage brokers for Apache Kafka + RocksDB and major SQL databases (PostgreSQL, MySQL, Oracle, SQL Server).
---

<div class="content-section" style="max-width: 960px; margin: 40px auto; padding: 0 24px;">

## Minimal Example in 1 Minute

### 1. Issuing a Token (Issuer Node)
```java
// Configure and issue a token valid for 1 hour (3600s)
String token = signer.sign("user@example.com",
    BasicConfigurer.builder()
        .groupId("user-123")
        .sequenceId("session-A")   // Optional: UUID auto-generated if omitted
        .validity(3600)
        .build());
```

### 2. Verifying the Token (Verifier Node)
```java
// Verify the token cryptographically and extract the bound payload
VerifiedData<String> result = verifier.verify(token, s -> s);

String email = result.data();           // "user@example.com"
String groupId = result.groupId();      // "user-123"
String sessionId = result.sequenceId(); // "session-A"
```

### 3. Revoking the Session (Any Node with Write Capability)
```java
// Revoke this specific session instantly across all nodes
revoker.revoke("user-123", "session-A");
```

</div>
