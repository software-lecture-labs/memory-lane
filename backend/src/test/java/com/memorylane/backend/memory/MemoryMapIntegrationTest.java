package com.memorylane.backend.memory;

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
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ML-16 acceptance for GET /api/v1/memories/map against a real PostgreSQL: only the session
 * user's located memories, in contract order, not limited by list paging, with the list's
 * search and category filters and the 1000-point limit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
class MemoryMapIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${local.server.port}") int port;
    @Autowired JdbcClient jdbc;
    TestApi api;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
    }

    @BeforeEach
    void client() {
        api = new TestApi(port);
    }

    @Test
    void returnsOnlyOwnLocatedMemoriesNewestFirstWithCover() throws Exception {
        TestApi.Login owner = api.registerAndLogin();
        UUID older = memory(owner.userId(), "Eski sahil", "2026-01-10", "TRAVEL", 41.0, 29.0);
        UUID newer = memory(owner.userId(), "Yeni sahil", "2026-03-05", "TRAVEL", 38.4, 27.1);
        memory(owner.userId(), "Konumsuz", "2026-04-01", "OTHER", null, null);
        // The cover is the photo with the lowest position; positions are not renumbered (ML-04).
        UUID cover = photo(newer, 1);
        photo(newer, 2);

        JsonNode map = map("", owner.token());

        assertEquals(List.of(newer.toString(), older.toString()), ids(map));
        JsonNode first = map.path("points").get(0);
        assertEquals("Yeni sahil", first.path("title").asText());
        assertEquals("2026-03-05", first.path("memoryDate").asText());
        assertEquals(38.4, first.path("latitude").asDouble());
        assertEquals(27.1, first.path("longitude").asDouble());
        assertEquals(cover.toString(), first.path("coverPhotoId").asText());
        assertTrue(map.path("points").get(1).path("coverPhotoId").isNull());
        assertEquals(1000, map.path("limit").asInt());
        assertEquals(2, map.path("returned").asInt());
        assertFalse(map.path("truncated").asBoolean());
    }

    @Test
    void otherAccountsPointsNeverAppear() throws Exception {
        TestApi.Login a = api.registerAndLogin();
        TestApi.Login b = api.registerAndLogin();
        memory(a.userId(), "A'nın gizli yeri", "2026-02-02", "FAMILY", 40.0, 30.0);
        UUID ofB = memory(b.userId(), "B'nin yeri", "2026-02-03", "FAMILY", 39.0, 32.0);

        JsonNode map = map("", b.token());

        assertEquals(List.of(ofB.toString()), ids(map));
        // Searching for A's title does not reveal it either.
        assertEquals(List.of(), ids(map("?q=" + encode("gizli"), b.token())));
    }

    @Test
    void searchAndCategoryUseTheListRules() throws Exception {
        TestApi.Login owner = api.registerAndLogin();
        UUID travel = memory(owner.userId(), "İstanbul gezisi", "2026-05-01", "TRAVEL", 41.0, 29.0);
        UUID family = memory(owner.userId(), "İstanbul'da aile yemeği", "2026-05-02", "FAMILY", 41.1, 29.1);
        memory(owner.userId(), "Ankara", "2026-05-03", "TRAVEL", 39.9, 32.8);

        // Turkish case rules, as in the list search: "istanbul" matches "İstanbul".
        assertEquals(List.of(family.toString(), travel.toString()), ids(map("?q=istanbul", owner.token())));
        assertEquals(List.of(travel.toString()), ids(map("?q=istanbul&category=TRAVEL", owner.token())));
        assertEquals(400, api.get("/api/v1/memories/map?category=SPACE", owner.token()).statusCode());
    }

    @Test
    void pagingParametersAreRefused() throws Exception {
        String token = api.registerAndLogin().token();
        for (String query : List.of("?page=0", "?size=20", "?page=1&size=5")) {
            var response = api.get("/api/v1/memories/map" + query, token);
            assertEquals(400, response.statusCode(), query);
            assertTrue(response.body().contains("\"INVALID_INPUT\""), response.body());
        }
    }

    @Test
    void moreThanTheLimitIsCutAndReportedNotHiddenByListPaging() throws Exception {
        TestApi.Login owner = api.registerAndLogin();
        // 1001 located memories, one per day, so the newest 1000 are the expected answer.
        jdbc.sql("""
                INSERT INTO memories (owner_id, client_request_id, client_request_sha256, title, memory_date,
                    latitude, longitude)
                SELECT :owner, gen_random_uuid(), :hash, 'Nokta ' || n, DATE '2020-01-01' + n, 40, 30
                FROM generate_series(1, 1001) AS n
                """).param("owner", owner.userId()).param("hash", "a".repeat(64)).update();

        JsonNode map = map("", owner.token());

        assertEquals(1000, map.path("points").size());
        assertEquals(1000, map.path("returned").asInt());
        assertTrue(map.path("truncated").asBoolean());
        assertEquals("Nokta 1001", map.path("points").get(0).path("title").asText());
        assertEquals("Nokta 2", map.path("points").get(999).path("title").asText());
    }

    @Test
    void requestWithoutSessionGets401() throws Exception {
        assertEquals(401, api.get("/api/v1/memories/map", null).statusCode());
    }

    private JsonNode map(String query, String token) throws Exception {
        var response = api.get("/api/v1/memories/map" + query, token);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        return JSON.readTree(response.body());
    }

    private static List<String> ids(JsonNode map) {
        List<String> ids = new ArrayList<>();
        map.path("points").forEach(point -> ids.add(point.path("id").asText()));
        return ids;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private UUID memory(UUID owner, String title, String date, String category, Double latitude, Double longitude) {
        return jdbc.sql("""
                INSERT INTO memories (owner_id, client_request_id, client_request_sha256, title, memory_date,
                    category, latitude, longitude)
                VALUES (:owner, :key, :hash, :title, :day, :category, :latitude, :longitude) RETURNING id""")
                .param("owner", owner).param("key", UUID.randomUUID()).param("hash", "a".repeat(64))
                .param("title", title).param("day", LocalDate.parse(date)).param("category", category)
                .param("latitude", latitude).param("longitude", longitude)
                .query(UUID.class).single();
    }

    private UUID photo(UUID memory, int position) {
        return jdbc.sql("""
                INSERT INTO photos (memory_id, client_photo_id, content_sha256, storage_key, media_type, size_bytes, position)
                VALUES (:memory, :key, :hash, :storage, 'image/jpeg', 1000, :position) RETURNING id""")
                .param("memory", memory).param("key", UUID.randomUUID()).param("hash", "b".repeat(64))
                .param("storage", UUID.randomUUID().toString()).param("position", position)
                .query(UUID.class).single();
    }
}
