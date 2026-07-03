package io.github.cyfko.veridot.trustroots.core.cache;

import io.github.cyfko.veridot.trustroots.api.KeyAlgorithm;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RocksDbL2CacheTest {

    @Test
    void testPutAndGet(@TempDir Path tempDir) throws Exception {
        RocksDbL2Cache cache = new RocksDbL2Cache(tempDir.toAbsolutePath().toString());

        String subject = "test-subj";
        long version = 2;
        Instant now = Instant.now();
        
        TrustEntry entry = new TrustEntry(
            1,
            subject,
            "abc",
            KeyAlgorithm.ED25519,
            now.minus(Duration.ofHours(1)),
            now.plus(Duration.ofHours(2)),
            version,
            "finger",
            "sig",
            now,
            false,
            Collections.emptyMap()
        );

        cache.put(entry);

        Optional<TrustEntry> retrieved = cache.get(subject);
        assertTrue(retrieved.isPresent());
        assertEquals(subject, retrieved.get().subject());
        assertEquals(version, retrieved.get().version());
        assertEquals("abc", retrieved.get().publicKeyEncoded());

        cache.close();
    }
}
