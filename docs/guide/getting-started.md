# Getting Started

This guide walks you through setting up Veridot and running your first token issue-verify-revoke lifecycle in Java. For demonstration purposes, we will use an in-memory broker and a static trust root.

---

## 1. Complete Quickstart Code

Here is a fully compiling Java class that illustrates the core verification pipeline.

```java
package io.github.cyfko.example;

import io.github.cyfko.veridot.core.*;
import io.github.cyfko.veridot.core.impl.BasicConfigurer;
import io.github.cyfko.veridot.core.impl.GenericSignerVerifier;
import io.github.cyfko.veridot.core.impl.InMemoryBroker;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Map;

public class VeridotQuickstart {

    public static void main(String[] args) throws Exception {
        // Step 1: Generate cryptographic keys
        // We generate a long-term root key used to sign verification envelopes.
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair rootKeyPair = kpg.generateKeyPair();
        PublicKey rootPublicKey = rootKeyPair.getPublic();
        PrivateKey rootPrivateKey = rootKeyPair.getPrivate();

        String issuerId = "admin-signer";

        // Step 2: Initialize dependencies
        // - Broker: In-memory store (for testing)
        // - TrustRoot: Resolves issuerId to the root public key
        Broker broker = new InMemoryBroker();
        TrustRoot trustRoot = new PublicKeyTrustRoot() {
            @Override
            public TrustIdentity resolve(String issuer) {
                if (issuerId.equals(issuer)) {
                    return new TrustIdentity(rootPublicKey, true);
                }
                return null;
            }
        };

        // Step 3: Instantiate the Orchestrator
        // GenericSignerVerifier implements DataSigner, TokenVerifier, TokenRevoker, TokenTracker
        GenericSignerVerifier orchestrator = new GenericSignerVerifier(
                broker,
                trustRoot,
                issuerId,
                rootPrivateKey,
                Algorithm.ED25519
        );

        // Step 4: Define a payload class
        record UserSession(String email, String role) {}
        UserSession userPayload = new UserSession("alice@example.com", "ADMIN");

        System.out.println("--- 1. ISSUING TOKEN ---");
        // Step 5: Issue a token (Direct mode)
        // This signs the token with an ephemeral key and publishes KEY_EPOCH & LIVENESS entries.
        String token = orchestrator.sign(userPayload,
                BasicConfigurer.builder()
                        .groupId("user-alice")
                        .validity(3600) // Valid for 1 hour (3600 seconds)
                        .build()
        );
        System.out.println("Issued Token (JWT): " + token);

        System.out.println("\n--- 2. TRACKING ACTIVE STATUS ---");
        // Step 6: Query liveness before verification
        boolean isActive = orchestrator.hasActiveToken("user-alice");
        System.out.println("Group 'user-alice' has active session? " + isActive); // true

        System.out.println("\n--- 3. VERIFYING TOKEN ---");
        // Step 7: Verify token and extract payload
        // This fetches metadata, runs capability checks, watermark checks, and signature verification.
        VerifiedData<UserSession> verified = orchestrator.verify(
                token,
                BasicConfigurer.deserializer(UserSession.class)
        );
        System.out.println("Verification Succeeded!");
        System.out.println("Extracted GroupId: " + verified.groupId());
        System.out.println("Extracted SequenceId: " + verified.sequenceId());
        System.out.println("Payload: " + verified.data().email() + " (" + verified.data().role() + ")");

        System.out.println("\n--- 4. REVOKING SESSION ---");
        // Step 8: Revoke session using the extracted identifiers
        orchestrator.revoke(verified.groupId(), verified.sequenceId());
        System.out.println("Revoked session: " + verified.sequenceId());

        System.out.println("\n--- 5. CHECKING LIVENESS POST-REVOCATION ---");
        // Step 9: Re-verify liveness
        boolean isActivePostRevoke = orchestrator.hasActiveToken("user-alice");
        System.out.println("Group 'user-alice' has active session? " + isActivePostRevoke); // false

        try {
            // Step 10: Verification attempt must fail
            orchestrator.verify(token, BasicConfigurer.deserializer(UserSession.class));
        } catch (Exception e) {
            System.out.println("Verification correctly failed: " + e.getMessage());
        }

        // Clean up scheduler threads
        orchestrator.close();
    }
}
```

---

## 2. Explanation of Key Concepts

### Key Entities
1. **`Broker`**: The messaging/database transport layer. In production, this would be `KafkaBroker` or `DatabaseBroker`.
2. **`TrustRoot`**: The out-of-band trust store that validates long-term keys. Here we map `"admin-signer"` directly to `rootPublicKey`.
3. **`GenericSignerVerifier`**: The orchestrator which binds everything together. It signs payloads, publishes metadata to the broker, runs renewal loops for liveness in the background, and verifies tokens.
4. **`BasicConfigurer`**: A fluent builder that configures session time-to-live (TTL), serialization, and group scoping.
