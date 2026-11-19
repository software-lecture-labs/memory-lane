package com.memorylane.backend.photo;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import com.memorylane.backend.TestApi;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * ML-14 acceptance against a real PostgreSQL: files of committed deletes are removed, a rolled-back
 * delete keeps its file, a failed delete is logged and retried, and files no photo refers to are
 * swept. Deletes are queued the way the photo and memory delete services will queue them: from
 * inside their own transaction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
@ExtendWith(OutputCaptureExtension.class)
class PhotoFileCleanupIntegrationTest {
    private static final Path PHOTOS = temporaryDirectory();

    @Value("${local.server.port}") int port;
    @Autowired PhotoFileCleanup cleanup;
    @Autowired PhotoService photos;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean PhotoStorage storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
        registry.add("memorylane.photos.directory", PHOTOS::toString);
    }

    @Test
    void committedDeleteRemovesTheFileAndItsJob() throws Exception {
        String key = storage.store(PhotoFileTest.JPEG);

        transactions.executeWithoutResult(status -> cleanup.deleteAfterCommit(List.of(key)));

        assertFalse(Files.exists(PHOTOS.resolve(key)));
        assertEquals(0, jobs(key));
    }

    @Test
    void rolledBackDeleteKeepsTheFileAndLeavesNoJob() throws Exception {
        String key = storage.store(PhotoFileTest.JPEG);

        // The deliberate failure after the delete was queued, as in the ML-13 rollback scenario.
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            cleanup.deleteAfterCommit(List.of(key));
            throw new IllegalStateException("deliberate failure after the delete was queued");
        }));

        assertTrue(Files.exists(PHOTOS.resolve(key)), "the photo is still in use, its file must stay");
        assertEquals(0, jobs(key));
    }

    @Test
    void failedDeleteIsLoggedKeptAndRetriedUntilItWorks(CapturedOutput output) throws Exception {
        String key = storage.store(PhotoFileTest.PNG);
        doThrow(new IOException("disk busy")).when(storage).delete(key);

        transactions.executeWithoutResult(status -> cleanup.deleteAfterCommit(List.of(key)));

        assertTrue(Files.exists(PHOTOS.resolve(key)));
        var job = jdbc.sql("SELECT attempts, last_error, next_attempt_at > now() AS later FROM file_cleanup_jobs WHERE storage_key = :key")
                .param("key", key).query().singleRow();
        assertEquals(1, ((Number) job.get("attempts")).intValue());
        assertTrue(job.get("last_error").toString().contains("disk busy"), job.toString());
        assertEquals(Boolean.TRUE, job.get("later"), "the next attempt waits instead of spinning");
        assertTrue(output.getOut().contains("Could not delete photo file " + key), "the failure is logged");

        // Not due yet: the retry run leaves it alone.
        doCallRealMethod().when(storage).delete(key);
        cleanup.retryDue();
        assertTrue(Files.exists(PHOTOS.resolve(key)));

        jdbc.sql("UPDATE file_cleanup_jobs SET next_attempt_at = now() - interval '1 second' WHERE storage_key = :key")
                .param("key", key).update();
        cleanup.retryDue();

        assertFalse(Files.exists(PHOTOS.resolve(key)));
        assertEquals(0, jobs(key));
    }

    @Test
    void queueingOutsideATransactionIsRefused() throws Exception {
        String key = storage.store(PhotoFileTest.JPEG);

        // Without a transaction there is no commit to wait for; deleting at once could remove a
        // file whose row is still there.
        assertThrows(IllegalTransactionStateException.class, () -> cleanup.deleteAfterCommit(List.of(key)));

        assertTrue(Files.exists(PHOTOS.resolve(key)));
        assertEquals(0, jobs(key));
    }

    @Test
    void aFileAPhotoStillRefersToIsNeverDeleted(CapturedOutput output) throws Exception {
        String inUse = photoInUse();

        // A wrong caller: queues the file while its photo row stays.
        transactions.executeWithoutResult(status -> cleanup.deleteAfterCommit(List.of(inUse)));

        assertTrue(Files.exists(PHOTOS.resolve(inUse)));
        assertEquals(0, jobs(inUse), "the job is dropped, not retried forever");
        assertTrue(output.getOut().contains("Not deleting photo file " + inUse));
    }

    @Test
    void aKeyThatIsNotAStorageKeyIsDroppedAndNeverReachesTheFileSystem(CapturedOutput output) throws Exception {
        Path outside = Files.writeString(PHOTOS.getParent().resolve("cleanup-outside-" + UUID.randomUUID() + ".txt"), "keep");
        String hostile = "../" + outside.getFileName();

        transactions.executeWithoutResult(status -> cleanup.deleteAfterCommit(List.of(hostile)));

        assertTrue(Files.exists(outside));
        assertEquals(0, jobs(hostile));
        assertFalse(output.getOut().contains(hostile), "a value that is not ours is not written to the log");
        Files.delete(outside);
    }

    @Test
    void sweepRemovesOldOrphansAndUnfinishedUploadsButKeepsPhotosInUse() throws Exception {
        String inUse = photoInUse();
        String orphan = storage.store(PhotoFileTest.PNG);
        String recentOrphan = storage.store(PhotoFileTest.PNG);
        Path abandoned = Files.write(PHOTOS.resolve("upload-abandoned.tmp"), PhotoFileTest.JPEG);
        Path inProgress = Files.write(PHOTOS.resolve("upload-in-progress.tmp"), PhotoFileTest.JPEG);
        for (Path path : List.of(PHOTOS.resolve(inUse), PHOTOS.resolve(orphan), abandoned)) {
            Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
        }

        cleanup.sweepOrphans();

        assertTrue(Files.exists(PHOTOS.resolve(inUse)), "a photo row still refers to it");
        assertFalse(Files.exists(PHOTOS.resolve(orphan)));
        assertTrue(Files.exists(PHOTOS.resolve(recentOrphan)), "may belong to an upload that has not committed yet");
        assertFalse(Files.exists(abandoned));
        assertTrue(Files.exists(inProgress));
    }

    /** Uploads a photo through the service and returns the storage key its row refers to. */
    private String photoInUse() throws Exception {
        TestApi.Login owner = new TestApi(port).registerAndLogin();
        UUID memory = memory(owner.token());
        photos.upload(owner.userId(), memory, UUID.randomUUID(), PhotoFileTest.JPEG, "image/jpeg");
        return jdbc.sql("SELECT storage_key FROM photos WHERE memory_id = :memory")
                .param("memory", memory).query(String.class).single();
    }

    private long jobs(String key) {
        return jdbc.sql("SELECT count(*) FROM file_cleanup_jobs WHERE storage_key = :key")
                .param("key", key).query(Long.class).single();
    }

    private UUID memory(String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/memories"))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"Temizlik\",\"memoryDate\":\"2026-01-01\"}"))
                .build();
        var created = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        Matcher id = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(created.body());
        assertTrue(id.find(), created.body());
        return UUID.fromString(id.group(1));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("memorylane-cleanup-test");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
