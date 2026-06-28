package io.github.cyfko.veridot.core.impl;

import io.github.cyfko.veridot.core.WatermarkStore;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * File-based implementation of {@link WatermarkStore} using atomic write-and-rename
 * to prevent corruption during system crashes.
 */
public final class FileWatermarkStore implements WatermarkStore {
    private static final Logger logger = Logger.getLogger(FileWatermarkStore.class.getName());
    private final File file;

    public FileWatermarkStore(File file) {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        this.file = file;
    }

    @Override
    public void save(byte[] snapshot) {
        if (snapshot == null) return;
        File tmpFile = new File(file.getAbsolutePath() + ".tmp");
        try {
            // Write to temp file
            Files.write(tmpFile.toPath(), snapshot);
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
            return Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to read watermarks from " + file.getAbsolutePath(), e);
            return null;
        }
    }
}
