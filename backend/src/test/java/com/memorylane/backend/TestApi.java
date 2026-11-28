package com.memorylane.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
        return send(method, path, body, token, Map.of());
    }

    /** Same as above with extra request headers, e.g. {@code If-Match} or {@code Idempotency-Key}. */
    public HttpResponse<String> send(String method, String path, String body, String token,
                                     Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        headers.forEach(request::header);
        if (body != null) request.header("Content-Type", "application/json");
        var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Creates a memory through the API as {@code token}'s account and returns its id. */
    public UUID createMemory(String token, String title, Double latitude, Double longitude) throws Exception {
        String location = latitude == null ? ""
                : ",\"location\":{\"latitude\":" + latitude + ",\"longitude\":" + longitude + "}";
        var response = send("POST", "/api/v1/memories",
                "{\"title\":\"" + title + "\",\"memoryDate\":\"2026-01-01\"" + location + "}", token,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(field(response.body(), "id"));
    }

    /** Uploads {@code bytes} as the multipart part "file" to {@code memory}'s photos. */
    public HttpResponse<String> uploadPhoto(UUID memory, byte[] bytes, String contentType, String token)
            throws Exception {
        String boundary = "memorylane-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/memories/" + memory + "/photos"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A string field of a JSON body, e.g. the {@code id} of a created photo. */
    public static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }
}
