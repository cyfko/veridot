package io.github.cyfko.veridot.trustroots.core;

import io.github.cyfko.veridot.core.TrustIdentity;
import io.github.cyfko.veridot.trustroots.api.KeyAlgorithm;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.api.TrustRootProvider;
import io.github.cyfko.veridot.trustroots.api.exception.TrustRootInitializationException;
import io.github.cyfko.veridot.trustroots.api.exception.TrustRootProviderException;
import io.github.cyfko.veridot.trustroots.core.validation.SignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CachingTrustRootTest {

    private KeyPair keyPair;
    private String publicKeyEncoded;
    private String signatureStr;
    private TrustEntry validEntry;

    @BeforeEach
    void setUp() throws Exception {
        // Generate Ed25519 KeyPair
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        keyPair = kpg.generateKeyPair();
        publicKeyEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.getPublic().getEncoded());

        // Prepare a valid TrustEntry
        String subject = "test-service";
        long version = 1;
        Instant notBefore = Instant.now().minus(Duration.ofHours(1));
        Instant notAfter = Instant.now().plus(Duration.ofHours(24));
        
        String canonicalPayload = subject + "\n"
                + publicKeyEncoded + "\n"
                + KeyAlgorithm.ED25519.identifier() + "\n"
                + notBefore.toString() + "\n"
                + notAfter.toString() + "\n"
                + version;
        
        Signature sig = Signature.getInstance("EdDSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(canonicalPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        signatureStr = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());

        String fingerprint = SignatureVerifier.computeFingerprint(publicKeyEncoded);

        validEntry = new TrustEntry(
            1,
            subject,
            publicKeyEncoded,
            KeyAlgorithm.ED25519,
            notBefore,
            notAfter,
            version,
            fingerprint,
            signatureStr,
            Instant.now(),
            false,
            Collections.emptyMap()
        );
    }

    @Test
    void testResolveSuccess(@TempDir Path tempDir) throws Exception {
        // Mock provider returning the entry in modified since
        TrustRootProvider provider = new TrustRootProvider() {
            @Override
            public Optional<TrustEntry> fetch(String subject) {
                return Optional.of(validEntry);
            }

            @Override
            public List<TrustEntry> fetchModifiedSince(Instant since) {
                return List.of(validEntry);
            }

            @Override
            public String name() {
                return "MockProvider";
            }
        };

        // Build CachingTrustRoot
        CachingTrustRoot cachingTrustRoot = CachingTrustRoot.builder()
                .provider(provider)
                .l2Directory(tempDir)
                .refreshThreshold(Duration.ofMinutes(10))
                .staleWindow(Duration.ofMinutes(5))
                .fullSyncInterval(Duration.ofHours(1))
                .build();

        cachingTrustRoot.initialize();

        // Resolve subject
        TrustIdentity identity = cachingTrustRoot.resolve("test-service");
        assertNotNull(identity);
        assertNotNull(identity.publicKey());
        assertFalse(identity.isRoot());
        
        // Assert public key equivalence
        assertArrayEquals(keyPair.getPublic().getEncoded(), identity.publicKey().getEncoded());

        cachingTrustRoot.close();
    }

    @Test
    void testResolveMissThrowsException(@TempDir Path tempDir) throws Exception {
        // Mock failing provider for sync
        TrustRootProvider provider = new TrustRootProvider() {
            @Override
            public Optional<TrustEntry> fetch(String subject) {
                return Optional.empty();
            }

            @Override
            public List<TrustEntry> fetchModifiedSince(Instant since) {
                throw new TrustRootProviderException("Provider unavailable");
            }

            @Override
            public String name() {
                return "MockProvider";
            }
        };

        // Build CachingTrustRoot
        CachingTrustRoot cachingTrustRoot = CachingTrustRoot.builder()
                .provider(provider)
                .l2Directory(tempDir)
                .build();

        // Force L2 empty bootstrap, should throw because provider throws and L2 is empty
        assertThrows(TrustRootInitializationException.class, cachingTrustRoot::initialize);
        
        cachingTrustRoot.close();
    }
}
