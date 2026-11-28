package com.memorylane.backend.memory;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import com.memorylane.backend.TestApi;
import com.memorylane.backend.photo.PhotoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ML-13 acceptance against a real PostgreSQL: a successful delete removes the memory, its photo
 * rows and, after commit, their files; a deliberate failure midway keeps all of them; another
 * account gets the same 404 as for a missing memory and changes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
@ExtendWith(OutputCaptureExtension.class)
class MemoryDeleteIntegrationTest {
    private static final Path PHOTOS = temporaryDirectory();
    /** JPEG signature, the same sample PhotoFileTest uses; PhotoFile checks the leading bytes. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @Value("${local.server.port}") int port;
    @Autowired PhotoService photos;
    @Autowired JdbcClient jdbc;
    TestApi api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
        registry.add("memorylane.photos.directory", PHOTOS::toString);
    }

    @BeforeEach
    void client() {
        api = new TestApi(port);
    }

    @Test
    void deleteRemovesMemoryPhotoRowsAndTheirFiles() throws Exception {
        TestApi.Login owner = api.registerAndLogin();
        UUID memory = memory(owner.userId(), "Silinecek");
        List<String> files = List.of(photo(owner.userId(), memory), photo(owner.userId(), memory));

        var response = api.send("DELETE", path(memory), null, owner.token());

        assertEquals(204, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(0, memories(memory));
        assertEquals(0, photoRows(memory));
        // Removed right after the commit, before the response was sent.
        for (String file : files) assertFalse(Files.exists(PHOTOS.resolve(file)), file);
        assertEquals(0, cleanupJobs(files));
        // A deleted memory is gone for its owner too (contract: 404 after DELETE).
        assertEquals(404, api.send("DELETE", path(memory), null, owner.token()).statusCode());
    }

    @Test
    void failureAfterPhotosWereDeletedRollsBackEverything(CapturedOutput output) throws Exception {
        TestApi.Login owner = api.registerAndLogin();
        UUID memory = memory(owner.userId(), "Ara hata");
        List<String> files = List.of(photo(owner.userId(), memory), photo(owner.userId(), memory));
        // The deliberate failure: deleting this memory's row fails once its photo rows are already
        // deleted in the same transaction, i.e. between the two steps of the delete.
        jdbc.sql("""
                CREATE FUNCTION ml13_fail_midway() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM photos WHERE memory_id = OLD.id) THEN
                        RAISE EXCEPTION 'ML-13 deliberate failure after the photos were deleted';
                    END IF;
                    RETURN OLD;
                END $$""").update();
        jdbc.sql("""
                CREATE TRIGGER ml13_fail_midway BEFORE DELETE ON memories
                FOR EACH ROW WHEN (OLD.title = 'Ara hata') EXECUTE FUNCTION ml13_fail_midway()""").update();
        try {
            var response = api.send("DELETE", path(memory), null, owner.token());

            assertEquals(500, response.statusCode(), response.body());
            assertTrue(output.getOut().contains("ML-13 deliberate failure after the photos were deleted"),
                    "the failure happened after the photo rows were deleted");
        } finally {
            jdbc.sql("DROP TRIGGER ml13_fail_midway ON memories").update();
            jdbc.sql("DROP FUNCTION ml13_fail_midway()").update();
        }

        assertEquals(1, memories(memory));
        assertEquals(2, photoRows(memory));
        for (String file : files) assertTrue(Files.exists(PHOTOS.resolve(file)), "photo still in use: " + file);
        assertEquals(0, cleanupJobs(files), "the queued file deletes rolled back with the rows");
    }

    @Test
    void otherAccountGetsSame404AsForMissingMemoryAndNothingIsDeleted() throws Exception {
        TestApi.Login owner = api.registerAndLogin();
        UUID memory = memory(owner.userId(), "Başkasının");
        String file = photo(owner.userId(), memory);
        String other = api.registerAndLogin().token();

        var foreign = api.send("DELETE", path(memory), null, other);
        var missing = api.send("DELETE", path(UUID.randomUUID()), null, other);

        assertEquals(404, foreign.statusCode());
        assertTrue(foreign.body().contains("\"MEMORY_NOT_FOUND\""), foreign.body());
        assertEquals(missing.statusCode(), foreign.statusCode());
        assertEquals(missing.body(), foreign.body());
        assertEquals(1, memories(memory));
        assertEquals(1, photoRows(memory));
        assertTrue(Files.exists(PHOTOS.resolve(file)));

        var invalid = api.send("DELETE", "/api/v1/memories/not-a-uuid", null, other);
        assertEquals(400, invalid.statusCode());
        assertTrue(invalid.body().contains("\"INVALID_INPUT\""), invalid.body());
    }

    private static String path(UUID memory) {
        return "/api/v1/memories/" + memory;
    }

    private UUID memory(UUID owner, String title) {
        return jdbc.sql("""
                INSERT INTO memories (owner_id, client_request_id, client_request_sha256, title, memory_date)
                VALUES (:owner, :key, :hash, :title, CURRENT_DATE) RETURNING id""")
                .param("owner", owner).param("key", UUID.randomUUID()).param("hash", "a".repeat(64))
                .param("title", title).query(UUID.class).single();
    }

    /** Uploads through the service, so the file is really on disk, and returns its storage key. */
    private String photo(UUID owner, UUID memory) throws IOException {
        UUID id = photos.upload(owner, memory, UUID.randomUUID(), JPEG, "image/jpeg").photo().id();
        return jdbc.sql("SELECT storage_key FROM photos WHERE id = :id").param("id", id).query(String.class).single();
    }

    private long memories(UUID memory) {
        return jdbc.sql("SELECT count(*) FROM memories WHERE id = :id").param("id", memory).query(Long.class).single();
    }

    private long photoRows(UUID memory) {
        return jdbc.sql("SELECT count(*) FROM photos WHERE memory_id = :id").param("id", memory).query(Long.class).single();
    }

    private long cleanupJobs(List<String> files) {
        return jdbc.sql("SELECT count(*) FROM file_cleanup_jobs WHERE storage_key IN (:keys)")
                .param("keys", files).query(Long.class).single();
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("memorylane-delete-test");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
