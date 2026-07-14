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
import java.util.logging.Logger;

/**
 * Implémentation du cache local L2 persistant utilisant RocksDB.
 * <p>
 * Ce stockage local utilise trois Column Families (familles de colonnes) distinctes :
 * <ul>
 *     <li>{@code entries} : Contient les objets {@link TrustEntry} sérialisés en JSON. Clé composite : {@code <subject_bytes> + 0x00 + <version_bytes>}.</li>
 *     <li>{@code subjects} : Mappe chaque sujet vers sa version la plus récente (8 octets big-endian).</li>
 *     <li>{@code meta} : Stocke les métadonnées globales (version de schéma, date de dernière synchronisation).</li>
 * </ul>
 */
public class RocksDbL2Cache implements L2Cache {

    /** Logger. */
    private static final Logger logger = Logger.getLogger(RocksDbL2Cache.class.getName());

    /** V5 schema version. V1 = original (V4), V2 = V5 with instance-scoped, attestation, and KEM fields. */
    private static final int CURRENT_SCHEMA_VERSION = 2;

    static {
        RocksDB.loadLibrary();
    }

    /** Instance de base de données RocksDB. */
    private final RocksDB db;
    
    /** Options de configuration de la base de données. */
    private final DBOptions dbOptions;
    
    /** Liste des descripteurs de familles de colonnes ouverts pour libération à la fermeture. */
    private final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
    
    /** Descripteur par défaut RocksDB. */
    private ColumnFamilyHandle defaultHandle;
    
    /** Descripteur contenant les Trust Entries associées à leurs clés composites. */
    private ColumnFamilyHandle entriesHandle;
    
    /** Descripteur pointant vers la version la plus récente de chaque sujet. */
    private ColumnFamilyHandle subjectsHandle;
    
    /** Descripteur de métadonnées (dernière synchronisation, version de schéma). */
    private ColumnFamilyHandle metaHandle;
    
    /** Mapper Jackson thread-safe configuré pour Java Time. */
    private final ObjectMapper objectMapper;

    /**
     * Initialise le cache local L2 dans le répertoire spécifié.
     * Si le répertoire ou les familles de colonnes n'existent pas, ils sont créés automatiquement.
     *
     * @param path Chemin absolu du répertoire de stockage local.
     * @throws TrustRootInitializationException si l'initialisation de RocksDB échoue.
     */
    public RocksDbL2Cache(String path) throws TrustRootInitializationException {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new TrustRootInitializationException("Failed to create RocksDB directory: " + path);
        }

        // Étape 1 : Lister les familles de colonnes existantes dans le répertoire
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

        // Étape 2 : Préparer les descripteurs pour l'ouverture
        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions()));
        if (hasEntries) descriptors.add(new ColumnFamilyDescriptor("entries".getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));
        if (hasSubjects) descriptors.add(new ColumnFamilyDescriptor("subjects".getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));
        if (hasMeta) descriptors.add(new ColumnFamilyDescriptor("meta".getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));

        this.dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);

        // Étape 3 : Ouvrir RocksDB avec toutes ses familles de colonnes
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
            
            // Étape 4 : Initialiser ou migrer la version de schéma de métadonnées
            byte[] schemaKey = "schema_version".getBytes(StandardCharsets.UTF_8);
            byte[] schemaVerBytes = db.get(metaHandle, schemaKey);
            if (schemaVerBytes == null) {
                // Fresh install — write current V5 schema version
                db.put(metaHandle, schemaKey, toBigEndian4(CURRENT_SCHEMA_VERSION));
            } else {
                int existingVersion = fromBigEndian4(schemaVerBytes);
                if (existingVersion < CURRENT_SCHEMA_VERSION) {
                    // V4 → V5 migration: TrustEntry JSON is backward-compatible via @JsonIgnoreProperties;
                    // new V5 fields (isInstanceScoped, attestationPlugin, kemPublicKey) will be populated
                    // on next sync from the TAD provider.
                    logger.info("Migrating RocksDB L2 cache schema from version " + existingVersion
                        + " to " + CURRENT_SCHEMA_VERSION + " (V5). Existing entries remain readable.");
                    db.put(metaHandle, schemaKey, toBigEndian4(CURRENT_SCHEMA_VERSION));
                }
            }
        } catch (RocksDBException e) {
            throw new TrustRootInitializationException("Failed to open RocksDB L2 cache at " + path, e);
        }
    }

    /**
     * Convertit un entier en tableau de 4 octets Big-Endian (sérialisation manuelle rapide).
     */
    private byte[] toBigEndian4(int val) {
        return new byte[] {
            (byte) (val >>> 24),
            (byte) (val >>> 16),
            (byte) (val >>> 8),
            (byte) val
        };
    }

    /**
     * Reconstitue un entier à partir d'un tableau de 4 octets Big-Endian.
     */
    private int fromBigEndian4(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
               ((bytes[1] & 0xFF) << 16) |
               ((bytes[2] & 0xFF) << 8) |
               (bytes[3] & 0xFF);
    }
    
    /**
     * Convertit un long en tableau de 8 octets Big-Endian.
     *
     * @param val The long value.
     * @return The 8-byte array.
     */
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

    /**
     * Reconstitue un long à partir d'un tableau de 8 octets Big-Endian.
     *
     * @param bytes The 8-byte array.
     * @return The long value.
     */
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

    /**
     * Construit une clé composite unique pour la table {@code entries} :
     * {@code <subject_bytes> + 0x00 + <version_bytes_8>}.
     * Ce format évite les collisions tout en permettant des requêtes ordonnées par version.
     *
     * @param subject The subject.
     * @param version The version.
     * @return The composite key bytes.
     */
    private byte[] toCompositeKey(String subject, long version) {
        byte[] subjBytes = subject.getBytes(StandardCharsets.UTF_8);
        byte[] composite = new byte[subjBytes.length + 1 + 8];
        System.arraycopy(subjBytes, 0, composite, 0, subjBytes.length);
        composite[subjBytes.length] = 0; // Séparateur d'octets null
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

            // 1. Écriture dans le registre des entrées
            batch.put(entriesHandle, compositeKey, entryBytes);

            // 2. Mise à jour de l'index des versions actives si cette entrée est plus récente
            byte[] currentVersionBytes = db.get(subjectsHandle, subjKey);
            long currentVersion = currentVersionBytes == null ? 0 : fromBigEndian8(currentVersionBytes);

            if (entry.version() >= currentVersion) {
                batch.put(subjectsHandle, subjKey, toBigEndian8(entry.version()));
            }

            // Écriture atomique dans RocksDB (sync désactivé pour la vitesse, L2 restant un cache secondaire)
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
            // Silence
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
