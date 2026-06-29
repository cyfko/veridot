package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.WatermarkStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * File-based implementation of {@link WatermarkStore} using atomic write-and-rename
 * to prevent corruption during system crashes.
 */
public final class FileWatermarkStore implements WatermarkStore {
    private static final Logger logger = Logger.getLogger(FileWatermarkStore.class.getName());
    private final File file;
    private final byte[] hmacKey;

    public FileWatermarkStore(File file) {
        this(file, null);
    }

    public FileWatermarkStore(File file, byte[] hmacKey) {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        this.file = file;
        this.hmacKey = hmacKey;
    }

    @Override
    public void save(byte[] snapshot) {
        if (snapshot == null) return;
        
        byte[] dataToWrite = snapshot;
        if (hmacKey != null) {
            byte[] hmac = computeHmac(snapshot);
            if (hmac != null) {
                dataToWrite = new byte[32 + snapshot.length];
                System.arraycopy(hmac, 0, dataToWrite, 0, 32);
                System.arraycopy(snapshot, 0, dataToWrite, 32, snapshot.length);
            }
        }

        File tmpFile = new File(file.getAbsolutePath() + ".tmp");
        try {
            // Write to temp file
            Files.write(tmpFile.toPath(), dataToWrite);
            // Atomically rename to target file to prevent partial writes
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to write watermarks to " + file.getAbsolutePath(), e);
            if (tmpFile.exists()) {
                tmpFile.delete();
            }
        }
    }

    @Override
    public byte[] load() {
        if (!file.exists() || !file.isFile() || file.length() == 0) {
            return null;
        }
        try {
            byte[] allBytes = Files.readAllBytes(file.toPath());
            if (hmacKey == null) {
                return allBytes;
            }

            if (allBytes.length < 32) {
                logger.log(Level.SEVERE, "Watermark file too short (less than HMAC size): " + file.getAbsolutePath());
                return null;
            }

            byte[] storedHmac = Arrays.copyOf(allBytes, 32);
            byte[] snapshot = Arrays.copyOfRange(allBytes, 32, allBytes.length);
            byte[] computedHmac = computeHmac(snapshot);

            if (computedHmac == null || !MessageDigest.isEqual(storedHmac, computedHmac)) {
                logger.log(Level.SEVERE, "CRITICAL: Watermark snapshot file integrity check failed (tampering detected): " + file.getAbsolutePath());
                return null;
            }

            return snapshot;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to read watermarks from " + file.getAbsolutePath(), e);
            return null;
        }
    }

    private byte[] computeHmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to compute HMAC for watermarks", e);
            return null;
        }
    }
}
