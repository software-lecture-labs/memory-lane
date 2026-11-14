package com.memorylane.backend.ownership;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Ownership checks shared by every memory and photo operation (ML-29). The owner is always the
 * session user from {@code @CurrentUser}; ids from the path are only accepted when they belong
 * to that user. Photo ownership is checked through the memory the photo belongs to.
 */
@Service
public class OwnershipGuard {
    private final JdbcClient jdbc;

    public OwnershipGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** For reads: throws {@link ResourceNotFoundException#memory()} unless the user owns it. */
    public void requireMemory(UUID owner, UUID memoryId) {
        boolean owned =
                jdbc.sql("SELECT 1 FROM memories WHERE id = :memory AND owner_id = :owner")
                        .param("memory", memoryId)
                        .param("owner", owner)
                        .query(Integer.class)
                        .optional()
                        .isPresent();
        if (!owned) throw ResourceNotFoundException.memory();
    }

    /**
     * For every write (memory update and delete, photo upload and delete): same check, and locks
     * the memory row until the caller's transaction ends so concurrent writes such as the 5-photo
     * limit are serialised. Other writers of the same memory wait; plain reads do not.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockMemory(UUID owner, UUID memoryId) {
        boolean owned =
                jdbc.sql("SELECT 1 FROM memories WHERE id = :memory AND owner_id = :owner FOR UPDATE")
                        .param("memory", memoryId)
                        .param("owner", owner)
                        .query(Integer.class)
                        .optional()
                        .isPresent();
        if (!owned) throw ResourceNotFoundException.memory();
    }

    /**
     * Throws {@link ResourceNotFoundException#memory()} if the memory is missing or foreign, and
     * {@link ResourceNotFoundException#photo()} if the photo is not part of that memory. Takes no
     * lock: a photo delete calls {@link #lockMemory} first and then this in the same transaction.
     */
    public void requirePhoto(UUID owner, UUID memoryId, UUID photoId) {
        Boolean photoFound =
                jdbc.sql(
                                """
                                SELECT p.id IS NOT NULL FROM memories m
                                LEFT JOIN photos p ON p.memory_id = m.id AND p.id = :photo
                                WHERE m.id = :memory AND m.owner_id = :owner
                                """)
                        .param("photo", photoId)
                        .param("memory", memoryId)
                        .param("owner", owner)
                        .query(Boolean.class)
                        .optional()
                        .orElseThrow(ResourceNotFoundException::memory);
        if (!photoFound) throw ResourceNotFoundException.photo();
    }
}
