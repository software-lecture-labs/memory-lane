package com.memorylane.backend.memory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class MemoryService {
    private static final String SUMMARY = """
            SELECT m.*, (SELECT count(*) FROM photos WHERE memory_id = m.id) AS photo_count,
                (SELECT id FROM photos WHERE memory_id = m.id ORDER BY position LIMIT 1) AS cover_photo_id
            FROM memories m WHERE m.owner_id = :owner
            """;
    private static final String FILTER = """
            AND (:category = '' OR m.category = :category)
            AND (:q = '' OR strpos(lower(normalize(m.title) COLLATE memorylane_tr),
                                    lower(normalize(CAST(:q AS text)) COLLATE memorylane_tr)) > 0
                         OR strpos(lower(normalize(coalesce(m.description, '')) COLLATE memorylane_tr),
                                    lower(normalize(CAST(:q AS text)) COLLATE memorylane_tr)) > 0)
            """;

    private final JdbcClient jdbc;
    private static final JsonMapper HASH_JSON = JsonMapper.builder().build();

    public MemoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Summary(UUID id, String title, String description, LocalDate memoryDate, String category,
                   String emotion, MemoryInput.Location location, Instant createdAt, Instant updatedAt,
                   long photoCount, UUID coverPhotoId) {}

    record Page(List<Summary> content, int page, int size, long totalElements, long totalPages,
                boolean hasNext) {}

    record Photo(UUID id, UUID memoryId, String contentType, long sizeBytes, int order,
                 String contentUrl, Instant createdAt) {}

    record Created(Summary summary, List<Photo> photos, boolean replayed) {}

    @Transactional
    public Created create(UUID owner, UUID key, MemoryInput input) {
        String hash = hash(input);
        var inserted = jdbc.sql("""
                INSERT INTO memories (owner_id, client_request_id, client_request_sha256, title,
                    description, memory_date, category, mood, latitude, longitude)
                VALUES (:owner, :key, :hash, :title, :description, :day, :category, :mood, :latitude, :longitude)
                ON CONFLICT (owner_id, client_request_id) DO NOTHING RETURNING id
                """).param("owner", owner).param("key", key).param("hash", hash)
                .param("title", input.title()).param("description", input.description())
                .param("day", input.memoryDate()).param("category", input.category()).param("mood", input.emotion())
                .param("latitude", input.location() == null ? null : input.location().latitude())
                .param("longitude", input.location() == null ? null : input.location().longitude())
                .query(UUID.class).optional();

        return jdbc.sql(SUMMARY + " AND m.client_request_id = :key FOR SHARE OF m")
                .param("owner", owner).param("key", key)
                .query((row, number) -> {
                    if (!hash.equals(row.getString("client_request_sha256"))) {
                        throw new IdempotencyConflict();
                    }
                    return summary(row);
                }).optional()
                .map(memory -> new Created(memory, photos(memory.id()), inserted.isEmpty()))
                .orElseThrow(IdempotencyConflict::new);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(UUID owner, int page, int size, String query, String category) {
        long count = filtered("SELECT count(*) FROM memories m WHERE m.owner_id = :owner ", "", owner, query, category)
                .query(Long.class).single();
        long pages = (count + size - 1) / size;
        List<Summary> content = filtered(SUMMARY, " ORDER BY m.memory_date DESC, m.id DESC LIMIT :size OFFSET :offset",
                owner, query, category)
                .param("size", size).param("offset", (long) page * size)
                .query((row, number) -> summary(row)).list();
        return new Page(content, page, size, count, pages, page + 1L < pages);
    }

    private JdbcClient.StatementSpec filtered(String sql, String order, UUID owner, String query, String category) {
        return jdbc.sql(sql + FILTER + order).param("owner", owner).param("q", query).param("category", category);
    }

    private List<Photo> photos(UUID memoryId) {
        return jdbc.sql("SELECT * FROM photos WHERE memory_id = :id ORDER BY position")
                .param("id", memoryId).query((row, number) -> {
                    UUID id = row.getObject("id", UUID.class);
                    return new Photo(id, memoryId, row.getString("media_type"), row.getLong("size_bytes"),
                            row.getInt("position"), "/api/v1/memories/" + memoryId + "/photos/" + id + "/content",
                            instant(row, "created_at"));
                }).list();
    }

    private static Summary summary(ResultSet row) throws SQLException {
        Double latitude = row.getObject("latitude", Double.class);
        var location = latitude == null ? null : new MemoryInput.Location(latitude, row.getDouble("longitude"));
        return new Summary(row.getObject("id", UUID.class), row.getString("title"),
                java.util.Objects.toString(row.getString("description"), ""), row.getObject("memory_date", LocalDate.class),
                row.getString("category"), row.getString("mood"), location,
                instant(row, "created_at"), instant(row, "updated_at"),
                row.getLong("photo_count"), row.getObject("cover_photo_id", UUID.class));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    static String hash(MemoryInput input) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (JsonGenerator json = HASH_JSON.createGenerator(bytes)) {
                json.writeStartObject();
                json.writeStringProperty("title", input.title());
                json.writeStringProperty("description", input.description());
                json.writeStringProperty("memoryDate", input.memoryDate().toString());
                json.writeStringProperty("category", input.category());
                if (input.emotion() == null) json.writeNullProperty("emotion");
                else json.writeStringProperty("emotion", input.emotion());
                if (input.location() == null) {
                    json.writeNullProperty("location");
                } else {
                    json.writeObjectPropertyStart("location");
                    json.writeName("latitude");
                    json.writeNumber(BigDecimal.valueOf(input.location().latitude()).stripTrailingZeros().toPlainString());
                    json.writeName("longitude");
                    json.writeNumber(BigDecimal.valueOf(input.location().longitude()).stripTrailingZeros().toPlainString());
                    json.writeEndObject();
                }
                json.writeEndObject();
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static class IdempotencyConflict extends RuntimeException {}
}
