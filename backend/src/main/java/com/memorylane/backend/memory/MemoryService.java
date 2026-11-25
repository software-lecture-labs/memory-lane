package com.memorylane.backend.memory;

import com.memorylane.backend.ownership.OwnershipGuard;
import com.memorylane.backend.ownership.ResourceNotFoundException;
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
    private final OwnershipGuard guard;
    private static final JsonMapper HASH_JSON = JsonMapper.builder().build();

    public MemoryService(JdbcClient jdbc, OwnershipGuard guard) {
        this.jdbc = jdbc;
        this.guard = guard;
    }

    record Summary(UUID id, String title, String description, LocalDate memoryDate, String category,
                   String emotion, MemoryInput.Location location, Instant createdAt, Instant updatedAt,
                   long photoCount, UUID coverPhotoId) {}

    record Page(List<Summary> content, int page, int size, long totalElements, long totalPages,
                boolean hasNext) {}

    record Photo(UUID id, UUID memoryId, String contentType, long sizeBytes, int order,
                 String contentUrl, Instant createdAt) {}

    record Created(Summary summary, List<Photo> photos, boolean replayed) {}

    /** A memory with its photos and the server-only version the detail ETag is made from. */
    record Found(Summary summary, List<Photo> photos, long version) {}

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

    /**
     * One memory of {@code owner} with its photos in {@code order ASC}. Missing and foreign memories
     * both throw {@link ResourceNotFoundException#memory()} (ADR-0003). The body and the version are
     * read from one snapshot, so the ETag always describes exactly the body it is sent with.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Found detail(UUID owner, UUID id) {
        guard.requireMemory(owner, id);
        return load(owner, id);
    }

    /**
     * Replaces the writable fields of {@code owner}'s memory if {@code ifMatch} is its current tag
     * (contract, PUT). One transaction: the row is locked first, so a foreign or missing memory is
     * 404 before the tag is looked at and the version cannot change between the check and the
     * write; of two writers with the same tag only the first succeeds, the second gets 412.
     * Photos are not touched. Any refusal writes nothing.
     */
    @Transactional
    public Found update(UUID owner, UUID id, String ifMatch, MemoryInput input) {
        guard.lockMemory(owner, id);
        long version = jdbc.sql("SELECT version FROM memories WHERE id = :id")
                .param("id", id).query(Long.class).single();
        MemoryPrecondition.check(ifMatch, id, version);
        // The version condition repeats the check in the statement itself, so the write can never
        // be unconditional even if the lock above were removed one day.
        int updated = jdbc.sql("""
                UPDATE memories SET title = :title, description = :description, memory_date = :day,
                    category = :category, mood = :mood, latitude = :latitude, longitude = :longitude,
                    version = version + 1, updated_at = now()
                WHERE id = :id AND owner_id = :owner AND version = :version
                """).param("title", input.title()).param("description", input.description())
                .param("day", input.memoryDate()).param("category", input.category()).param("mood", input.emotion())
                .param("latitude", input.location() == null ? null : input.location().latitude())
                .param("longitude", input.location() == null ? null : input.location().longitude())
                .param("id", id).param("owner", owner).param("version", version)
                .update();
        if (updated != 1) throw new MemoryPrecondition.Modified();
        return load(owner, id);
    }

    private Found load(UUID owner, UUID id) {
        record Row(Summary summary, long version) {}
        Row memory = jdbc.sql(SUMMARY + " AND m.id = :id")
                .param("owner", owner).param("id", id)
                .query((row, number) -> new Row(summary(row), row.getLong("version")))
                .single();
        return new Found(memory.summary(), photos(id), memory.version());
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
