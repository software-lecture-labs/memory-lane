package com.memorylane.backend;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Database-level product rules from the migrations, checked against real PostgreSQL. */
@RequiresDocker
class SchemaRulesTest {
    private static final String HASH = "a".repeat(64);
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        DriverManagerDataSource dataSource = dataSource(SharedPostgres.createEmptyDatabase("schema_rules"));
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test void userMemoryAndPhotoKeysAreGeneratedUuids() {
        UUID memory = insertMemory(insertUser(), UUID.randomUUID());
        assertNotNull(insertPhoto(memory, UUID.randomUUID(), 1000, 0));
    }

    @Test void photoSizeLimitIsFiveMillionBytes() {
        UUID memory = insertMemory(insertUser(), UUID.randomUUID());
        insertPhoto(memory, UUID.randomUUID(), 5_000_000, 0);
        assertThrows(DataIntegrityViolationException.class, () -> insertPhoto(memory, UUID.randomUUID(), 5_000_001, 1));
    }

    @Test void nextPhotoBecomesCoverAndNewPhotoIsAppendedAfterCoverIsDeleted() {
        UUID memory = insertMemory(insertUser(), UUID.randomUUID());
        UUID cover = insertPhoto(memory, UUID.randomUUID(), 1000, 0);
        for (int position = 1; position <= 4; position++) {
            insertPhoto(memory, UUID.randomUUID(), 1000, position);
        }
        jdbc.update("DELETE FROM photos WHERE id = ?", cover);
        assertEquals(1, coverPosition(memory));
        // Positions are not renumbered, so the appended photo takes max(position) + 1.
        insertPhoto(memory, UUID.randomUUID(), 1000, 5);
        assertEquals(1, coverPosition(memory));
    }

    @Test void sameClientPhotoIdCannotCreateASecondPhoto() {
        UUID memory = insertMemory(insertUser(), UUID.randomUUID());
        UUID clientPhotoId = UUID.randomUUID();
        insertPhoto(memory, clientPhotoId, 1000, 0);
        assertThrows(DataIntegrityViolationException.class, () -> insertPhoto(memory, clientPhotoId, 1000, 1));
    }

    @Test void sameClientRequestIdCannotCreateASecondMemoryForTheSameOwner() {
        UUID owner = insertUser();
        UUID requestId = UUID.randomUUID();
        insertMemory(owner, requestId);
        assertThrows(DataIntegrityViolationException.class, () -> insertMemory(owner, requestId));
        // Keys are scoped per owner, so another account may use the same value.
        assertNotNull(insertMemory(insertUser(), requestId));
    }

    @Test void uuidMigrationRefusesToDropExistingRows() throws Exception {
        DriverManagerDataSource dataSource = dataSource(SharedPostgres.createEmptyDatabase("uuid_guard"));
        Flyway.configure().dataSource(dataSource).target("1").load().migrate();
        new JdbcTemplate(dataSource).update("INSERT INTO users (email, password_hash) VALUES ('a@example.com', 'x')");

        FlywayException failure = assertThrows(FlywayException.class, () -> Flyway.configure().dataSource(dataSource).load().migrate());
        assertTrue(failure.getMessage().contains("needs them empty"), failure.getMessage());
        assertEquals(1, new JdbcTemplate(dataSource).queryForObject("SELECT count(*) FROM users", Integer.class));
    }

    private static DriverManagerDataSource dataSource(String jdbcUrl) {
        return new DriverManagerDataSource(jdbcUrl, SharedPostgres.container().getUsername(), SharedPostgres.container().getPassword());
    }

    private static UUID insertUser() {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash) VALUES (?, 'x') RETURNING id",
                UUID.class, UUID.randomUUID() + "@example.com");
    }

    private static UUID insertMemory(UUID owner, UUID clientRequestId) {
        return jdbc.queryForObject("""
                INSERT INTO memories (owner_id, client_request_id, client_request_sha256, title, memory_date)
                VALUES (?, ?, ?, 'Test', CURRENT_DATE) RETURNING id""", UUID.class, owner, clientRequestId, HASH);
    }

    private static UUID insertPhoto(UUID memory, UUID clientPhotoId, long sizeBytes, int position) {
        return jdbc.queryForObject("""
                INSERT INTO photos (memory_id, client_photo_id, content_sha256, storage_key, media_type, size_bytes, position)
                VALUES (?, ?, ?, ?, 'image/jpeg', ?, ?) RETURNING id""",
                UUID.class, memory, clientPhotoId, HASH, UUID.randomUUID().toString(), sizeBytes, position);
    }

    private static int coverPosition(UUID memory) {
        return jdbc.queryForObject("SELECT position FROM photos WHERE memory_id = ? ORDER BY position LIMIT 1", Integer.class, memory);
    }
}
