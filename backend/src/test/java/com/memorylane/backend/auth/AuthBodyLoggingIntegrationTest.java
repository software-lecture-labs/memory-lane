package com.memorylane.backend.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import com.memorylane.backend.TestApi;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passwords must not reach the log even when someone turns request logging on to debug. Spring MVC
 * logs every request body it reads at DEBUG (shortened to 100 characters) and TRACE (in full);
 * before the fix that line carried {@code password=...} in clear text. The verbose levels are set
 * only for this test and restored afterwards, because log levels are shared by the whole test JVM.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
@ExtendWith(OutputCaptureExtension.class)
class AuthBodyLoggingIntegrationTest {
    private static final Map<String, LogLevel> VERBOSE = Map.of(
            "org.springframework.web", LogLevel.TRACE,
            "com.memorylane", LogLevel.TRACE,
            "org.hibernate.SQL", LogLevel.DEBUG,
            "org.hibernate.orm.jdbc.bind", LogLevel.TRACE);
    /** Short e-mails, so even DEBUG's 100-character cut would still include the password. */
    private static final String PASSWORD = "Gunluk parola denemesi 4821";
    private static final String WRONG_PASSWORD = "Yanlis parola denemesi 7305";

    @Value("${local.server.port}") int port;
    @Autowired LoggingSystem logging;
    private final Map<String, LogLevel> previous = new HashMap<>();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
    }

    @BeforeEach
    void verboseLogging() {
        VERBOSE.forEach((name, level) -> {
            var configuration = logging.getLoggerConfiguration(name);
            previous.put(name, configuration == null ? null : configuration.getConfiguredLevel());
            logging.setLogLevel(name, level);
        });
    }

    @AfterEach
    void restoreLogging() {
        previous.forEach(logging::setLogLevel);
    }

    @Test
    void passwordsAndTokensStayOutOfTheLogWithRequestLoggingOn(CapturedOutput output) throws Exception {
        TestApi api = new TestApi(port);
        String email = "l" + UUID.randomUUID().toString().substring(0, 8) + "@ex.test";
        String account = credentials(email, PASSWORD);

        assertEquals(201, api.send("POST", "/api/v1/auth/register", account, null).statusCode());
        assertEquals(400, api.send("POST", "/api/v1/auth/register", credentials(email, "kisa"), null).statusCode());
        assertEquals(401, api.send("POST", "/api/v1/auth/login", credentials(email, WRONG_PASSWORD), null).statusCode());
        // Unreadable, duplicated and extra fields are refused, and their bodies carry the password too.
        assertEquals(400, api.send("POST", "/api/v1/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"", null).statusCode());
        assertEquals(400, api.send("POST", "/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"password\":\"" + WRONG_PASSWORD + "\"}", null).statusCode());
        assertEquals(400, api.send("POST", "/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"remember\":\"" + WRONG_PASSWORD + "\"}", null).statusCode());

        var login = api.send("POST", "/api/v1/auth/login", account, null);
        assertEquals(200, login.statusCode(), login.body());
        String token = field(login.body(), "accessToken");
        assertEquals(200, api.get("/api/v1/auth/me", token).statusCode());
        assertEquals(204, api.send("POST", "/api/v1/auth/logout", null, token).statusCode());

        String log = output.getAll();
        // Not vacuous: Spring really logged the bodies it read, with the credentials masked.
        assertTrue(log.contains("Read \"application/json"), "request body logging was captured");
        assertTrue(log.contains(AuthRequestBody.MASKED), "the body was logged in its masked form");
        // TRACE also logs each call's arguments; /me and /logout must not hand it the token.
        assertTrue(log.contains("Arguments: "), "handler argument logging was captured");
        for (String secret : List.of(PASSWORD, WRONG_PASSWORD, token)) {
            assertFalse(log.contains(secret), "a password or token reached the log");
        }
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }
}
