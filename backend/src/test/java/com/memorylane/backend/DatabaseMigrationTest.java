package com.memorylane.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@RequiresDocker
class DatabaseMigrationTest {
    @Test
    void emptyDatabaseIsMigratedAndApplicationRestartsOnExistingSchema() throws Exception {
        // A fresh database guarantees an empty start even when other tests already migrated the shared one.
        String jdbcUrl = SharedPostgres.createEmptyDatabase("migration_test");
        try (var context = start(jdbcUrl)) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(1, successfulMigrations(jdbc));
            List<String> tables = jdbc.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
            assertTrue(tables.containsAll(List.of("users", "sessions", "memories", "photos", "file_cleanup_jobs")), tables.toString());
        }
        // Second start must reuse the schema without re-running or failing validation.
        try (var context = start(jdbcUrl)) {
            assertEquals(1, successfulMigrations(context.getBean(JdbcTemplate.class)));
        }
    }

    private static ConfigurableApplicationContext start(String jdbcUrl) {
        return new SpringApplicationBuilder(MemoryLaneApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + jdbcUrl,
                        "--spring.datasource.username=" + SharedPostgres.container().getUsername(),
                        "--spring.datasource.password=" + SharedPostgres.container().getPassword());
    }

    private static int successfulMigrations(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
    }
}
