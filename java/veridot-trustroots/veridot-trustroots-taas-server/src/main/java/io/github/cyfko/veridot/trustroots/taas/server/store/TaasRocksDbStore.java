package io.github.cyfko.veridot.trustroots.taas.server.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.cyfko.veridot.trustroots.api.AttestationRecord;
import io.github.cyfko.veridot.trustroots.api.SecurityAlert;
import io.github.cyfko.veridot.trustroots.api.TrustEntry;
import org.rocksdb.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Stockage d'état persistant persistant RocksDB pour le serveur TAAS (V5).
 * <p>
 * Ce composant gère la persistance de l'autorité de confiance répliquée. Il utilise six familles de colonnes (Column Families)
 * pour séparer logiquement les métadonnées, l'index de version, les entrées de confiance JSON indexées par clés composites,
 * le journal d'attestation, les alertes de sécurité et le cache JWKS.
 * Contrairement au cache L2 client, les écritures sur le serveur TAAS sont synchrones ({@code setSync(true)}) pour garantir la durabilité
 * requise par le consensus distribué Raft.
 */
public class TaasRocksDbStore implements AutoCloseable {
    static {
        RocksDB.loadLibrary();
    }

    /** Noms des familles de colonnes (Column Families). */
    private static final List<String> CF_NAMES = List.of(
        "default", "entries", "subjects", "meta",
        "attestation_log", "security_alerts", "jwks_cache"
    );

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

    /** Handle de la famille de colonnes {@code attestation_log}. */
    private ColumnFamilyHandle attestationLogHandle;

    /** Handle de la famille de colonnes {@code security_alerts}. */
    private ColumnFamilyHandle securityAlertsHandle;

    /** Handle de la famille de colonnes {@code jwks_cache}. */
    private ColumnFamilyHandle jwksCacheHandle;
    
    /** Mapper Jackson configuré pour la sérialisation/désérialisation JSON. */
    private final ObjectMapper objectMapper;

