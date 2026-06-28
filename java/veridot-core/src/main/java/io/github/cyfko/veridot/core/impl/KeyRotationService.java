package io.github.cyfko.veridot.core.impl;

import java.security.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Manages the generation, atomic thread-safe access, and background rotation of ephemeral signing key pairs (F-04).
 */
public final class KeyRotationService {
    private static final Logger logger = Logger.getLogger(KeyRotationService.class.getName());

    private final ScheduledExecutorService scheduler;
    private final byte alg; // 0x01 = RSA-SHA256, 0x02 = ECDSA-SHA256 (ephemeral key type)
    private volatile KeySnapshot currentSnapshot;

    public record KeySnapshot(PrivateKey privateKey, PublicKey publicKey, byte alg) {}

    public KeyRotationService() {
        this((byte) 0x01); // Default to RSA
    }

    public KeyRotationService(byte alg) {
        if (alg != 0x01 && alg != 0x02 && alg != 0x03) {
            throw new IllegalArgumentException("Unsupported ephemeral key algorithm: " + alg);
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
            KeyPairGenerator gen;
            if (alg == 0x01 || alg == 0x03) {
                gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(Config.ASYMMETRIC_KEY_SIZE, new SecureRandom());
            } else {
                gen = KeyPairGenerator.getInstance("EC");
                gen.initialize(256, new SecureRandom()); // Standard secp256r1 curve
            }

            KeyPair keyPair = gen.generateKeyPair();
            // F-04: capture atomically as a single record
            this.currentSnapshot = new KeySnapshot(keyPair.getPrivate(), keyPair.getPublic(), alg);

            logger.info("Ephemeral key pair rotated successfully. Algorithm: " + (alg == 0x02 ? "EC" : "RSA") 
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
