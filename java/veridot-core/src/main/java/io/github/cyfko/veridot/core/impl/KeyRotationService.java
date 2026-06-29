package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.Algorithm;
import java.security.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Manages the generation, atomic thread-safe access, and background rotation of ephemeral signing key pairs (F-04).
 */
public final class KeyRotationService {
    private static final Logger logger = Logger.getLogger(KeyRotationService.class.getName());

    private final ScheduledExecutorService scheduler;
    private final Algorithm alg; // Ephemeral key type
    private volatile KeySnapshot currentSnapshot;

    public record KeySnapshot(PrivateKey privateKey, PublicKey publicKey, Algorithm alg) {}

    public KeyRotationService() {
        this(Algorithm.ED25519); // Default to Ed25519 as per §13.1
    }

    @Deprecated
    public KeyRotationService(byte algCode) {
        this(Algorithm.fromCode(algCode));
    }

    public KeyRotationService(Algorithm alg) {
        if (alg == null) {
            throw new IllegalArgumentException("Algorithm cannot be null");
        }
        this.alg = alg;
        generateKeyPair();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.scheduler.scheduleAtFixedRate(
                this::generateKeyPair,
                Config.KEYS_ROTATION_MINUTES,
                Config.KEYS_ROTATION_MINUTES,
                TimeUnit.MINUTES
        );
    }

    private void generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(alg.getJcaKeyAlg());
            if (alg == Algorithm.RSA_SHA256 || alg == Algorithm.RSA_PSS) {
                gen.initialize(Config.ASYMMETRIC_KEY_SIZE, new SecureRandom());
            } else if (alg == Algorithm.ECDSA_SHA256) {
                gen.initialize(256, new SecureRandom()); // Standard secp256r1 curve
            }

            KeyPair keyPair = gen.generateKeyPair();
            // F-04: capture atomically as a single record
            this.currentSnapshot = new KeySnapshot(keyPair.getPrivate(), keyPair.getPublic(), alg);

            logger.info("Ephemeral key pair rotated successfully. Algorithm: " + alg.name() 
                        + " on thread: " + Thread.currentThread().getName());
        } catch (NoSuchAlgorithmException e) {
            logger.severe("CRITICAL: Failed to generate ephemeral key pair: " + e.getMessage());
            throw new RuntimeException("Key generation failed", e);
        }
    }

    /**
     * Atomically returns the current key snapshot (PrivateKey, PublicKey, Algorithm).
     * Prevents race condition where public/private key read might refer to different rotations (F-04).
     */
    public KeySnapshot snapshot() {
        return currentSnapshot;
    }

    public PublicKey getPublicKey() {
        return currentSnapshot.publicKey();
    }

    public PrivateKey getPrivateKey() {
        return currentSnapshot.privateKey();
    }

    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
