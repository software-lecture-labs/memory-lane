package com.memorylane.backend;

import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One PostgreSQL container per test JVM (singleton container pattern), matching the image in
 * backend/docker-compose.yml. Testcontainers removes it when the JVM exits.
 */
public final class SharedPostgres {
    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer("postgres:17");

    private SharedPostgres() {
    }

    public static synchronized PostgreSQLContainer container() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
        return CONTAINER;
    }

    /** Creates an empty database in the shared container so a test can start from a clean schema. */
    public static String createEmptyDatabase(String name) throws SQLException {
        PostgreSQLContainer postgres = container();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name);
    }
}
