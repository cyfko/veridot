package io.github.cyfko.veridot.trustroots.tad.server.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import org.rocksdb.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stockage d'état persistant persistant RocksDB pour le serveur TAD.
 * <p>
 * Ce composant gère la persistance de l'autorité de confiance répliquée. Il utilise trois familles de colonnes (Column Families)
 * pour séparer logiquement les métadonnées, l'index de version et les entrées de confiance JSON indexées par clés composites.
 * Contrairement au cache L2 client, les écritures sur le serveur TAD sont synchrones (`setSync(true)`) pour garantir la durabilité
 * requise par le consensus distribué Raft.
 */
public class TadRocksDbStore implements AutoCloseable {
    static {
        RocksDB.loadLibrary();
    }

    /** Instance RocksDB sous-jacente. */
    private final RocksDB db;
    
    /** Options globales de RocksDB. */
    private final DBOptions dbOptions;
    
    /** Liste des handles pour chaque famille de colonnes (Column Families). */
    private final List<ColumnFamilyHandle> cfHandles = new ArrayList<>();
    
    /** Handle de la famille de colonnes par défaut. */
    private ColumnFamilyHandle defaultHandle;
    
    /** Handle de la famille de colonnes {@code entries} contenant les JSON sérialisés. */
    private ColumnFamilyHandle entriesHandle;
    
    /** Handle de la famille de colonnes {@code subjects} contenant l'index des versions actives. */
    private ColumnFamilyHandle subjectsHandle;
    
    /** Handle de la famille de colonnes {@code meta} contenant les métadonnées globales. */
    private ColumnFamilyHandle metaHandle;
    
    /** Mapper Jackson configuré pour la sérialisation/désérialisation JSON. */
    private final ObjectMapper objectMapper;

    /**
     * Initialise le stockage persistant RocksDB dans le répertoire spécifié.
     * Crée le répertoire et les familles de colonnes si absents.
     *
     * @param path Chemin absolu vers le dossier de stockage de RocksDB.
     */
    public TadRocksDbStore(String path) {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        
        File dir = new File(path);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Failed to create RocksDB directory: " + path);
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
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open RocksDB store at " + path, e);
        }
    }

    /**
     * Convertit un long en tableau de 8 octets Big-Endian.
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
     */
    private byte[] toCompositeKey(String subject, long version) {
        byte[] subjBytes = subject.getBytes(StandardCharsets.UTF_8);
        byte[] composite = new byte[subjBytes.length + 1 + 8];
        System.arraycopy(subjBytes, 0, composite, 0, subjBytes.length);
        composite[subjBytes.length] = 0; // Separator byte
        byte[] verBytes = toBigEndian8(version);
        System.arraycopy(verBytes, 0, composite, subjBytes.length + 1, 8);
        return composite;
    }

    /**
     * Récupère la version de clé active la plus récente pour un sujet donné.
     *
     * @param subject Identifiant du sujet.
     * @return Un {@link Optional} contenant la {@link TrustEntry} active si trouvée, sinon {@link Optional#empty()}.
     */
    public Optional<TrustEntry> get(String subject) {
        try {
            byte[] key = subject.getBytes(StandardCharsets.UTF_8);
            byte[] versionBytes = db.get(subjectsHandle, key);
            if (versionBytes == null) {
                return Optional.empty();
            }
            long version = fromBigEndian8(versionBytes);
            return get(subject, version);
        } catch (RocksDBException e) {
            return Optional.empty();
        }
    }

    /**
     * Récupère une version spécifique d'une clé pour un sujet donné.
     *
     * @param subject Identifiant du sujet.
     * @param version Version de clé recherchée.
     * @return Un {@link Optional} contenant la {@link TrustEntry} si trouvée, sinon {@link Optional#empty()}.
     */
    public Optional<TrustEntry> get(String subject, long version) {
        try {
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

    /**
     * Persiste une {@link TrustEntry} de manière synchrone et durable.
     *
     * @param entry L'entrée à stocker.
     */
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

            // Écriture synchrone obligatoire pour garantir la persistance physique lors du consensus Raft
            db.write(new WriteOptions().setSync(true), batch);
        } catch (RocksDBException | IOException e) {
            throw new RuntimeException("Failed to write to RocksDB Store", e);
        }
    }

    /**
     * Récupère le numéro de version de clé le plus élevé stocké pour un sujet donné.
     *
     * @param subject Identifiant du sujet.
     * @return Le numéro de version ou 0 si le sujet n'existe pas.
     */
    public long getLatestVersion(String subject) {
        try {
            byte[] key = subject.getBytes(StandardCharsets.UTF_8);
            byte[] versionBytes = db.get(subjectsHandle, key);
            if (versionBytes == null) {
                return 0;
            }
            return fromBigEndian8(versionBytes);
        } catch (RocksDBException e) {
            return 0;
        }
    }

    /**
     * Parcourt l'ensemble des entrées et filtre celles qui ont été publiées après la date spécifiée.
     * Utilisé pour la synchronisation différentielle.
     *
     * @param since Instant de référence.
     * @return Liste des {@link TrustEntry} modifiées ou ajoutées.
     */
    public List<TrustEntry> getModifiedSince(Instant since) {
        List<TrustEntry> list = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(entriesHandle)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                byte[] entryBytes = iterator.value();
                TrustEntry entry = objectMapper.readValue(entryBytes, TrustEntry.class);
                if (entry.publishedAt().isAfter(since)) {
                    list.add(entry);
                }
                iterator.next();
            }
        } catch (IOException e) {
            // Silence
        }
        return list;
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
