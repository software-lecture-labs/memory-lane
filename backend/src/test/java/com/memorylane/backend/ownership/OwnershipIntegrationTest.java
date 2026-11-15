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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    @Autowired TransactionTemplate transaction;
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

        @DeleteMapping("/photos/{photoId}")
        ResponseEntity<Void> deletePhoto(@CurrentUser UUID user, @PathVariable UUID memoryId, @PathVariable UUID photoId) {
            transaction.executeWithoutResult(status -> {
                guard.lockMemory(user, memoryId);
                guard.requirePhoto(user, memoryId, photoId);
                jdbc.update("DELETE FROM photos WHERE id = ?", photoId);
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
        UUID memoryOfA = insertMemory(insertUser());
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
        UUID memoryOfA = insertMemory(insertUser());
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
    void otherAccountCannotDeletePhoto() throws Exception {
        TestApi.Login a = api.registerAndLogin();
        UUID memoryOfA = insertMemory(a.userId());
        UUID photoOfA = insertPhoto(memoryOfA);
        String photoPath = memoryPath(memoryOfA) + "/photos/" + photoOfA;

        assertEquals(404, api.send("DELETE", photoPath, null, api.registerAndLogin().token()).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM photos WHERE id = ?", Integer.class, photoOfA));

        assertEquals(204, api.send("DELETE", photoPath, null, a.token()).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM photos WHERE id = ?", Integer.class, photoOfA));
    }

    @Test
    void secondWriterWaitsUntilFirstWriterReleasesTheMemoryLock() throws Exception {
        UUID owner = insertUser();
        UUID memory = insertMemory(owner);
        var firstHoldsLock = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var firstReleased = new AtomicBoolean();
        ExecutorService writers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = writers.submit(() -> transaction.executeWithoutResult(status -> {
                guard.lockMemory(owner, memory);
                firstHoldsLock.countDown();
                awaitQuietly(releaseFirst);
                firstReleased.set(true);
            }));
            assertTrue(firstHoldsLock.await(5, TimeUnit.SECONDS));

            Future<Boolean> second = writers.submit(() -> transaction.execute(status -> {
                guard.lockMemory(owner, memory);
                return firstReleased.get();
            }));
            // PostgreSQL itself reports the second transaction as blocked on the row lock.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (writersWaitingForLock() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(1, writersWaitingForLock());
            assertFalse(second.isDone());

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertTrue(second.get(5, TimeUnit.SECONDS), "second writer must get the lock only after the first commits");
        } finally {
            releaseFirst.countDown();
            writers.shutdownNow();
        }
    }

    @Test
    void lockingRequiresTheCallersTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> guard.lockMemory(UUID.randomUUID(), UUID.randomUUID()));
    }

    private int writersWaitingForLock() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE '%FOR UPDATE%'""",
                Integer.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String memoryPath(UUID memory) {
        return "/api/v1/ownership-probe/memories/" + memory;
    }

    /**
     * Account A only owns data and never calls the API, so it is inserted directly. The auth
     * limiter allows 20 register/login requests per minute from one IP for the whole test class.
     */
    private UUID insertUser() {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash) VALUES (?, 'x') RETURNING id",
                UUID.class, UUID.randomUUID() + "@example.test");
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
