package com.memorylane.backend.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import com.memorylane.backend.TestApi;
import com.memorylane.backend.auth.AuthLimiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ML-31: every protected endpoint tried by account B on account A's data, through real HTTP and
 * PostgreSQL. B gets the same answer as for something that does not exist, and A's rows and files
 * stay exactly as they were. The endpoint-specific tests (MemoryIntegrationTest,
 * PhotoIntegrationTest, MemoryDeleteIntegrationTest, MemoryMapIntegrationTest) cover each
 * endpoint's own rules; this class is the one place that walks all of them with two accounts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
class TwoAccountAccessIntegrationTest {
    private static final Path PHOTOS = temporaryDirectory();
    /** JPEG signature, as in PhotoFileTest; the server checks the leading bytes. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};
    private static final String MISSING_MEMORY = "/api/v1/memories/" + UUID.randomUUID();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    /** Real limiter with only the per-IP counter off: this class registers many accounts from one IP. */
    @MockitoSpyBean AuthLimiter limiter;

    TestApi api;
    TestApi.Login a;
    TestApi.Login b;
    UUID memoryOfA;
    String titleOfA;
    UUID photoOfA;
    String fileOfA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
        registry.add("memorylane.photos.directory", PHOTOS::toString);
    }

    @BeforeEach
    void twoAccounts() throws Exception {
        doNothing().when(limiter).ip(anyString());
        api = new TestApi(port);
        a = api.registerAndLogin();
        b = api.registerAndLogin();
        // A word nobody else uses, so a search for it can only find A's memory.
        titleOfA = "Gizli" + UUID.randomUUID().toString().substring(0, 8);
        memoryOfA = api.createMemory(a.token(), titleOfA, 41.0082, 28.9784);
        var uploaded = api.uploadPhoto(memoryOfA, JPEG, "image/jpeg", a.token());
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        photoOfA = UUID.fromString(TestApi.field(uploaded.body(), "id"));
        fileOfA = jdbc.sql("SELECT storage_key FROM photos WHERE id = :id").param("id", photoOfA)
                .query(String.class).single();
    }

    @Test
    void otherAccountCannotReadChangeOrDeleteTheMemory() throws Exception {
        var ofA = api.get(memory(), a.token());
        assertEquals(200, ofA.statusCode(), ofA.body());
        String etagOfA = ofA.headers().firstValue("ETag").orElseThrow();
        Map<String, Object> before = memoryRow();
        String body = "{\"title\":\"B'nin başlığı\",\"memoryDate\":\"2026-01-02\"}";

        var missing = api.get(MISSING_MEMORY, b.token());
        assertEquals(404, missing.statusCode());
        // Even with A's real tag, and without any tag, B learns nothing more than "not found".
        for (HttpResponse<String> response : List.of(
                api.get(memory(), b.token()),
                api.send("PUT", memory(), body, b.token(), Map.of("If-Match", etagOfA)),
                api.send("PUT", memory(), body, b.token()),
                api.send("DELETE", memory(), null, b.token()))) {
            assertEquals(404, response.statusCode(), response.body());
            assertEquals(missing.body(), response.body());
        }

        assertEquals(before, memoryRow(), "A's memory is unchanged");
        assertEquals(1, photoRows(), "A's photo row is still there");
        assertTrue(Files.exists(PHOTOS.resolve(fileOfA)), "A's photo file is still there");
        assertEquals(200, api.get(memory(), a.token()).statusCode());
    }

    @Test
    void otherAccountCannotUploadListOrDownloadPhotos() throws Exception {
        long filesBefore = storedFiles();
        var missing = api.get(MISSING_MEMORY + "/photos", b.token());
        assertEquals(404, missing.statusCode());

        for (HttpResponse<String> response : List.of(
                api.uploadPhoto(memoryOfA, JPEG, "image/jpeg", b.token()),
                api.get(memory() + "/photos", b.token()),
                api.get(memory() + "/photos/" + photoOfA + "/content", b.token()))) {
            assertEquals(404, response.statusCode(), response.body());
            assertEquals(missing.body(), response.body(), "same answer as a memory that does not exist");
        }
        // B's own memory with A's photo id: the photo is checked through the memory in the path.
        UUID memoryOfB = api.createMemory(b.token(), "B'nin anısı", null, null);
        var borrowed = api.get("/api/v1/memories/" + memoryOfB + "/photos/" + photoOfA + "/content", b.token());
        assertEquals(404, borrowed.statusCode());
        assertTrue(borrowed.body().contains("\"PHOTO_NOT_FOUND\""), borrowed.body());

        assertEquals(1, photoRows(), "B's upload added no photo to A's memory");
        assertEquals(filesBefore, storedFiles(), "and left no file on disk");
        assertTrue(Files.exists(PHOTOS.resolve(fileOfA)));
        assertEquals(200, api.get(memory() + "/photos/" + photoOfA + "/content", a.token()).statusCode());
    }

    private long storedFiles() throws IOException {
        try (var files = Files.list(PHOTOS)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private String memory() {
        return "/api/v1/memories/" + memoryOfA;
    }

    private Map<String, Object> memoryRow() {
        return jdbc.sql("SELECT owner_id, title, memory_date, latitude, longitude, version, updated_at FROM memories WHERE id = :id")
                .param("id", memoryOfA).query().singleRow();
    }

    private long photoRows() {
        return jdbc.sql("SELECT count(*) FROM photos WHERE memory_id = :id").param("id", memoryOfA)
                .query(Long.class).single();
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("memorylane-two-accounts");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
