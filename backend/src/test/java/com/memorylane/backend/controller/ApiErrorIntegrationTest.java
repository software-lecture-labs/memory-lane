package com.memorylane.backend.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import com.memorylane.backend.TestApi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ML-18 over real HTTP (MockMvc does not run the servlet error page forward these errors take):
 * every refusal, including ones that never reach a controller, uses the contract's single
 * {code,message} body with Cache-Control: no-store, and requests that skip the mobile form's
 * checks are rejected without writing anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
class ApiErrorIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MISSING = "11111111-1111-4111-8111-111111111111";

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private TestApi.Login owner;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
        // Every cached test context keeps its own pool open against the one shared container;
        // a small pool keeps the whole suite under PostgreSQL's 100-connection limit.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
    }

    @BeforeEach
    void signIn() throws Exception {
        owner = new TestApi(port).registerAndLogin();
    }

    @Test
    void errorsOutsideControllersUseTheContractBody() throws Exception {
        String memory = createMemory();

        assertError(404, "HTTP_ERROR", send("GET", "/api/v1/yok", null, null));
        assertError(404, "HTTP_ERROR", send("GET", "/yok", null, null));
        assertError(404, "HTTP_ERROR", send("DELETE", "/api/v1/memories/" + memory + "/photos/" + MISSING, null, null));

        var method = send("DELETE", "/api/v1/memories", null, null);
        assertError(405, "HTTP_ERROR", method);
        assertEquals(Set.of("GET", "POST"), Set.of(method.headers().firstValue("Allow").orElse("").split(",\\s*")));
        assertError(405, "HTTP_ERROR", send("PATCH", "/api/v1/memories/" + memory, "application/json", "{}"));
        assertError(405, "HTTP_ERROR", send("POST", "/api/health", null, null));

        assertError(415, "HTTP_ERROR",
                send("POST", "/api/v1/memories/" + memory + "/photos", "application/json", "{}"));

        var xml = request("GET", "/api/v1/memories", null, null).header("Accept", "application/xml");
        assertError(406, "HTTP_ERROR", http.send(xml.build(), HttpResponse.BodyHandlers.ofString()));

        // Tomcat rejects this path before Spring sees it; without JsonErrorReportValve it is an HTML page.
        assertError(400, "INVALID_INPUT", send("GET", "/api/v1/memories/%00", null, null));
    }

    @Test
    void requestsThatSkipTheFormChecksAreRejectedAndWriteNothing() throws Exception {
        long before = memories();
        List<String> invalidBodies = List.of(
                "{\"title\":5,\"memoryDate\":\"2025-01-01\"}",
                "{\"title\":\"Tarih\",\"memoryDate\":20250101}",
                "{\"title\":\"Tarih\",\"memoryDate\":\"2025-02-30\"}",
                "{\"title\":\"Konum\",\"memoryDate\":\"2025-01-01\",\"location\":{\"latitude\":41}}",
                "{\"title\":\"Konum\",\"memoryDate\":\"2025-01-01\",\"location\":{\"latitude\":\"41\",\"longitude\":29}}",
                "{\"title\":\"Konum\",\"memoryDate\":\"2025-01-01\",\"location\":{\"latitude\":41,\"longitude\":181}}",
                "{\"title\":\"Konum\",\"memoryDate\":\"2025-01-01\",\"location\":{\"latitude\":1e400,\"longitude\":29}}",
                "{\"title\":\"Konum\",\"memoryDate\":\"2025-01-01\",\"location\":[41,29]}",
                "{\"title\":\"Sahip\",\"memoryDate\":\"2025-01-01\",\"ownerId\":\"" + MISSING + "\"}",
                "{\"title\":\"Kimlik\",\"memoryDate\":\"2025-01-01\",\"id\":\"" + MISSING + "\"}");
        for (String body : invalidBodies) {
            assertError(400, "INVALID_INPUT", create(UUID.randomUUID().toString(), body));
        }

        // A field named twice would otherwise silently keep its last value.
        assertError(400, "INVALID_JSON", create(UUID.randomUUID().toString(),
                "{\"title\":\"Birinci\",\"title\":\"İkinci\",\"memoryDate\":\"2025-01-01\"}"));
        assertError(400, "INVALID_JSON", send("POST", "/api/v1/auth/login", "application/json",
                "{\"email\":\"a@example.test\",\"email\":\"b@example.test\",\"password\":\"Uzun güvenli parola 123\"}"));

        assertError(400, "INVALID_INPUT", create("abc", "{\"title\":\"Anahtar\",\"memoryDate\":\"2025-01-01\"}"));
        assertEquals(before, memories(), "no rejected request wrote a memory");

        assertError(400, "INVALID_INPUT", send("GET", "/api/v1/memories/abc", null, null));
        assertError(400, "INVALID_INPUT", send("GET", "/api/v1/memories/" + createMemory() + "/photos/abc/content", null, null));
        assertError(404, "MEMORY_NOT_FOUND", send("GET", "/api/v1/memories/" + MISSING, null, null));
    }

    private String createMemory() throws Exception {
        var response = create(UUID.randomUUID().toString(), "{\"title\":\"Hata testi\",\"memoryDate\":\"2025-01-01\"}");
        assertEquals(201, response.statusCode(), response.body());
        return JSON.readTree(response.body()).path("id").asString();
    }

    private long memories() {
        return jdbc.sql("SELECT count(*) FROM memories WHERE owner_id = :owner")
                .param("owner", owner.userId()).query(Long.class).single();
    }

    private HttpResponse<String> create(String key, String body) throws Exception {
        var builder = request("POST", "/api/v1/memories", "application/json", body).header("Idempotency-Key", key);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String method, String path, String contentType, String body) throws Exception {
        return http.send(request(method, path, contentType, body).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String method, String path, String contentType, String body) {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + owner.token());
        if (contentType != null) builder.header("Content-Type", contentType);
        return builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    }

    /** Status, code, no-store, and nothing but code and message in the body. */
    private static void assertError(int status, String code, HttpResponse<String> response) {
        String context = response.request().method() + " " + response.request().uri() + " -> " + response.body();
        assertEquals(status, response.statusCode(), context);
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), context);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""), context);
        JsonNode body = JSON.readTree(response.body());
        assertEquals(Set.of("code", "message"), Set.copyOf(body.propertyNames()), context);
        assertEquals(code, body.path("code").asString(), context);
        assertFalse(body.path("message").asString().isBlank(), context);
    }
}
