package com.memorylane.backend.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import com.memorylane.backend.TestApi;

import org.junit.jupiter.api.BeforeEach;
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

import java.util.Map;
import java.util.UUID;

/** The session filter protects every /api/v1 route outside /api/v1/auth (ML-29). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SessionAuthenticationIntegrationTest.Probe.class)
@RequiresDocker
class SessionAuthenticationIntegrationTest {
    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate jdbc;
    TestApi api;

    /** Test-only endpoint standing in for the memory and photo controllers. */
    @RestController
    static class Probe {
        @GetMapping("/api/v1/session-probe")
        Map<String, UUID> me(@CurrentUser UUID user) {
            return Map.of("userId", user);
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
    void requestWithoutTokenGets401WithBearerChallenge() throws Exception {
        var response = api.get("/api/v1/session-probe", null);
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"INVALID_SESSION\""), response.body());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void malformedTokenGets401() throws Exception {
        assertEquals(401, api.get("/api/v1/session-probe", "not-a-real-token").statusCode());
    }

    @Test
    void validTokenResolvesTheSessionOwner() throws Exception {
        TestApi.Login login = api.registerAndLogin();
        var response = api.get("/api/v1/session-probe", login.token());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains(login.userId().toString()), response.body());
    }

    @Test
    void revokedTokenGets401() throws Exception {
        TestApi.Login login = api.registerAndLogin();
        assertEquals(204, api.send("POST", "/api/v1/auth/logout", null, login.token()).statusCode());
        assertEquals(401, api.get("/api/v1/session-probe", login.token()).statusCode());
    }

    @Test
    void expiredSessionGets401() throws Exception {
        TestApi.Login login = api.registerAndLogin();
        jdbc.update("UPDATE sessions SET expires_at = now() - interval '1 minute' WHERE user_id = ?", login.userId());
        assertEquals(401, api.get("/api/v1/session-probe", login.token()).statusCode());
    }

    @Test
    void unknownApiPathDoesNotRevealRoutesWithoutSession() throws Exception {
        assertEquals(401, api.get("/api/v1/does-not-exist", null).statusCode());
        assertEquals(404, api.get("/api/v1/does-not-exist", api.registerAndLogin().token()).statusCode());
    }

    @Test
    void publicEndpointsStayOpen() throws Exception {
        assertEquals(200, api.get("/api/health", null).statusCode());
        assertEquals(201, api.send("POST", "/api/v1/auth/register", TestApi.credentials(), null).statusCode());
    }
}
