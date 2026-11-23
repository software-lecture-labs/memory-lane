package com.memorylane.backend.photo;

import com.memorylane.backend.ownership.OwnershipGuard;
import com.memorylane.backend.ownership.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Photo upload, list and download (ML-09). Every upload runs in one transaction that starts by
 * locking the memory row (ADR-0003), so the retry check, the 5-photo count and the position are
 * decided by one writer at a time for that memory.
 */
@Service
public class PhotoService {
    static final int MAX_PHOTOS_PER_MEMORY = 5;

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private final JdbcClient jdbc;
    private final OwnershipGuard guard;
    private final PhotoStorage storage;

    public PhotoService(JdbcClient jdbc, OwnershipGuard guard, PhotoStorage storage) {
        this.jdbc = jdbc;
        this.guard = guard;
        this.storage = storage;
    }

    /** Same shape as the photos inside MemoryDetail (API contract, PhotoResponse). */
    public record Photo(UUID id, UUID memoryId, int order, String contentType, long sizeBytes,
                        Instant createdAt, String contentUrl) {}

    public record Uploaded(Photo photo, boolean replayed) {}

    /** Caller must close the stream. */
    public record Content(String contentType, long sizeBytes, InputStream stream) {}

    // IOException is checked, so Spring would commit on it by default; roll back explicitly
    // (architecture.md, "Transaction": checked exception rules are chosen on purpose).
    @Transactional(rollbackFor = IOException.class)
    public Uploaded upload(UUID owner, UUID memoryId, UUID key, byte[] bytes, String declaredType) throws IOException {
        guard.lockMemory(owner, memoryId);
        PhotoFile file = PhotoFile.validate(bytes, declaredType);

        // A retry is answered before the limit check, so repeating an upload that already
        // succeeded never counts as a sixth photo (contract, "Kısmi başarı ve tekrar deneme" 5).
        var previous = jdbc.sql("SELECT * FROM photos WHERE memory_id = :memory AND client_photo_id = :key")
                .param("memory", memoryId).param("key", key)
                .query((row, number) -> new Previous(photo(row), row.getString("content_sha256")))
                .optional();
        if (previous.isPresent()) {
            Previous earlier = previous.get();
            if (!earlier.sha256().equals(file.sha256()) || !earlier.photo().contentType().equals(file.contentType())) {
                throw PhotoRejected.keyConflict();
            }
            return new Uploaded(earlier.photo(), true);
        }

        long count = jdbc.sql("SELECT count(*) FROM photos WHERE memory_id = :memory")
                .param("memory", memoryId).query(Long.class).single();
        if (count >= MAX_PHOTOS_PER_MEMORY) {
            throw PhotoRejected.limitReached();
        }

        String storageKey = storage.store(file.bytes());
        deleteIfRolledBack(storageKey);
        // Positions are never reused or renumbered (ML-04): the next photo goes after the highest one.
        Photo saved = jdbc.sql("""
                INSERT INTO photos (memory_id, client_photo_id, content_sha256, storage_key, media_type,
                    size_bytes, position)
                VALUES (:memory, :key, :sha256, :storageKey, :type, :size,
                    (SELECT coalesce(max(position) + 1, 0) FROM photos WHERE memory_id = :memory))
                RETURNING *
                """).param("memory", memoryId).param("key", key).param("sha256", file.sha256())
                .param("storageKey", storageKey).param("type", file.contentType())
                .param("size", (long) file.bytes().length)
                .query((row, number) -> photo(row)).single();
        return new Uploaded(saved, false);
    }

    @Transactional(readOnly = true)
    public List<Photo> list(UUID owner, UUID memoryId) {
        guard.requireMemory(owner, memoryId);
        return jdbc.sql("SELECT * FROM photos WHERE memory_id = :memory ORDER BY position")
                .param("memory", memoryId).query((row, number) -> photo(row)).list();
    }

    @Transactional(readOnly = true)
    public Content content(UUID owner, UUID memoryId, UUID photoId) throws IOException {
        guard.requirePhoto(owner, memoryId, photoId);
        var stored = jdbc.sql("SELECT media_type, size_bytes, storage_key FROM photos WHERE id = :photo")
                .param("photo", photoId)
                .query((row, number) -> new Stored(row.getString("media_type"), row.getLong("size_bytes"),
                        row.getString("storage_key")))
                .single();
        try {
            return new Content(stored.contentType(), stored.sizeBytes(), storage.open(stored.storageKey()));
        } catch (NoSuchFileException missing) {
            // The row exists but this backend has no file for it, e.g. the photo was uploaded through
            // another developer's backend sharing the same database. Answer like a missing photo.
            log.warn("Photo {} has no stored file on this backend", photoId);
            throw ResourceNotFoundException.photo();
        }
    }

    // The file is written before the row is inserted. If the transaction then fails (insert error,
    // lost connection), remove the file so a rolled-back upload leaves nothing on disk. A crash
    // between the two steps can still leave an orphan file; PhotoFileCleanup.sweepOrphans removes it.
    private void deleteIfRolledBack(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) return;
                try {
                    storage.delete(storageKey);
                } catch (IOException error) {
                    log.warn("Could not remove file of rolled-back photo upload {}", storageKey, error);
                }
            }
        });
    }

    private static Photo photo(ResultSet row) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        UUID memoryId = row.getObject("memory_id", UUID.class);
        return new Photo(id, memoryId, row.getInt("position"), row.getString("media_type"),
                row.getLong("size_bytes"), row.getObject("created_at", OffsetDateTime.class).toInstant(),
                "/api/v1/memories/" + memoryId + "/photos/" + id + "/content");
    }

    private record Previous(Photo photo, String sha256) {}

    private record Stored(String contentType, long sizeBytes, String storageKey) {}
}
