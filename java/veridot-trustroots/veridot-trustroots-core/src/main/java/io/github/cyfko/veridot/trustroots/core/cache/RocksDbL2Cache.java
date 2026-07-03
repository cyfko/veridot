package io.github.cyfko.veridot.trustroots.core.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import io.github.cyfko.veridot.trustroots.api.exception.TrustRootInitializationException;
import org.rocksdb.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Implémentation du cache L2 persisté localement avec RocksDB.
 */
public class RocksDbL2Cache implements L2Cache {
    static {
        RocksDB.loadLibrary();
    }

    private final RocksDB db;
    private final DBOptions dbOptions;
    private final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
    private ColumnFamilyHandle defaultHandle;
    private ColumnFamilyHandle entriesHandle;
    private ColumnFamilyHandle subjectsHandle;
    private ColumnFamilyHandle metaHandle;
    private final ObjectMapper objectMapper;

    public RocksDbL2Cache(String path) throws TrustRootInitializationException {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new TrustRootInitializationException("Failed to create RocksDB directory: " + path);
        }

        List<byte[]> cfList;
        try (Options listOptions = new Options()) {
            cfList = RocksDB.listColumnFamilies(listOptions, path);
        } catch (RocksDBException e) {
            cfList = new ArrayList<>();
        }

