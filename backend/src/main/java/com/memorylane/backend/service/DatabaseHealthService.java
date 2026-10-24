package com.memorylane.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DatabaseHealthService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthService.class);
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    // Log only state changes so frequent health polling cannot flood the log while the database is down.
    private final AtomicBoolean lastKnownUp = new AtomicBoolean(true);

    public DatabaseHealthService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public boolean isDatabaseUp() {
        String failure;
        try (Connection connection = dataSource.getConnection()) {
            failure = connection.isValid(VALIDATION_TIMEOUT_SECONDS) ? null : "connection validation failed";
        } catch (SQLException e) {
            // Driver messages name host, port and user but never the password.
            failure = "SQLState " + e.getSQLState() + ": " + e.getMessage();
        }
        boolean up = failure == null;
        if (lastKnownUp.getAndSet(up) != up) {
            if (up) {
                log.info("Database connection restored");
            } else {
                log.warn("Database health check failed ({})", failure);
            }
        } else if (!up) {
            log.debug("Database still unavailable ({})", failure);
        }
        return up;
    }
}
