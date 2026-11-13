package com.memorylane.backend.ownership;

import static org.junit.jupiter.api.Assertions.*;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import com.memorylane.backend.TestApi;
import com.memorylane.backend.auth.CurrentUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * ML-29 acceptance: account B cannot read, change or delete account A's memory or photo even
 * with the exact ids, and gets the same 404 as for an id that does not exist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OwnershipIntegrationTest.Probe.class)
@RequiresDocker
class OwnershipIntegrationTest {
    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate jdbc;
    @Autowired OwnershipGuard guard;
    TestApi api;

    /** Test-only endpoints using the guard the way the memory and photo services will. */
    @RestController
    @RequestMapping("/api/v1/ownership-probe/memories/{memoryId}")
    static class Probe {
        private final OwnershipGuard guard;
        private final JdbcTemplate jdbc;
        private final TransactionTemplate transaction;

        Probe(OwnershipGuard guard, JdbcTemplate jdbc, TransactionTemplate transaction) {
            this.guard = guard;
            this.jdbc = jdbc;
            this.transaction = transaction;
        }

        @GetMapping
        ResponseEntity<Void> read(@CurrentUser UUID user, @PathVariable UUID memoryId) {
            guard.requireMemory(user, memoryId);
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/photos/{photoId}")
        ResponseEntity<Void> photo(@CurrentUser UUID user, @PathVariable UUID memoryId, @PathVariable UUID photoId) {
            guard.requirePhoto(user, memoryId, photoId);
            return ResponseEntity.noContent().build();
        }

        @PutMapping
        ResponseEntity<Void> rename(@CurrentUser UUID user, @PathVariable UUID memoryId) {
            transaction.executeWithoutResult(status -> {
                guard.lockMemory(user, memoryId);
                jdbc.update("UPDATE memories SET title = 'Değişti' WHERE id = ?", memoryId);
            });
            return ResponseEntity.noContent().build();
        }

        @DeleteMapping
        ResponseEntity<Void> delete(@CurrentUser UUID user, @PathVariable UUID memoryId) {
            transaction.executeWithoutResult(status -> {
                guard.lockMemory(user, memoryId);
                jdbc.update("DELETE FROM photos WHERE memory_id = ?", memoryId);
                jdbc.update("DELETE FROM memories WHERE id = ?", memoryId);
            });
            return ResponseEntity.noContent().build();
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        r.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        r.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
    }

    @BeforeEach
    void client() {
        api = new TestApi(port);
    }

    @Test
    void ownerCanReadOwnMemoryAndPhoto() throws Exception {
        TestApi.Login a = api.registerAndLogin();
        UUID memory = insertMemory(a.userId());
        UUID photo = insertPhoto(memory);
        assertEquals(204, api.get(memoryPath(memory), a.token()).statusCode());
        assertEquals(204, api.get(memoryPath(memory) + "/photos/" + photo, a.token()).statusCode());
    }

    @Test
    void otherAccountGetsSame404AsForMissingMemory() throws Exception {
        UUID memoryOfA = insertMemory(api.registerAndLogin().userId());
        String tokenOfB = api.registerAndLogin().token();

        var foreign = api.get(memoryPath(memoryOfA), tokenOfB);
        var missing = api.get(memoryPath(UUID.randomUUID()), tokenOfB);
        assertEquals(404, foreign.statusCode());
        assertTrue(foreign.body().contains("\"MEMORY_NOT_FOUND\""), foreign.body());
        assertEquals(missing.statusCode(), foreign.statusCode());
        assertEquals(missing.body(), foreign.body());
    }

    @Test
    void otherAccountCannotReadPhotoEvenWithItsFullAddress() throws Exception {
        UUID memoryOfA = insertMemory(api.registerAndLogin().userId());
        UUID photoOfA = insertPhoto(memoryOfA);
        var response = api.get(memoryPath(memoryOfA) + "/photos/" + photoOfA, api.registerAndLogin().token());
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("\"MEMORY_NOT_FOUND\""), response.body());
    }

    @Test
    void photoMustBelongToTheMemoryInThePath() throws Exception {
        TestApi.Login a = api.registerAndLogin();
        UUID first = insertMemory(a.userId());
        UUID photoOfSecond = insertPhoto(insertMemory(a.userId()));
        var response = api.get(memoryPath(first) + "/photos/" + photoOfSecond, a.token());
        assertEquals(404, response.statusCode());
        assertTrue(response.body().contains("\"PHOTO_NOT_FOUND\""), response.body());
    }

    @Test
    void otherAccountCannotChangeOrDeleteMemory() throws Exception {
        TestApi.Login a = api.registerAndLogin();
        UUID memoryOfA = insertMemory(a.userId());
        insertPhoto(memoryOfA);
        String tokenOfB = api.registerAndLogin().token();

        assertEquals(404, api.send("PUT", memoryPath(memoryOfA), null, tokenOfB).statusCode());
        assertEquals(404, api.send("DELETE", memoryPath(memoryOfA), null, tokenOfB).statusCode());
        assertEquals("Test", jdbc.queryForObject("SELECT title FROM memories WHERE id = ?", String.class, memoryOfA));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM photos WHERE memory_id = ?", Integer.class, memoryOfA));

        assertEquals(204, api.send("DELETE", memoryPath(memoryOfA), null, a.token()).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM memories WHERE id = ?", Integer.class, memoryOfA));
    }

    @Test
    void lockingRequiresTheCallersTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> guard.lockMemory(UUID.randomUUID(), UUID.randomUUID()));
    }

    private static String memoryPath(UUID memory) {
        return "/api/v1/ownership-probe/memories/" + memory;
    }

    private UUID insertMemory(UUID owner) {
        return jdbc.queryForObject("""
                INSERT INTO memories (owner_id, client_request_id, client_request_sha256, title, memory_date)
                VALUES (?, ?, ?, 'Test', CURRENT_DATE) RETURNING id""",
                UUID.class, owner, UUID.randomUUID(), "a".repeat(64));
    }

    private UUID insertPhoto(UUID memory) {
        return jdbc.queryForObject("""
                INSERT INTO photos (memory_id, client_photo_id, content_sha256, storage_key, media_type, size_bytes, position)
                VALUES (?, ?, ?, ?, 'image/jpeg', 1000, 0) RETURNING id""",
                UUID.class, memory, UUID.randomUUID(), "b".repeat(64), UUID.randomUUID().toString());
    }
}
