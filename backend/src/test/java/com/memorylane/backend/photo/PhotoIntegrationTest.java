package com.memorylane.backend.photo;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ML-09 acceptance against a real PostgreSQL: invalid, too large and sixth photos are rejected,
 * the client's file name is never used, and every photo row belongs to a memory of the session
 * user (ADR-0003: a second account gets 404 even with the full address).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
class PhotoIntegrationTest {
    private static final Path PHOTOS = temporaryDirectory();

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired PhotoService photos;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcClient jdbc;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
        registry.add("memorylane.photos.directory", PHOTOS::toString);
    }

    @Test
    void uploadedPhotosAreListedInOrderDownloadedAndCountedOnTheMemory() throws Exception {
        Session owner = session();
        String memory = memory(owner);

        var first = upload(owner.token(), memory, UUID.randomUUID().toString(), PhotoFileTest.JPEG, "image/jpeg");
        assertEquals(201, first.statusCode(), first.body());
        JsonNode photo = json.readTree(first.body());
        assertEquals(0, photo.path("order").asInt());
        assertEquals("image/jpeg", photo.path("contentType").asString());
        assertEquals(PhotoFileTest.JPEG.length, photo.path("sizeBytes").asLong());
        assertEquals(memory, photo.path("memoryId").asString());
        assertEquals(photo.path("contentUrl").asString(), first.headers().firstValue("Location").orElseThrow());

        var second = upload(owner.token(), memory, UUID.randomUUID().toString(), PhotoFileTest.PNG, "image/png");
        assertEquals(201, second.statusCode(), second.body());
        assertEquals(1, json.readTree(second.body()).path("order").asInt());

        JsonNode list = json.readTree(send("GET", "/memories/" + memory + "/photos", owner.token()).body());
        assertEquals(2, list.size());
        assertEquals(photo.path("id"), list.get(0).path("id"));
        assertEquals("image/png", list.get(1).path("contentType").asString());

        var content = http.send(request(photo.path("contentUrl").asString(), owner.token()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, content.statusCode());
        assertArrayEquals(PhotoFileTest.JPEG, content.body());
        assertEquals("image/jpeg", content.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("nosniff", content.headers().firstValue("X-Content-Type-Options").orElseThrow());

        JsonNode summary = json.readTree(send("GET", "/memories", owner.token()).body()).path("content").get(0);
        assertEquals(2, summary.path("photoCount").asInt());
        assertEquals(photo.path("id"), summary.path("coverPhotoId"));
    }

    @Test
    void retryWithTheSameKeyReturnsTheFirstPhotoAndDifferentBytesConflict() throws Exception {
        Session owner = session();
        String memory = memory(owner);
        String key = UUID.randomUUID().toString();

        var created = upload(owner.token(), memory, key, PhotoFileTest.JPEG, "image/jpeg");
        assertEquals(201, created.statusCode(), created.body());
        long filesAfterFirst = storedFiles();

        var replay = upload(owner.token(), memory, key, PhotoFileTest.JPEG, "image/jpeg");
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals("true", replay.headers().firstValue("Idempotency-Replayed").orElseThrow());
        assertEquals(json.readTree(created.body()).path("id"), json.readTree(replay.body()).path("id"));

        var conflict = upload(owner.token(), memory, key, PhotoFileTest.jpegOfSize(64), "image/jpeg");
        assertEquals(409, conflict.statusCode());
        assertEquals("IDEMPOTENCY_CONFLICT", json.readTree(conflict.body()).path("code").asString());

        assertEquals(1, photoRows(memory));
        assertEquals(filesAfterFirst, storedFiles());
    }

    @Test
    void sixthPhotoIsRejectedButRetryingAnEarlierUploadStillSucceeds() throws Exception {
        Session owner = session();
        String memory = memory(owner);
        String firstKey = UUID.randomUUID().toString();
        assertEquals(201, upload(owner.token(), memory, firstKey, PhotoFileTest.JPEG, "image/jpeg").statusCode());
        for (int i = 1; i < PhotoService.MAX_PHOTOS_PER_MEMORY; i++) {
            assertEquals(201, upload(owner.token(), memory, UUID.randomUUID().toString(),
                    PhotoFileTest.jpegOfSize(20 + i), "image/jpeg").statusCode());
        }

        var sixth = upload(owner.token(), memory, UUID.randomUUID().toString(), PhotoFileTest.PNG, "image/png");
        assertEquals(409, sixth.statusCode());
        assertEquals("PHOTO_LIMIT_REACHED", json.readTree(sixth.body()).path("code").asString());
        assertEquals(200, upload(owner.token(), memory, firstKey, PhotoFileTest.JPEG, "image/jpeg").statusCode());
        assertEquals(PhotoService.MAX_PHOTOS_PER_MEMORY, photoRows(memory));
    }

    @Test
    void invalidRequestsAreRejectedAndStoreNothing() throws Exception {
        Session owner = session();
        String memory = memory(owner);
        long filesBefore = storedFiles();
        byte[] text = "this is not a photo".getBytes(StandardCharsets.UTF_8);

        assertError(415, "UNSUPPORTED_PHOTO_TYPE", upload(owner.token(), memory, UUID.randomUUID().toString(), text, "image/jpeg"));
        assertError(415, "UNSUPPORTED_PHOTO_TYPE",
                upload(owner.token(), memory, UUID.randomUUID().toString(), PhotoFileTest.PNG, "image/jpeg"));
        assertError(413, "FILE_TOO_LARGE",
                upload(owner.token(), memory, UUID.randomUUID().toString(), PhotoFileTest.jpegOfSize(5_000_001), "image/jpeg"));
        assertRefusedAboveServletLimit(owner.token(), memory);
        assertError(400, "INVALID_INPUT", upload(owner.token(), memory, "not-a-uuid", PhotoFileTest.JPEG, "image/jpeg"));
        assertError(400, "INVALID_INPUT", upload(owner.token(), memory, UUID.randomUUID().toString(), null, null));

        assertEquals(0, photoRows(memory));
        assertEquals(filesBefore, storedFiles());
    }

    @Test
    void clientFileNameIsNeverUsedAsAPath() throws Exception {
        Session owner = session();
        String memory = memory(owner);

        // upload() sends the file name "../../escape.jpg"; it must not decide where the file goes.
        assertEquals(201, upload(owner.token(), memory, UUID.randomUUID().toString(), PhotoFileTest.JPEG, "image/jpeg").statusCode());

        String storageKey = jdbc.sql("SELECT storage_key FROM photos WHERE memory_id = :memory")
                .param("memory", UUID.fromString(memory)).query(String.class).single();
        assertEquals(storageKey, UUID.fromString(storageKey).toString());
        assertTrue(Files.exists(PHOTOS.resolve(storageKey)));
        assertFalse(Files.exists(PHOTOS.getParent().getParent().resolve("escape.jpg")));
    }

    @Test
    void anotherAccountCannotUploadListOrDownloadEvenWithFullAddresses() throws Exception {
        Session owner = session();
        Session other = session();
        String memory = memory(owner);
        var uploaded = upload(owner.token(), memory, UUID.randomUUID().toString(), PhotoFileTest.JPEG, "image/jpeg");
        String contentUrl = json.readTree(uploaded.body()).path("contentUrl").asString();

        assertError(404, "MEMORY_NOT_FOUND", upload(other.token(), memory, UUID.randomUUID().toString(), PhotoFileTest.PNG, "image/png"));
        assertError(404, "MEMORY_NOT_FOUND", send("GET", "/memories/" + memory + "/photos", other.token()));
        assertError(404, "MEMORY_NOT_FOUND", sendUrl("GET", contentUrl, other.token()));
        assertEquals(401, sendUrl("GET", contentUrl, null).statusCode());

        assertEquals(1, photoRows(memory));
        assertEquals(200, sendUrl("GET", contentUrl, owner.token()).statusCode());
    }

    @Test
    void rolledBackUploadLeavesNeitherARowNorAFile() throws Exception {
        Session owner = session();
        String memory = memory(owner);
        long filesBefore = storedFiles();

        transactions.executeWithoutResult(status -> {
            try {
                photos.upload(owner.userId(), UUID.fromString(memory), UUID.randomUUID(), PhotoFileTest.JPEG, "image/jpeg");
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            status.setRollbackOnly();
        });

        assertEquals(0, photoRows(memory));
        assertEquals(filesBefore, storedFiles());
    }

    private record Session(UUID userId, String token) {}

    private Session session() throws Exception {
        String credentials = json.writeValueAsString(Map.of(
                "email", UUID.randomUUID() + "@example.test", "password", "Test için uzun parola 2026"));
        assertEquals(201, sendJson("/auth/register", credentials, null).statusCode());
        var login = sendJson("/auth/login", credentials, null);
        assertEquals(200, login.statusCode(), login.body());
        JsonNode body = json.readTree(login.body());
        return new Session(UUID.fromString(body.path("user").path("id").asString()), body.path("accessToken").asString());
    }

    private String memory(Session owner) throws Exception {
        var request = request("/api/v1/memories", owner.token())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(Map.of("title", "Fotoğraflı anı", "memoryDate", "2026-01-01"))));
        var created = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode(), created.body());
        return json.readTree(created.body()).path("id").asString();
    }

    /** Sends one multipart part named "file"; with null bytes only an unrelated text part is sent. */
    private HttpResponse<String> upload(String token, String memory, String key, byte[] bytes, String type)
            throws Exception {
        String boundary = "memorylane-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        if (bytes == null) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nno file\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        } else {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../../escape.jpg\"\r\n"
                    + "Content-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(bytes);
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = request("/api/v1/memories/" + memory + "/photos", token)
                .header("Idempotency-Key", key)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String apiPath, String token) throws Exception {
        return sendUrl(method, "/api/v1" + apiPath, token);
    }

    private HttpResponse<String> sendUrl(String method, String path, String token) throws Exception {
        return http.send(request(path, token).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(String apiPath, String body, String token) throws Exception {
        return http.send(request("/api/v1" + apiPath, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String token) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    /**
     * Above the servlet multipart limit the request is refused before the controller runs. The
     * server may answer 413 and close the connection before the client has finished sending the
     * body, so the client sees either the 413 body or a broken connection depending on timing
     * (this made CI flaky). Both mean the upload was refused; the caller then checks that nothing
     * was stored. The 413 body for this case is checked without the network in PhotoHttpContractTest.
     */
    private void assertRefusedAboveServletLimit(String token, String memory) throws Exception {
        HttpResponse<String> response;
        try {
            response = upload(token, memory, UUID.randomUUID().toString(), PhotoFileTest.jpegOfSize(6_500_000), "image/jpeg");
        } catch (IOException closedWhileSending) {
            return;
        }
        assertError(413, "FILE_TOO_LARGE", response);
    }

    private void assertError(int status, String code, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).path("code").asString(), response.body());
    }

    private long photoRows(String memory) {
        return jdbc.sql("SELECT count(*) FROM photos WHERE memory_id = :memory")
                .param("memory", UUID.fromString(memory)).query(Long.class).single();
    }

    private static long storedFiles() throws IOException {
        try (var files = Files.list(PHOTOS)) {
            return files.count();
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("memorylane-photos-").resolve("photos");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
