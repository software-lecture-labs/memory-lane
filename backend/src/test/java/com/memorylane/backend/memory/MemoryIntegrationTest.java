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
        assertEquals("İlk anı", detail.path("title").asText());
        assertEquals("OTHER", detail.path("category").asText());
        assertTrue(detail.path("photos").isArray());
        assertEquals("/api/v1/memories/" + detail.path("id").asText(),
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
        return json.readTree(response.body()).path("accessToken").asText();
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
