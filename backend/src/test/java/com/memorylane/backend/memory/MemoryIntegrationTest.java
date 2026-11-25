package com.memorylane.backend.memory;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
class MemoryIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
    }

    @Test
    void createReplayAndReadOnlyAsOwnerAfterSigningInAgain() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String owner = session(email, true);
        String other = session(UUID.randomUUID() + "@example.test", true);
        String key = UUID.randomUUID().toString();

        assertEquals(401, send("GET", "/memories", null, null, null).statusCode());
        assertEquals(400, create(owner, key, "   ").statusCode());
        var created = create(owner, key, "  İlk anı  ");
        assertEquals(201, created.statusCode(), created.body());
        var detail = json.readTree(created.body());
        assertEquals("İlk anı", detail.path("title").asString());
        assertEquals("OTHER", detail.path("category").asString());
        assertTrue(detail.path("photos").isArray());
        assertEquals("/api/v1/memories/" + detail.path("id").asString(),
                created.headers().firstValue("Location").orElseThrow());

        var replay = create(owner, key, "İlk anı");
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals("true", replay.headers().firstValue("Idempotency-Replayed").orElseThrow());
        assertEquals(detail.path("id"), json.readTree(replay.body()).path("id"));
        assertEquals(409, create(owner, key, "Başka içerik").statusCode());

        assertEquals(0, json.readTree(send("GET", "/memories", other, null, null).body())
                .path("totalElements").asInt());
        assertEquals(204, send("POST", "/auth/logout", owner, null, null).statusCode());
        assertEquals(401, send("GET", "/memories", owner, null, null).statusCode());
        String signedInAgain = session(email, false);
        var page = json.readTree(send("GET", "/memories", signedInAgain, null, null).body());
        assertEquals(1, page.path("totalElements").asInt());
        assertEquals(detail.path("id"), page.path("content").get(0).path("id"));
        assertEquals(400, send("GET", "/memories?page=x", signedInAgain, null, null).statusCode());
    }

    @Test
    void turkishSearchFiltersBeforePaginationAndTreatsWildcardsLiterally() throws Exception {
        String owner = session(UUID.randomUUID() + "@example.test", true);
        String other = session(UUID.randomUUID() + "@example.test", true);
        for (int i = 0; i < 25; i++) {
            assertEquals(201, create(owner, UUID.randomUUID().toString(), "İstanbul %_" + i).statusCode());
        }
        assertEquals(201, create(owner, UUID.randomUUID().toString(), "Işık").statusCode());
        assertEquals(201, create(other, UUID.randomUUID().toString(), "İstanbul %_").statusCode());

        String filter = "/memories?q=istanbul%20%25_&size=20&category=OTHER";
        var first = json.readTree(send("GET", filter, owner, null, null).body());
        var second = json.readTree(send("GET", filter + "&page=1", owner, null, null).body());
        assertEquals(20, first.path("content").size());
        assertEquals(5, second.path("content").size());
        assertEquals(25, first.path("totalElements").asInt());
        assertEquals(2, first.path("totalPages").asInt());
        assertTrue(first.path("hasNext").asBoolean());
        assertFalse(second.path("hasNext").asBoolean());
        assertEquals(0, json.readTree(send("GET", "/memories?q=isik", owner, null, null).body())
                .path("totalElements").asInt());
        assertEquals(1, json.readTree(send("GET", "/memories?q=%C4%B1%C5%9F%C4%B1k", owner, null, null).body())
                .path("totalElements").asInt());
    }

    @Test
    void createFormRulesAreEnforcedOverHttpAndRejectedRequestsWriteNothing() throws Exception {
        String owner = session(UUID.randomUUID() + "@example.test", true);
        String emoji = new String(Character.toChars(0x1F600));
        String future = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2).toString();

        // PostgreSQL VARCHAR(n) counts characters, so the 100 / 3000 limits hold for emoji too.
        var full = new java.util.HashMap<String, Object>(java.util.Map.of(
                "title", emoji.repeat(100), "description", emoji.repeat(3000), "memoryDate", "2024-02-29",
                "category", "TRAVEL", "emotion", "HAPPY",
                "location", java.util.Map.of("latitude", 41.0082, "longitude", 28.9784)));
        var created = send("POST", "/memories", owner, UUID.randomUUID().toString(), json.writeValueAsString(full));
        assertEquals(201, created.statusCode(), created.body());
        var detail = json.readTree(created.body());
        assertEquals(emoji.repeat(100), detail.path("title").asString());
        assertEquals(emoji.repeat(3000), detail.path("description").asString());
        assertEquals("2024-02-29", detail.path("memoryDate").asString());
        assertEquals("TRAVEL", detail.path("category").asString());
        assertEquals("HAPPY", detail.path("emotion").asString());
        assertEquals(41.0082, detail.path("location").path("latitude").asDouble());
        assertEquals(28.9784, detail.path("location").path("longitude").asDouble());

        var minimal = json.readTree(create(owner, UUID.randomUUID().toString(), "Sade").body());
        assertEquals("OTHER", minimal.path("category").asString());
        assertTrue(minimal.path("emotion").isNull());
        assertTrue(minimal.path("location").isNull());
        assertEquals("", minimal.path("description").asString());

        var rejected = java.util.List.of(
                java.util.Map.of("title", "a".repeat(101), "memoryDate", "2026-01-01"),
                java.util.Map.of("title", "Uzun", "description", "a".repeat(3001), "memoryDate", "2026-01-01"),
                java.util.Map.of("title", "Tarihsiz"),
                java.util.Map.of("title", "Gelecek", "memoryDate", future),
                java.util.Map.of("title", "Kategori", "memoryDate", "2026-01-01", "category", "Gezi"),
                java.util.Map.of("title", "Duygu", "memoryDate", "2026-01-01", "emotion", "ANGRY"),
                java.util.Map.of("title", "Konum", "memoryDate", "2026-01-01",
                        "location", java.util.Map.of("latitude", 91, "longitude", 0)),
                java.util.Map.of("title", "Alan", "memoryDate", "2026-01-01", "ownerId", UUID.randomUUID().toString()));
        for (var body : rejected) {
            var response = send("POST", "/memories", owner, UUID.randomUUID().toString(), json.writeValueAsString(body));
            assertEquals(400, response.statusCode(), body.keySet() + " " + response.body());
            var error = json.readTree(response.body());
            assertEquals("INVALID_INPUT", error.path("code").asString());
            assertFalse(response.body().contains("Exception"), response.body());
        }
        assertEquals(2, json.readTree(send("GET", "/memories", owner, null, null).body())
                .path("totalElements").asInt());
    }

    @Test
    void detailReturnsTheMemoryWithAStrongEtagOnlyToItsOwner() throws Exception {
        String owner = session(UUID.randomUUID() + "@example.test", true);
        String other = session(UUID.randomUUID() + "@example.test", true);
        var body = new java.util.HashMap<String, Object>(java.util.Map.of(
                "title", "Sahil yürüyüşü", "description", "Akşam yürüdük.", "memoryDate", "2026-01-02",
                "category", "TRAVEL", "emotion", "HAPPY",
                "location", java.util.Map.of("latitude", 41.0082, "longitude", 28.9784)));
        var created = json.readTree(send("POST", "/memories", owner, UUID.randomUUID().toString(),
                json.writeValueAsString(body)).body());
        String id = created.path("id").asString();

        var response = send("GET", "/memories/" + id, owner, null, null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        String etag = response.headers().firstValue("ETag").orElseThrow();
        assertTrue(etag.matches("\"memory-" + id + "-r\\d+\""), etag);
        var detail = json.readTree(response.body());
        // Same body as the create answer: summary fields unwrapped plus an empty photos array.
        assertEquals(created, detail);
        assertTrue(detail.path("photos").isArray());
        assertEquals(0, detail.path("photos").size());
        assertTrue(detail.path("coverPhotoId").isNull());
        assertFalse(detail.has("version"), "the version is server-only");
        assertFalse(detail.has("ownerId"));
        assertEquals(etag, send("GET", "/memories/" + id, owner, null, null)
                .headers().firstValue("ETag").orElseThrow(), "unchanged memory keeps its tag");

        // No session, foreign, missing and malformed ids: nothing about the memory is revealed.
        assertEquals(401, send("GET", "/memories/" + id, null, null, null).statusCode());
        var foreign = send("GET", "/memories/" + id, other, null, null);
        var missing = send("GET", "/memories/" + UUID.randomUUID(), owner, null, null);
        for (var notFound : java.util.List.of(foreign, missing)) {
            assertEquals(404, notFound.statusCode());
            assertEquals("MEMORY_NOT_FOUND", json.readTree(notFound.body()).path("code").asString());
            assertTrue(notFound.headers().firstValue("ETag").isEmpty());
        }
        assertEquals(foreign.body(), missing.body());
        var malformed = send("GET", "/memories/not-a-uuid", owner, null, null);
        assertEquals(400, malformed.statusCode());
        assertEquals("INVALID_INPUT", json.readTree(malformed.body()).path("code").asString());
        assertEquals("Anı kimliği geçersiz.", json.readTree(malformed.body()).path("message").asString());
    }

    /**
     * PUT acceptance from the contract. One account for the whole scenario: the auth limiter allows
     * 20 register/login requests per minute for this test class.
     */
    @Test
    void putReplacesTheMemoryOnlyWithItsCurrentTagAndRefusalsWriteNothing() throws Exception {
        String owner = session(UUID.randomUUID() + "@example.test", true);
        var full = new java.util.HashMap<String, Object>(java.util.Map.of(
                "title", "Sahil yürüyüşü", "description", "Akşam yürüdük.", "memoryDate", "2026-01-02",
                "category", "TRAVEL", "emotion", "HAPPY",
                "location", java.util.Map.of("latitude", 41.0082, "longitude", 28.9784)));
        var created = json.readTree(send("POST", "/memories", owner, UUID.randomUUID().toString(),
                json.writeValueAsString(full)).body());
        String id = created.path("id").asString();
        String path = "/memories/" + id;
        String first = etag(owner, path);
        assertTrue(first.endsWith("-r1\""), first);
        String minimal = json.writeValueAsString(java.util.Map.of("title", "  Yeni başlık  ", "memoryDate", "2025-12-31"));

        // Refusals: none of them may change the memory or its version.
        assertEquals(401, put(null, path, first, minimal).statusCode());
        assertError(404, "MEMORY_NOT_FOUND", put(owner, "/memories/" + UUID.randomUUID(), first, minimal));
        assertError(428, "PRECONDITION_REQUIRED", put(owner, path, null, minimal));
        for (String invalid : java.util.List.of("*", "\"bozuk\"", "memory-" + id + "-r1",
                first + ", " + first, "\"memory-" + UUID.randomUUID() + "-r1\"")) {
            assertError(400, "INVALID_PRECONDITION", put(owner, path, invalid, minimal));
        }
        // Contract: If-Match compares strongly, so a weak tag never matches (412, not 400).
        assertError(412, "MEMORY_MODIFIED", put(owner, path, "W/" + first, minimal));
        for (var body : java.util.List.of(
                java.util.Map.of("title", "   ", "memoryDate", "2026-01-01"),
                java.util.Map.of("title", "a".repeat(101), "memoryDate", "2026-01-01"),
                java.util.Map.of("title", "Uzun", "description", "a".repeat(3001), "memoryDate", "2026-01-01"),
                java.util.Map.of("title", "Tarihsiz"),
                java.util.Map.of("title", "Gelecek", "memoryDate", java.time.LocalDate.now().plusDays(2).toString()),
                java.util.Map.of("title", "Kategori", "memoryDate", "2026-01-01", "category", "Gezi"),
                java.util.Map.of("title", "Konum", "memoryDate", "2026-01-01",
                        "location", java.util.Map.of("latitude", 0, "longitude", 181)),
                java.util.Map.of("title", "Alan", "memoryDate", "2026-01-01", "photos", java.util.List.of()))) {
            assertError(400, "INVALID_INPUT", put(owner, path, first, json.writeValueAsString(body)));
        }
        assertEquals(first, etag(owner, path));
        assertEquals(created, json.readTree(send("GET", path, owner, null, null).body()));

        // Success: optional fields left out return to their defaults; photos and createdAt stay.
        var replaced = put(owner, path, first, minimal);
        assertEquals(200, replaced.statusCode(), replaced.body());
        assertTrue(replaced.headers().firstValue("ETag").isEmpty(), "the next edit takes its tag from GET");
        assertEquals("no-store", replaced.headers().firstValue("Cache-Control").orElseThrow());
        var updated = json.readTree(replaced.body());
        assertEquals(id, updated.path("id").asString());
        assertEquals("Yeni başlık", updated.path("title").asString());
        assertEquals("", updated.path("description").asString());
        assertEquals("2025-12-31", updated.path("memoryDate").asString());
        assertEquals("OTHER", updated.path("category").asString());
        assertTrue(updated.path("emotion").isNull());
        assertTrue(updated.path("location").isNull());
        assertTrue(updated.path("photos").isArray());
        assertEquals(created.path("createdAt"), updated.path("createdAt"));
        assertTrue(java.time.Instant.parse(updated.path("updatedAt").asString())
                .isAfter(java.time.Instant.parse(created.path("updatedAt").asString())));
        String second = etag(owner, path);
        assertEquals(first.replace("-r1\"", "-r2\""), second, "the version went up by exactly one");
        assertEquals(updated, json.readTree(send("GET", path, owner, null, null).body()));

        // The old tag is now stale: 412 and the stored memory stays as it is.
        assertError(412, "MEMORY_MODIFIED", put(owner, path, first, json.writeValueAsString(full)));
        assertEquals(second, etag(owner, path));

        // Every writable field can be set again with the current tag.
        var restored = json.readTree(put(owner, path, second, json.writeValueAsString(full)).body());
        assertEquals("TRAVEL", restored.path("category").asString());
        assertEquals("HAPPY", restored.path("emotion").asString());
        assertEquals(41.0082, restored.path("location").path("latitude").asDouble());
        assertEquals("Akşam yürüdük.", restored.path("description").asString());

        // Two concurrent PUTs with the same tag: the row lock lets exactly one of them write.
        String third = etag(owner, path);
        var api = new com.memorylane.backend.TestApi(port);
        var writers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = writers.submit(() -> api.send("PUT", "/api/v1" + path, json.writeValueAsString(
                    java.util.Map.of("title", "Cihaz A", "memoryDate", "2026-01-02")), owner, java.util.Map.of("If-Match", third)));
            var b = writers.submit(() -> api.send("PUT", "/api/v1" + path, json.writeValueAsString(
                    java.util.Map.of("title", "Cihaz B", "memoryDate", "2026-01-02")), owner, java.util.Map.of("If-Match", third)));
            var statuses = new java.util.ArrayList<>(java.util.List.of(a.get().statusCode(), b.get().statusCode()));
            java.util.Collections.sort(statuses);
            assertEquals(java.util.List.of(200, 412), statuses);
            String winner = a.get().statusCode() == 200 ? "Cihaz A" : "Cihaz B";
            assertEquals(winner, json.readTree(send("GET", path, owner, null, null).body()).path("title").asString());
            assertEquals(third.replace("-r3\"", "-r4\""), etag(owner, path));
        } finally {
            writers.shutdownNow();
        }
        assertEquals(1, json.readTree(send("GET", "/memories", owner, null, null).body())
                .path("totalElements").asInt(), "PUT never creates a memory");
    }

    private HttpResponse<String> put(String token, String path, String ifMatch, String body) throws Exception {
        var api = new com.memorylane.backend.TestApi(port);
        return api.send("PUT", "/api/v1" + path, body, token,
                ifMatch == null ? java.util.Map.of() : java.util.Map.of("If-Match", ifMatch));
    }

    private String etag(String token, String path) throws Exception {
        var response = send("GET", path, token, null, null);
        assertEquals(200, response.statusCode(), response.body());
        return response.headers().firstValue("ETag").orElseThrow();
    }

    private void assertError(int status, String code, HttpResponse<String> response) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json.readTree(response.body()).path("code").asString(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(response.body().contains("Exception"), response.body());
    }

    private HttpResponse<String> create(String token, String key, String title) throws Exception {
        return send("POST", "/memories", token, key,
                json.writeValueAsString(java.util.Map.of("title", title, "memoryDate", "2026-01-01")));
    }

    private String session(String email, boolean register) throws Exception {
        String credentials = json.writeValueAsString(java.util.Map.of(
                "email", email, "password", "Test için uzun parola 2026"));
        if (register) assertEquals(201, send("POST", "/auth/register", null, null, credentials).statusCode());
        var response = send("POST", "/auth/login", null, null, credentials);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("accessToken").asString();
    }

    private HttpResponse<String> send(String method, String path, String token, String key, String body)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
