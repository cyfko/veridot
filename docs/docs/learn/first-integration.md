---
title: "Chapter 3: Your First Sign-Verify-Revoke"
description: "Build a working Veridot V5 integration in 15 minutes."
sidebar_position: 3
pagination_prev: learn/how-veridot-works
pagination_next: learn/going-distributed
---

# Your First Sign-Verify-Revoke

Let's make this concrete. We'll simulate **ShopFlow**, an e-commerce platform using Veridot Protocol V5.

## 1. Instance Registration

First, our `order-service` spins up, generates an in-memory keypair, and registers with the TAAS using an attestation proof.

```java
// Generate ephemeral key
KeyPair keyPair = Crypto.generateEd25519();

// Submit to TAAS with attestation
Identity subject = taasClient.register("orders-api", keyPair.getPublic(), attestationProof);
// subject becomes a TrustIdentity
```

## 2. Signing and Liveness

To issue a token, the instance asserts it is alive and signs the data. 

```java
// 1. Publish LIVENESS
broker.put(new LivenessEntry(subject, Liveness.ACTIVE));

// 2. Sign data and publish to broker
String payload = "{"orderId": "12345"}";
NativeToken token = veridot.signNative("group:orders", "session-1", payload);

// Returns a compact string: "8:group:orders:session-1"
return token.asString();
```
Notice we are using the **NATIVE** distribution mode (`8:<scope>:<key>`).

## 3. Verification

The `shipping-service` receives the string `8:group:orders:session-1`.

```java
try {
    VerifiedData data = veridot.verify("8:group:orders:session-1");
    System.out.println("Verified payload: " + data.getPayload());
} catch (VeridotException e) {
    // Fails with a V5xxx error code if invalid or revoked
}
```

Behind the scenes, Veridot resolves the signer's identity from the TrustRoot (producing a TrustIdentity), reads the payload and liveness from the broker cache, and mathematically validates the signature.

## 4. Instant Revocation

If the session is compromised, the original instance (or an admin holding the right capability) publishes a revocation:

```java
broker.put(new LivenessEntry(subject, Liveness.REVOKED));
```

The moment that `REVOKED` entry propagates (usually milliseconds), `verify` will throw a `V5005` or contextually appropriate validation error. Since versions strictly increase, a compromised broker cannot replay the old `ACTIVE` state!
