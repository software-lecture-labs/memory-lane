package com.memorylane.backend.memory;

import static org.junit.jupiter.api.Assertions.*;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import com.memorylane.backend.TestApi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V6 against a real PostgreSQL: once a memory is deleted, a create retried with its
 * Idempotency-Key gets 409 IDEMPOTENCY_KEY_RETIRED and the memory stays deleted
 * (docs/api-contract.md "Tekrar deneme", ADR-0002). Keys are per owner, and a delete that rolls back
 * retires nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
class RetiredRequestKeyIntegrationTest {
    private static final String BODY = "{\"title\":\"Silinecek anı\",\"memoryDate\":\"2025-05-01\"}";

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    @Autowired MemoryService memories;
    @Autowired TransactionTemplate transactions;
    private final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
    }

    @Test
    void retryWithTheKeyOfADeletedMemoryIsRefusedAndBringsNothingBack() throws Exception {
        TestApi.Login owner = new TestApi(port).registerAndLogin();
        String key = UUID.randomUUID().toString();
        var created = create(owner.token(), key, BODY);
        assertEquals(201, created.statusCode(), created.body());
        String memory = id(created.body());
        assertEquals(204, send("DELETE", "/api/v1/memories/" + memory, owner.token(), null, null).statusCode());

        for (String body : new String[] {BODY, "{\"title\":\"Başka içerik\",\"memoryDate\":\"2025-05-02\"}"}) {
            var retried = create(owner.token(), key, body);
            assertEquals(409, retried.statusCode(), retried.body());
            assertTrue(retried.body().contains("\"IDEMPOTENCY_KEY_RETIRED\""), retried.body());
        }
        assertEquals(0, memories(owner.userId()), "the deleted memory did not come back");

        // A new key creates normally; another account may use the retired value (keys are per owner).
        assertEquals(201, create(owner.token(), UUID.randomUUID().toString(), BODY).statusCode());
        TestApi.Login other = new TestApi(port).registerAndLogin();
        assertEquals(201, create(other.token(), key, BODY).statusCode());
    }

    @Test
    void aDeleteThatRollsBackRetiresNothing() throws Exception {
        TestApi.Login owner = new TestApi(port).registerAndLogin();
        String key = UUID.randomUUID().toString();
        UUID memory = UUID.fromString(id(create(owner.token(), key, BODY).body()));

        transactions.executeWithoutResult(status -> {
            memories.delete(owner.userId(), memory);
            status.setRollbackOnly();
        });

        assertEquals(0L, retired(owner.userId()), "no key was retired");
        var replay = create(owner.token(), key, BODY);
        assertEquals(200, replay.statusCode(), "the memory is still there, so this is an ordinary replay");
        assertEquals(memory.toString(), id(replay.body()));
    }

    @Test
    void everyWayOfDeletingAMemoryRetiresItsKey() throws Exception {
        TestApi.Login owner = new TestApi(port).registerAndLogin();
        String key = UUID.randomUUID().toString();
        UUID memory = UUID.fromString(id(create(owner.token(), key, BODY).body()));

        // Not through the API: the trigger, not the service, does the retiring.
        jdbc.sql("DELETE FROM memories WHERE id = :id").param("id", memory).update();

        assertEquals(1L, retired(owner.userId()));
        assertEquals(409, create(owner.token(), key, BODY).statusCode());
    }

    private HttpResponse<String> create(String token, String key, String body) throws Exception {
        return send("POST", "/api/v1/memories", token, key, body);
    }

    private HttpResponse<String> send(String method, String path, String token, String key, String body)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long memories(UUID owner) {
        return jdbc.sql("SELECT count(*) FROM memories WHERE owner_id = :owner").param("owner", owner)
                .query(Long.class).single();
    }

    private long retired(UUID owner) {
        return jdbc.sql("SELECT count(*) FROM retired_request_keys WHERE owner_id = :owner").param("owner", owner)
                .query(Long.class).single();
    }

    private static String id(String json) {
        Matcher m = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }
}