    /**
     * Initialise le stockage persistant RocksDB dans le répertoire spécifié.
     * Crée le répertoire et les familles de colonnes si absents.
     *
     * @param path Chemin absolu vers le dossier de stockage de RocksDB.
     */
    public TaasRocksDbStore(String path) {
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

        // Detect which column families already exist
        java.util.Set<String> existingCfs = new java.util.HashSet<>();
        for (byte[] cfName : cfList) {
            existingCfs.add(new String(cfName, StandardCharsets.UTF_8));
        }

        List<ColumnFamilyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, new ColumnFamilyOptions()));
        for (String cfName : CF_NAMES) {
            if (!cfName.equals("default") && existingCfs.contains(cfName)) {
                descriptors.add(new ColumnFamilyDescriptor(cfName.getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));
            }
        }

        this.dbOptions = new DBOptions()
                .setCreateIfMissing(true)
                .setCreateMissingColumnFamilies(true);

        try {
            this.db = RocksDB.open(dbOptions, path, descriptors, cfHandles);
            this.defaultHandle = cfHandles.get(0);
            
            int idx = 1;
            // Map existing CFs by descriptor order
            java.util.Map<String, ColumnFamilyHandle> handleMap = new java.util.HashMap<>();
            handleMap.put("default", defaultHandle);
            for (int i = 1; i < descriptors.size(); i++) {
                String name = new String(descriptors.get(i).getName(), StandardCharsets.UTF_8);
                handleMap.put(name, cfHandles.get(i));
            }

            // Create missing CFs
            for (String cfName : CF_NAMES) {
                if (!cfName.equals("default") && !handleMap.containsKey(cfName)) {
                    ColumnFamilyHandle h = db.createColumnFamily(
                        new ColumnFamilyDescriptor(cfName.getBytes(StandardCharsets.UTF_8), new ColumnFamilyOptions()));
                    cfHandles.add(h);
                    handleMap.put(cfName, h);
                }
            }

            this.entriesHandle = handleMap.get("entries");
            this.subjectsHandle = handleMap.get("subjects");
            this.metaHandle = handleMap.get("meta");
            this.attestationLogHandle = handleMap.get("attestation_log");
            this.securityAlertsHandle = handleMap.get("security_alerts");
            this.jwksCacheHandle = handleMap.get("jwks_cache");
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to open RocksDB store at " + path, e);
        }
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
     *
     * @param subject The subject.
     * @param version The version.
     * @return The composite key bytes.
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
     * Inserts a {@link TrustEntry} directly into the store, bypassing Raft consensus.
     * Used only for bootstrap operations (§5.7).
     *
     * @param entry The entry to store directly.
     */
    public void putDirect(TrustEntry entry) {
        put(entry);
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

    /**
     * Logs an attestation record to the attestation_log column family.
     *
     * @param record The attestation record to log.
     */
    public void logAttestation(AttestationRecord record) {
        try {
            // Key: subject + 0x00 + timestamp (big-endian)
            byte[] subjBytes = record.subject().getBytes(StandardCharsets.UTF_8);
            byte[] key = new byte[subjBytes.length + 1 + 8];
            System.arraycopy(subjBytes, 0, key, 0, subjBytes.length);
            key[subjBytes.length] = 0;
            byte[] tsBytes = toBigEndian8(record.verifiedAt().toEpochMilli());
            System.arraycopy(tsBytes, 0, key, subjBytes.length + 1, 8);

            byte[] value = objectMapper.writeValueAsBytes(record);
            db.put(attestationLogHandle, new WriteOptions().setSync(true), key, value);
        } catch (RocksDBException | IOException e) {
            throw new RuntimeException("Failed to log attestation record", e);
        }
    }

    /**
     * Logs a security alert to the security_alerts column family.
     *
     * @param alert The security alert to log.
     */
    public void logSecurityAlert(SecurityAlert alert) {
        try {
            // Key: timestamp (big-endian) + subject
            byte[] tsBytes = toBigEndian8(alert.detectedAt().toEpochMilli());
            byte[] subjBytes = alert.subject().getBytes(StandardCharsets.UTF_8);
            byte[] key = new byte[8 + 1 + subjBytes.length];
            System.arraycopy(tsBytes, 0, key, 0, 8);
            key[8] = 0;
            System.arraycopy(subjBytes, 0, key, 9, subjBytes.length);

            byte[] value = objectMapper.writeValueAsBytes(alert);
            db.put(securityAlertsHandle, new WriteOptions().setSync(true), key, value);
        } catch (RocksDBException | IOException e) {
            throw new RuntimeException("Failed to log security alert", e);
        }
    }

    /**
     * Retrieves attestation records for a given subject since a given instant.
     *
     * @param subject Subject identifier.
     * @param since   Instant from which to retrieve records.
     * @return List of matching attestation records.
     */
    public List<AttestationRecord> getAttestations(String subject, Instant since) {
        List<AttestationRecord> results = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(attestationLogHandle)) {
            if (subject != null) {
                byte[] subjBytes = subject.getBytes(StandardCharsets.UTF_8);
                byte[] prefix = new byte[subjBytes.length + 1];
                System.arraycopy(subjBytes, 0, prefix, 0, subjBytes.length);
                prefix[subjBytes.length] = 0;
                iterator.seek(prefix);
                while (iterator.isValid()) {
                    byte[] key = iterator.key();
                    // Check prefix match
                    if (key.length < prefix.length) break;
                    boolean match = true;
                    for (int i = 0; i < prefix.length; i++) {
                        if (key[i] != prefix[i]) { match = false; break; }
                    }
                    if (!match) break;

                    AttestationRecord record = objectMapper.readValue(iterator.value(), AttestationRecord.class);
                    if (since == null || record.verifiedAt().isAfter(since)) {
                        results.add(record);
                    }
                    iterator.next();
                }
            } else {
                iterator.seekToFirst();
                while (iterator.isValid()) {
                    AttestationRecord record = objectMapper.readValue(iterator.value(), AttestationRecord.class);
                    if (since == null || record.verifiedAt().isAfter(since)) {
                        results.add(record);
                    }
                    iterator.next();
                }
            }
        } catch (IOException e) {
            // Silence
        }
        return results;
    }

    /**
     * Retrieves security alerts since a given instant.
     *
     * @param since Instant from which to retrieve alerts.
     * @return List of matching security alerts.
     */
    public List<SecurityAlert> getAlerts(Instant since) {
        List<SecurityAlert> results = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(securityAlertsHandle)) {
            if (since != null) {
                byte[] sinceKey = toBigEndian8(since.toEpochMilli());
                iterator.seek(sinceKey);
            } else {
                iterator.seekToFirst();
            }
            while (iterator.isValid()) {
                SecurityAlert alert = objectMapper.readValue(iterator.value(), SecurityAlert.class);
                results.add(alert);
                iterator.next();
            }
        } catch (IOException e) {
            // Silence
        }
        return results;
    }

    /**
     * Prunes expired trust entries from the store.
     *
     * @param now    Current instant.
     * @param margin Grace period after expiration before pruning.
     */
    public void pruneExpired(Instant now, Duration margin) {
        Instant cutoff = now.minus(margin);
        try (RocksIterator iterator = db.newIterator(entriesHandle)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                byte[] entryBytes = iterator.value();
                try {
                    TrustEntry entry = objectMapper.readValue(entryBytes, TrustEntry.class);
                    if (entry.notAfter().isBefore(cutoff)) {
                        db.delete(entriesHandle, iterator.key());
                    }
                } catch (IOException e) {
                    // Skip malformed entries
                }
                iterator.next();
            }
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to prune expired entries", e);
        }
    }

    /**
     * Retrieves cached JWKS data for a given provider.
     *
     * @param providerName The attestation provider name.
     * @return The cached JWKS bytes if present.
     */
    public Optional<byte[]> getJwksCache(String providerName) {
        try {
            byte[] key = providerName.getBytes(StandardCharsets.UTF_8);
            byte[] value = db.get(jwksCacheHandle, key);
            return Optional.ofNullable(value);
        } catch (RocksDBException e) {
            return Optional.empty();
        }
    }

    /**
     * Stores JWKS data in the cache for a given provider.
     *
     * @param providerName The attestation provider name.
     * @param jwks         The JWKS bytes to cache.
     */
    public void putJwksCache(String providerName, byte[] jwks) {
        try {
            byte[] key = providerName.getBytes(StandardCharsets.UTF_8);
            db.put(jwksCacheHandle, new WriteOptions().setSync(true), key, jwks);
        } catch (RocksDBException e) {
            throw new RuntimeException("Failed to cache JWKS", e);
        }
    }

    /**
     * Returns all active {@link TrustEntry} objects in the store.
     *
     * <p>Iterates the {@code entries} column family and returns every valid entry.
     * Used by the TAAS Digest Service (§18.2) to compute the Sparse Merkle Tree root
     * over all TAAS-committed entries.
     *
     * @return List of all active TrustEntry objects.
     */
    public List<TrustEntry> getAllEntries() {
        List<TrustEntry> entries = new ArrayList<>();
        try (RocksIterator iterator = db.newIterator(entriesHandle)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                try {
                    TrustEntry entry = objectMapper.readValue(iterator.value(), TrustEntry.class);
                    entries.add(entry);
                } catch (IOException e) {
                    // Skip malformed entries
                }
                iterator.next();
            }
        }
        return entries;
    }

    /**
     * Returns the total number of registered subjects.
     *
     * @return The number of subjects in the store.
     */
    public long count() {
        long count = 0;
        try (RocksIterator iterator = db.newIterator(subjectsHandle)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                count++;
                iterator.next();
            }
        }
        return count;
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
