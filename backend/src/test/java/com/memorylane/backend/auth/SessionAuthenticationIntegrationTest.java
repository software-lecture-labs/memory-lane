package com.memorylane.backend.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The session filter protects every /api/v1 route outside /api/v1/auth (ML-29). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SessionAuthenticationIntegrationTest.Probe.class)
@RequiresDocker
class SessionAuthenticationIntegrationTest {
    static final String PASSWORD = "Uzun güvenli parola 123";

    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate jdbc;
    final HttpClient http = HttpClient.newHttpClient();

    /** Test-only endpoint standing in for the memory and photo controllers. */
    @RestController
    static class Probe {
        @GetMapping("/api/v1/session-probe")
        Map<String, UUID> me(@CurrentUser UUID user) {
            return Map.of("userId", user);
        }
    }

    record Login(UUID userId, String token) {}

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        r.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        r.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
    }

    @Test
    void requestWithoutTokenGets401WithBearerChallenge() throws Exception {
        var response = get("/api/v1/session-probe", null);
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"INVALID_SESSION\""), response.body());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void malformedTokenGets401() throws Exception {
        assertEquals(401, get("/api/v1/session-probe", "not-a-real-token").statusCode());
    }

    @Test
    void validTokenResolvesTheSessionOwner() throws Exception {
        Login login = registerAndLogin();
        var response = get("/api/v1/session-probe", login.token());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains(login.userId().toString()), response.body());
    }

    @Test
    void revokedTokenGets401() throws Exception {
        Login login = registerAndLogin();
        assertEquals(204, send("POST", "/api/v1/auth/logout", null, login.token()).statusCode());
        assertEquals(401, get("/api/v1/session-probe", login.token()).statusCode());
    }

    @Test
    void expiredSessionGets401() throws Exception {
        Login login = registerAndLogin();
        jdbc.update("UPDATE sessions SET expires_at = now() - interval '1 minute' WHERE user_id = ?", login.userId());
        assertEquals(401, get("/api/v1/session-probe", login.token()).statusCode());
    }

    @Test
    void unknownApiPathDoesNotRevealRoutesWithoutSession() throws Exception {
        assertEquals(401, get("/api/v1/does-not-exist", null).statusCode());
        assertEquals(404, get("/api/v1/does-not-exist", registerAndLogin().token()).statusCode());
    }

    @Test
    void publicEndpointsStayOpen() throws Exception {
        assertEquals(200, get("/api/health", null).statusCode());
        String body = "{\"email\":\"" + UUID.randomUUID() + "@example.test\",\"password\":\"" + PASSWORD + "\"}";
        assertEquals(201, send("POST", "/api/v1/auth/register", body, null).statusCode());
    }

    private Login registerAndLogin() throws Exception {
        String body = "{\"email\":\"" + UUID.randomUUID() + "@example.test\",\"password\":\"" + PASSWORD + "\"}";
        assertEquals(201, send("POST", "/api/v1/auth/register", body, null).statusCode());
        var response = send("POST", "/api/v1/auth/login", body, null);
        assertEquals(200, response.statusCode(), response.body());
        return new Login(UUID.fromString(field(response.body(), "id")), field(response.body(), "accessToken"));
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send("GET", path, null, token);
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
}
