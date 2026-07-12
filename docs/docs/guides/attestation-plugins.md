---
title: Attestation & Plugins
description: Understand how Veridot proves identity using attestation, and learn how to write a custom Pluggable Attestor for the TAAS cluster.
keywords: [attestation, plugin, SPI, taas, trust, veridot, kubernetes, tpm]
sidebar_position: 11
---

# Attestation & Plugins

The core of Veridot V5's "Zero Shared Secret" model is **Attestation-First Identity**. But what does that actually mean?

## The Passport Analogy (For Beginners)

Imagine a highly secure building (your microservice architecture). 
Traditional systems use **Shared Secrets**: the building manager gives you a badge (a symmetric key or long-lived token). If someone steals your badge, they are *you*.

Veridot V5 works like a **Customs Office** (the TAAS cluster):
1. You show up with your own face (your mathematically generated **Public Key**).
2. You hand the customs officer an official, unforgeable **Passport** issued by your country. This is your **Attestation Proof**.
3. The officer checks the passport's watermark, verifies the photo matches your face, and then officially registers you in the ledger (the **Raft Consensus**). 

In Veridot, your application generates its own key, but TAAS only trusts it if it provides a valid Attestation Proof (a Kubernetes Token, a TPM quote, etc.). 

---

## 1. Client-Side: Providing the Proof

Before a microservice can issue tokens, it must register its generated public key with the TAAS cluster. To do this, the client must supply the proof.

If you are using the built-in Kubernetes support, the client automatically reads the `/var/run/secrets/kubernetes.io/serviceaccount/token` file. 

You can explicitly configure how your instance fetches its proof using the `AttestationProvider`:

```java
DataSigner signer = DataSigner.create(
    BasicConfigurer.builder()
        .taasEndpoint("https://taas.internal:8443")
        .attestationProvider(new KubernetesAttestationProvider()) // Fetch K8s token
        .build()
);
```

For a custom environment (e.g., a proprietary cloud), you implement the `AttestationProvider` interface to return your custom proof bytes:

```java
public class MyCloudAttestationProvider implements AttestationProvider {
    @Override
    public byte[] getProof() {
        // Fetch identity document from local metadata API
        return HttpClient.get("http://169.254.169.254/latest/dynamic/instance-identity/document");
    }
}
```

---

## 2. Server-Side: Verifying the Proof (TAAS)

When the TAAS Server receives the registration request, it must verify the proof before persisting the key. Because environments vary wildly (AWS, GCP, Kubernetes, Bare Metal), the TAAS Server uses **Pluggable Attestation Modules**.

### Built-in Plugins
The TAAS server ships with several built-in plugins, configured in the `taas-server.yaml`:

```yaml
taas:
  attestation:
    modules:
      - "kubernetes" # Verifies Kubernetes Service Account JWTs using the K8s API
      - "tpm"        # Verifies hardware TPM quotes
```

### Writing a Custom Attestation Plugin (SPI)

If your enterprise uses a custom trust anchor, you can write a Java plugin using the Service Provider Interface (SPI).

**Step 1: Implement the `AttestationPlugin` Interface**

```java
package com.mycompany.veridot.plugin;

import io.github.cyfko.veridot.taas.spi.AttestationPlugin;
import io.github.cyfko.veridot.taas.spi.AttestationResult;
import io.github.cyfko.veridot.taas.spi.AttestationContext;

public class MyCloudAttestationPlugin implements AttestationPlugin {

    @Override
    public String getPluginId() {
        return "my-cloud-v1";
    }

    @Override
    public AttestationResult verify(byte[] proof, byte[] publicKey, AttestationContext ctx) {
        try {
            // 1. Parse the proof (e.g., your custom JSON identity document)
            MyIdentityDoc doc = parse(proof);

            // 2. Validate the document against your Cloud API
            boolean isValid = verifyWithCloudProvider(doc);
            if (!isValid) {
                return AttestationResult.rejected("Cloud API rejected identity document");
            }

            // 3. Derive the logical Subject from the proof
            // Example: CN@hash(pk) where CN is the Cloud Instance ID
            String subject = doc.getInstanceId() + "@" + hash(publicKey);

            // 4. Return success! TAAS will now persist this identity
            return AttestationResult.accepted(subject);

        } catch (Exception e) {
            return AttestationResult.rejected("Malformed proof: " + e.getMessage());
        }
    }
}
```

**Step 2: Register the SPI**

Create a file in your TAAS server classpath at `META-INF/services/io.github.cyfko.veridot.taas.spi.AttestationPlugin`:
```text
com.mycompany.veridot.plugin.MyCloudAttestationPlugin
```

**Step 3: Enable it in `taas-server.yaml`**

```yaml
taas:
  attestation:
    modules:
      - "my-cloud-v1"
```

## Security Best Practices

1. **Strict Binding**: Your `AttestationPlugin` must cryptographically guarantee that the `publicKey` provided in the request is the exact same key that the proof was generated for. If an attacker can submit *their* public key alongside *your* valid proof, the system is compromised.
2. **Auditability**: The TAAS Server automatically logs rejected attestations. For highly sensitive environments, configure your plugin to archive successful proofs (`proof` bytes) into a Cold Storage S3 bucket for forensic analysis.
