package com.memorylane.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseConnectionFailureAnalyzerTest {
    private final DatabaseConnectionFailureAnalyzer analyzer = new DatabaseConnectionFailureAnalyzer();

    @Test void explainsRefusedConnectionWrappedInStartupFailure() {
        var refused = new SQLException("Connection to localhost:5432 refused.", "08001");
        FailureAnalysis analysis = analyzer.analyze(new IllegalStateException("Flyway failed", refused));
        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("localhost:5432 refused"));
        assertTrue(analysis.getAction().contains("DB_HOST"));
    }

    @Test void explainsWrongPassword() {
        var authFailed = new SQLException("FATAL: password authentication failed for user \"memorylane\"", "28P01");
        assertNotNull(analyzer.analyze(new IllegalStateException(authFailed)));
    }

    @Test void ignoresUnrelatedSqlErrors() {
        var syntaxError = new SQLException("syntax error", "42601");
        assertNull(analyzer.analyze(new IllegalStateException(syntaxError)));
    }
}
