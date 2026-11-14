package com.memorylane.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal HTTP client for integration tests that need real accounts and sessions. */
public final class TestApi {
    public static final String PASSWORD = "Uzun güvenli parola 123";

    public record Login(UUID userId, String token) {}

    private final int port;
    private final HttpClient http = HttpClient.newHttpClient();

    public TestApi(int port) {
        this.port = port;
    }

    /** Registers a fresh account and returns its id with a valid bearer token. */
    public Login registerAndLogin() throws Exception {
        String body = credentials();
        assertEquals(201, send("POST", "/api/v1/auth/register", body, null).statusCode());
        var response = send("POST", "/api/v1/auth/login", body, null);
        assertEquals(200, response.statusCode(), response.body());
        return new Login(UUID.fromString(field(response.body(), "id")), field(response.body(), "accessToken"));
    }

    public static String credentials() {
        return "{\"email\":\"" + UUID.randomUUID() + "@example.test\",\"password\":\"" + PASSWORD + "\"}";
    }

    public HttpResponse<String> get(String path, String token) throws Exception {
        return send("GET", path, null, token);
    }

    public HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }
}
