package io.github.cyfko.veridot.core.impl;

import java.security.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Manages the generation, thread-safe access, and background rotation of ephemeral RSA key pairs.
 */
class KeyRotationService {
    private static final Logger logger = Logger.getLogger(KeyRotationService.class.getName());

    private final ScheduledExecutorService scheduler;
    private volatile KeyPair currentKeyPair;

    public KeyRotationService() {
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
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(Config.ASYMMETRIC_KEY_SIZE, new SecureRandom());
            this.currentKeyPair = gen.generateKeyPair();
            logger.info("Ephemeral RSA-" + Config.ASYMMETRIC_KEY_SIZE 
                    + " key pair rotated successfully on thread: " + Thread.currentThread().getName());
        } catch (NoSuchAlgorithmException e) {
            logger.severe("CRITICAL: Failed to generate ephemeral key pair: " + e.getMessage());
            throw new RuntimeException("Key generation failed", e);
        }
    }

    public PublicKey getPublicKey() {
        return currentKeyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return currentKeyPair.getPrivate();
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
