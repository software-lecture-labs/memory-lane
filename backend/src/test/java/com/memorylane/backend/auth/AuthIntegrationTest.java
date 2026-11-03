package com.memorylane.backend.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
class AuthIntegrationTest {
    @Value("${local.server.port}")
    int port;

    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoSpyBean PasswordEncoder encoder;
    @Autowired AuthSessionCleanup sessionCleanup;
    final AtomicReference<Instant> now = new AtomicReference<>();
    static final String PASSWORD = "Uzun güvenli parola 123";
    final HttpClient http = HttpClient.newHttpClient();
    String email;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        r.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        r.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
    }

    @BeforeEach
    void setup() {
        // Every test advances beyond previous limiter windows, regardless of test ordering.
        now.set(Instant.ofEpochSecond(sequence.addAndGet(100_000)));
        when(clock.instant()).thenAnswer(i -> now.get());
        when(clock.millis()).thenAnswer(i -> now.get().toEpochMilli());
        email = "auth-" + UUID.randomUUID() + "@example.test";
    }

    static final java.util.concurrent.atomic.AtomicLong sequence =
            new java.util.concurrent.atomic.AtomicLong(1800000000);

    HttpResponse<String> send(String method, String path, String body, String token)
            throws Exception {
        var b =
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/auth/" + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        return http.send(
                b.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    String credentials(String mail, String password) {
        return "{\"email\":\"" + mail + "\",\"password\":\"" + password + "\"}";
    }

    void register() throws Exception {
        assertEquals(
                201, send("POST", "register", credentials(email, PASSWORD), null).statusCode());
    }

    String login() throws Exception {
        var r = send("POST", "login", credentials(email, PASSWORD), null);
        assertEquals(200, r.statusCode());
        var m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(r.body());
        assertTrue(m.find());
        return m.group(1);
    }

    @Test
    void expensivePasswordWorkRunsWithoutDatabaseTransaction() throws Exception {
        doAnswer(
                        invocation -> {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return invocation.callRealMethod();
                        })
                .when(encoder)
                .encode(any(CharSequence.class));
        doAnswer(
                        invocation -> {
                            assertFalse(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            return invocation.callRealMethod();
                        })
                .when(encoder)
                .matches(any(CharSequence.class), anyString());
        register();
        assertEquals(200, send("GET", "me", null, login()).statusCode());
    }

    @Test
    void cleanupRemovesExpiredSessionsAndPreservesActiveOnes() throws Exception {
        register();
        String active = login();
        String expired = login();
        String revoked = login();
        assertEquals(204, send("POST", "logout", null, revoked).statusCode());
        jdbc.update(
                "UPDATE sessions SET expires_at=? WHERE token_hash=?",
                java.sql.Timestamp.from(now.get()),
                AuthService.digest(expired));
        assertEquals(401, send("GET", "me", null, expired).statusCode());
        sessionCleanup.cleanup();
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM sessions WHERE token_hash=?",
                        Integer.class,
                        AuthService.digest(expired)));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT count(*) FROM sessions WHERE token_hash=?",
                        Integer.class,
                        AuthService.digest(active)));
        assertEquals(200, send("GET", "me", null, active).statusCode());
        assertEquals(401, send("GET", "me", null, expired).statusCode());
        assertEquals(401, send("GET", "me", null, revoked).statusCode());
    }

    @Test
    void wrongMethodHasStableErrorBodyOnRunningServer() throws Exception {
        var response = send("GET", "login", null, null);
        assertEquals(405, response.statusCode());
        assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(response.body().contains("\"code\":\"HTTP_ERROR\""));
    }

    @Test
    void malformedEmailIsRejectedBeforeAccountCreation() throws Exception {
        String prefix = "invalid-" + UUID.randomUUID();
        var response =
                send(
                        "POST",
                        "register",
                        credentials(prefix + "\\u0000@example.test", PASSWORD),
                        null);
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("INVALID_INPUT"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE email LIKE ?",
                        Integer.class,
                        prefix + "%"));
    }

    @Test
    void lifecycleStoresOnlyHashesAndRevokesOnlyOneSession() throws Exception {
        var r =
                send(
                        "POST",
                        "register",
                        credentials("  " + email.toUpperCase(Locale.ROOT) + "  ", PASSWORD),
                        null);
        assertEquals(201, r.statusCode());
        assertTrue(r.body().contains(email));
        assertFalse(r.body().contains("password"));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElseThrow());
        String hash =
                jdbc.queryForObject(
                        "SELECT password_hash FROM users WHERE email=?", String.class, email);
        assertTrue(hash.startsWith("$argon2id$"));
        assertNotEquals(PASSWORD, hash);
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT count(*) FROM sessions s JOIN users u ON u.id=s.user_id WHERE"
                                + " u.email=?",
                        Integer.class,
                        email));
        String first = login(), second = login();
        assertNotEquals(first, second);
        assertEquals(43, first.length());
        String stored =
                jdbc.queryForObject(
                        "SELECT token_hash FROM sessions WHERE token_hash=?",
                        String.class,
                        AuthService.digest(first));
        assertEquals(64, stored.length());
        assertNotEquals(first, stored);
        assertEquals(200, send("GET", "me", null, first).statusCode());
        assertEquals(204, send("POST", "logout", null, first).statusCode());
        assertEquals(204, send("POST", "logout", null, first).statusCode());
        assertEquals(401, send("GET", "me", null, first).statusCode());
        assertEquals(200, send("GET", "me", null, second).statusCode());
    }

    @Test
    void expiresAtExactly24HoursAndRejectsMissingMalformedAndUnknownTokens() throws Exception {
        register();
        String token = login();
        now.set(now.get().plusSeconds(86399));
        assertEquals(200, send("GET", "me", null, token).statusCode());
        now.set(now.get().plusSeconds(1));
        var r = send("GET", "me", null, token);
        assertEquals(401, r.statusCode());
        assertTrue(r.body().contains("INVALID_SESSION"));
        assertEquals("Bearer", r.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(401, send("GET", "me", null, null).statusCode());
        assertEquals(401, send("POST", "logout", null, "malformed").statusCode());
        assertEquals(
                401,
                send(
                                "GET",
                                "me",
                                null,
                                Base64.getUrlEncoder()
                                        .withoutPadding()
                                        .encodeToString(new byte[32]))
                        .statusCode());
    }

    @Test
    void wrongPasswordAndUnknownEmailHaveSameResponse() throws Exception {
        register();
        var wrong = send("POST", "login", credentials(email, "wrong"), null);
        var unknown = send("POST", "login", credentials("missing-" + email, "wrong"), null);
        assertEquals(401, wrong.statusCode());
        assertEquals(wrong.body(), unknown.body());
    }

    @Test
    void duplicateConcurrentRegistrationsCreateOnlyOneAccount() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call =
                    () -> send("POST", "register", credentials(email, PASSWORD), null).statusCode();
            var results = pool.invokeAll(List.of(call, call));
            var statuses = new ArrayList<Integer>();
            for (var f : results) statuses.add(f.get());
            Collections.sort(statuses);
            assertEquals(List.of(201, 409), statuses);
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT count(*) FROM users WHERE email=?", Integer.class, email));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void acceptsUnicodePasswordWithoutTrimmingAndEnforcesBoundaries() throws Exception {
        String pass = " " + "😀".repeat(13) + " ";
        assertEquals(201, send("POST", "register", credentials(email, pass), null).statusCode());
        assertEquals(200, send("POST", "login", credentials(email, pass), null).statusCode());
        assertEquals(
                400,
                send("POST", "register", credentials("short-" + email, "x".repeat(14)), null)
                        .statusCode());
        assertEquals(
                201,
                send("POST", "register", credentials("long-" + email, "x".repeat(128)), null)
                        .statusCode());
        assertEquals(
                400,
                send("POST", "register", credentials("too-long-" + email, "x".repeat(129)), null)
                        .statusCode());
        assertEquals(
                400,
                send("POST", "register", "{\"email\":123,\"password\":true}", null).statusCode());
    }

    @Test
    void boundsBodyAndReturnsSafeJsonErrors() throws Exception {
        assertEquals(400, send("POST", "register", "{", null).statusCode());
        assertEquals(413, send("POST", "%6cogin", "x".repeat(8193), null).statusCode());
        var r = send("POST", "register", "{\"email\":\"wrong\",\"password\":\"secret\"}", null);
        assertEquals(400, r.statusCode());
        assertFalse(r.body().contains("secret"));
        assertFalse(r.body().contains("Exception"));
    }

    @Test
    void accountLimitBlocksSixthFailureAndExpires() throws Exception {
        for (int i = 0; i < 5; i++)
            assertEquals(
                    401, send("POST", "login", credentials(email, PASSWORD), null).statusCode());
        var r = send("POST", "login", credentials(email, PASSWORD), null);
        assertEquals(429, r.statusCode());
        assertTrue(Long.parseLong(r.headers().firstValue("Retry-After").orElseThrow()) > 0);
        now.set(now.get().plusSeconds(60));
        assertEquals(401, send("POST", "login", credentials(email, PASSWORD), null).statusCode());
    }

    @Test
    void ipLimitCountsMalformedRequests() throws Exception {
        for (int i = 0; i < 20; i++)
            assertEquals(400, send("POST", "login", "{", null).statusCode());
        var r = send("POST", "%6cogin", "{", null);
        assertEquals(429, r.statusCode());
        assertTrue(r.body().contains("RATE_LIMITED"));
    }
}
