---
layout: home
title: Enterprise-Grade Distributed Token Verification
---

# Veridot

[![Java Version](https://img.shields.io/badge/Java-17%2B-ED8B00.svg)](https://openjdk.java.net/)
[![Node.js Version](https://img.shields.io/badge/Node.js-16%2B-43853D.svg)](https://nodejs.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.cyfko/veridot-core)](https://central.sonatype.com/search?q=io.github.cyfko.veridot)
[![npm](https://img.shields.io/npm/v/dverify)](https://www.npmjs.com/package/dverify)

**Veridot** is a production-ready, multi-language library for secure, distributed token verification in microservices architectures. Built for enterprise environments where services need to verify cryptographically signed tokens without shared secrets or centralized infrastructure.

## 🚀 Quick Start

### Java
```xml
<dependency>
    <groupId>io.github.cyfko</groupId>
    <artifactId>veridot-core</artifactId>
    <version>2.0.1</version>
</dependency>
```

### Node.js
```bash
npm install dverify
```

## 🎯 Why Choose Veridot?

<div class="feature-grid">
  <div class="feature">
    <h3>🔐 Zero Shared Secrets</h3>
    <p>Ephemeral asymmetric keys eliminate the security risks of shared secrets</p>
  </div>
  
  <div class="feature">
    <h3>⚡ High Performance</h3>
    <p>Sub-millisecond token verification with intelligent local caching</p>
  </div>
  
  <div class="feature">
    <h3>🌐 Distributed by Design</h3>
    <p>No centralized dependencies or single points of failure</p>
  </div>
  
  <div class="feature">
    <h3>🔄 Automatic Key Management</h3>
    <p>Self-managing key rotation with configurable intervals</p>
  </div>
</div>

## 📖 Documentation

<div class="doc-links">
  <a href="{{ '/docs/getting-started' | relative_url }}" class="doc-link">
    <h3>Getting Started</h3>
    <p>Installation and basic setup for Java and Node.js</p>
  </a>
  
  <a href="{{ '/docs/java-guide' | relative_url }}" class="doc-link">
    <h3>Java Guide</h3>
    <p>Complete Java implementation guide with Spring integration</p>
  </a>
  
  <a href="{{ '/docs/nodejs-guide' | relative_url }}" class="doc-link">
    <h3>Node.js Guide</h3>
    <p>TypeScript/JavaScript usage with environment configuration</p>
  </a>
  
  <a href="{{ '/docs/api-reference' | relative_url }}" class="doc-link">
    <h3>API Reference</h3>
    <p>Complete API documentation for all interfaces and classes</p>
  </a>
</div>

## 🏢 Enterprise Ready

Veridot is built for production environments with:
- **Comprehensive monitoring hooks** for observability
- **Flexible broker implementations** (Kafka, Database, Custom)
- **Battle-tested cryptography** (RSA-2048, ECDSA P-256)
- **Enterprise support** available from [Kunrin SA](https://www.kunrin.com)

---

<div class="cta-section">
  <h2>Ready to get started?</h2>
  <p>Choose your preferred language and dive into the documentation.</p>
  <div class="cta-buttons">
    <a href="{{ '/docs/getting-started' | relative_url }}" class="btn btn-primary">Get Started</a>
    <a href="https://github.com/cyfko/veridot" class="btn btn-secondary">View on GitHub</a>
  </div>
</div>

<style>
.feature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 2rem;
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
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 1.5rem;
  margin: 2rem 0;
}

.doc-link {
  display: block;
  padding: 1.5rem;
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
}

.cta-section {
  text-align: center;
  padding: 3rem 0;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-radius: 8px;
  margin: 3rem 0;
}

.cta-buttons {
  margin-top: 2rem;
}

.btn {
  display: inline-block;
  padding: 0.75rem 1.5rem;
  margin: 0 0.5rem;
  border-radius: 4px;
  text-decoration: none;
  font-weight: 500;
  transition: all 0.2s ease;
}

.btn-primary {
  background-color: #007bff;
  color: white;
  border: 2px solid #007bff;
}

.btn-primary:hover {
  background-color: #0056b3;
  border-color: #0056b3;
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
  color: #667eea;
  text-decoration: none;
}

@media (prefers-color-scheme: dark) {
  .feature {
    background: #2d3748;
    border-color: #4a5568;
  }
  
  .feature h3 {
    color: #e2e8f0;
  }
  
  .doc-link {
    background: #2d3748;
    border-color: #4a5568;
    color: #e2e8f0;
  }
  
  .doc-link:hover {
    border-color: #4299e1;
  }
  
  .doc-link h3 {
    color: #4299e1;
  }
}
</style>