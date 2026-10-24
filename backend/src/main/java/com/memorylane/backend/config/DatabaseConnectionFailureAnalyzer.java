package com.memorylane.backend.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import java.sql.SQLException;
import java.util.Set;

/**
 * Turns a startup failure caused by an unreachable or misconfigured PostgreSQL
 * into a short, actionable message instead of a long Flyway/Hikari stack trace.
 */
public class DatabaseConnectionFailureAnalyzer extends AbstractFailureAnalyzer<SQLException> {
    // 28000/28P01: authentication failed, 3D000: database does not exist.
    private static final Set<String> CONFIGURATION_STATES = Set.of("28000", "28P01", "3D000");

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, SQLException cause) {
        String state = cause.getSQLState();
        if (state == null || !(state.startsWith("08") || CONFIGURATION_STATES.contains(state))) {
            return null;
        }
        String description = "Could not connect to PostgreSQL (SQLState " + state + "): " + cause.getMessage();
        String action = "Check that PostgreSQL is running and reachable from this host, and that "
                + "DB_HOST, DB_PORT, DB_NAME, DB_USER and DB_PASSWORD point to it with valid credentials.";
        return new FailureAnalysis(description, action, cause);
    }
}