        boolean hasEntries = false;
        boolean hasSubjects = false;
        boolean hasMeta = false;
        for (byte[] cfName : cfList) {
            String s = new String(cfName, StandardCharsets.UTF_8);
            if (s.equals("entries")) hasEntries = true;
            else if (s.equals("subjects")) hasSubjects = true;
            else if (s.equals("meta")) hasMeta = true;
        }

        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions()));
        if (hasEntries) descriptors.add(new ColumnFamilyDescriptor("entries".getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));
        if (hasSubjects) descriptors.add(new ColumnFamilyDescriptor("subjects".getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));
        if (hasMeta) descriptors.add(new ColumnFamilyDescriptor("meta".getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));

        this.dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);

        try {
            this.db = RocksDB.open(dbOptions, path, descriptors, cfHandles);
            this.defaultHandle = cfHandles.get(0);
            
            int idx = 1;
            if (hasEntries) {
                this.entriesHandle = cfHandles.get(idx++);
            } else {
                this.entriesHandle = db.createColumnFamily(new ColumnFamilyDescriptor("entries".getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));
                cfHandles.add(entriesHandle);
            }

            if (hasSubjects) {
                this.subjectsHandle = cfHandles.get(idx++);
            } else {
                this.subjectsHandle = db.createColumnFamily(new ColumnFamilyDescriptor("subjects".getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));
                cfHandles.add(subjectsHandle);
            }

            if (hasMeta) {
                this.metaHandle = cfHandles.get(idx++);
            } else {
                this.metaHandle = db.createColumnFamily(new ColumnFamilyDescriptor("meta".getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));
                cfHandles.add(metaHandle);
            }
            
            byte[] schemaVerBytes = db.get(metaHandle, "schema_version".getBytes(StandardCharsets.UTF_8));
            if (schemaVerBytes == null) {
                db.put(metaHandle, "schema_version".getBytes(StandardCharsets.UTF_8), toBigEndian4(1));
            }
        } catch (RocksDBException e) {
            throw new TrustRootInitializationException("Failed to open RocksDB L2 cache at " + path, e);
        }
    }

    private byte[] toBigEndian4(int val) {
        return new byte[] {
            (byte) (val >>> 24),
            (byte) (val >>> 16),
            (byte) (val >>> 8),
            (byte) val
        };
    }
    
    private byte[] toBigEndian8(long val) {
        return new byte[] {
            (byte) (val >>> 56),
            (byte) (val >>> 48),
            (byte) (val >>> 40),
            (byte) (val >>> 32),
            (byte) (val >>> 24),
            (byte) (val >>> 16),
            (byte) (val >>> 8),
            (byte) val
        };
    }

    private long fromBigEndian8(byte[] bytes) {
        return ((long) (bytes[0] & 0xFF) << 56) |
               ((long) (bytes[1] & 0xFF) << 48) |
               ((long) (bytes[2] & 0xFF) << 40) |
               ((long) (bytes[3] & 0xFF) << 32) |
               ((long) (bytes[4] & 0xFF) << 24) |
               ((long) (bytes[5] & 0xFF) << 16) |
               ((long) (bytes[6] & 0xFF) << 8) |
               ((long) (bytes[7] & 0xFF) & 0xFF);
    }

    private byte[] toCompositeKey(String subject, long version) {
        byte[] subjBytes = subject.getBytes(StandardCharsets.UTF_8);
        byte[] composite = new byte[subjBytes.length + 1 + 8];
        System.arraycopy(subjBytes, 0, composite, 0, subjBytes.length);
        composite[subjBytes.length] = 0; // null separator
        byte[] verBytes = toBigEndian8(version);
        System.arraycopy(verBytes, 0, composite, subjBytes.length + 1, 8);
        return composite;
    }

    @Override
    public Optional<TrustEntry> get(String subject) {
        try {
            byte[] key = subject.getBytes(StandardCharsets.UTF_8);
            byte[] versionBytes = db.get(subjectsHandle, key);
            if (versionBytes == null) {
                return Optional.empty();
            }
            long version = fromBigEndian8(versionBytes);
            byte[] compositeKey = toCompositeKey(subject, version);
            byte[] entryBytes = db.get(entriesHandle, compositeKey);
            if (entryBytes == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(entryBytes, TrustEntry.class));
        } catch (RocksDBException | IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void put(TrustEntry entry) {
        try (WriteBatch batch = new WriteBatch()) {
            byte[] subjKey = entry.subject().getBytes(StandardCharsets.UTF_8);
            byte[] compositeKey = toCompositeKey(entry.subject(), entry.version());
            byte[] entryBytes = objectMapper.writeValueAsBytes(entry);

            batch.put(entriesHandle, compositeKey, entryBytes);

            byte[] currentVersionBytes = db.get(subjectsHandle, subjKey);
            long currentVersion = currentVersionBytes == null ? 0 : fromBigEndian8(currentVersionBytes);

            if (entry.version() >= currentVersion) {
                batch.put(subjectsHandle, subjKey, toBigEndian8(entry.version()));
            }

            db.write(new WriteOptions().setSync(false), batch);
        } catch (RocksDBException | IOException e) {
            throw new RuntimeException("Failed to write to RocksDB L2 Cache", e);
        }
    }

    @Override
    public List<TrustEntry> loadAll() {
        List<TrustEntry> list = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(subjectsHandle)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                byte[] subjKey = iterator.key();
                byte[] versionBytes = iterator.value();
                
                String subject = new String(subjKey, StandardCharsets.UTF_8);
                long version = fromBigEndian8(versionBytes);
                byte[] compositeKey = toCompositeKey(subject, version);
                byte[] entryBytes = db.get(entriesHandle, compositeKey);
                if (entryBytes != null) {
                    list.add(objectMapper.readValue(entryBytes, TrustEntry.class));
                }
                iterator.next();
            }
        } catch (RocksDBException | IOException e) {
            // Silent error or log
        }
        return list;
    }

    @Override
    public Optional<Instant> lastSyncTime() {
        try {
            byte[] syncBytes = db.get(metaHandle, "last_sync_time".getBytes(StandardCharsets.UTF_8));
            if (syncBytes == null) return Optional.empty();
            long epochMillis = fromBigEndian8(syncBytes);
            return Optional.of(Instant.ofEpochMilli(epochMillis));
        } catch (RocksDBException e) {
            return Optional.empty();
        }
    }

    @Override
    public void markSyncTime(Instant time) {
        try {
            db.put(metaHandle, "last_sync_time".getBytes(StandardCharsets.UTF_8), toBigEndian8(time.toEpochMilli()));
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to update last_sync_time in RocksDB", e);
        }
    }

    @Override
    public long estimatedSize() {
        try {
            return db.getLongProperty(subjectsHandle, "rocksdb.estimate-num-keys");
        } catch (RocksDBException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        for (ColumnFamilyHandle handle : cfHandles) {
            handle.close();
        }
        if (db != null) {
            db.close();
        }
        if (dbOptions != null) {
            dbOptions.close();
        }
    }
}
