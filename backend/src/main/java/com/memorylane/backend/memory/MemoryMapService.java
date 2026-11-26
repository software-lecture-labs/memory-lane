package com.memorylane.backend.memory;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Map points of the session user's memories that have a location (ML-16, contract "Harita"). Not
 * paged like the list: a pin must not disappear because its memory is on another list page. The
 * same owner, category and search filter as the list is applied in SQL before the limit.
 */
@Service
public class MemoryMapService {
    /** Contract: at most 1000 points in the first release; the rest is reported as truncated. */
    static final int LIMIT = 1000;

    private final JdbcClient jdbc;

    public MemoryMapService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record Point(UUID id, String title, LocalDate memoryDate, double latitude, double longitude,
                 UUID coverPhotoId) {}

    record Points(List<Point> points, int limit, int returned, boolean truncated) {}

    @Transactional(readOnly = true)
    public Points points(UUID owner, String query, String category) {
        // One row more than the limit tells whether anything was left out, without a second count query.
        List<Point> found = jdbc.sql("""
                SELECT m.id, m.title, m.memory_date, m.latitude, m.longitude,
                    (SELECT id FROM photos WHERE memory_id = m.id ORDER BY position LIMIT 1) AS cover_photo_id
                FROM memories m
                WHERE m.owner_id = :owner AND m.latitude IS NOT NULL
                """ + MemoryService.FILTER + """
                ORDER BY m.memory_date DESC, m.id DESC
                LIMIT :limit
                """)
                .param("owner", owner).param("q", query).param("category", category).param("limit", LIMIT + 1)
                .query(MemoryMapService::point).list();
        boolean truncated = found.size() > LIMIT;
        List<Point> points = truncated ? found.subList(0, LIMIT) : found;
        return new Points(points, LIMIT, points.size(), truncated);
    }

    private static Point point(ResultSet row, int number) throws SQLException {
        // latitude and longitude are both set or both null (ck_memories_location_pair).
        return new Point(row.getObject("id", UUID.class), row.getString("title"),
                row.getObject("memory_date", LocalDate.class), row.getDouble("latitude"),
                row.getDouble("longitude"), row.getObject("cover_photo_id", UUID.class));
    }
}
