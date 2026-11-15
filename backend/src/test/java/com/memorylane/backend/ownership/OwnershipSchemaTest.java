package com.memorylane.backend.ownership;

import static org.junit.jupiter.api.Assertions.*;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * ML-29 checklist item 1: every memory has a required owner that references a user, and the
 * ownership lookups (memories by owner, photos by memory) are backed by an index.
 */
@RequiresDocker
class OwnershipSchemaTest {
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        var dataSource = new DriverManagerDataSource(
                SharedPostgres.createEmptyDatabase("ownership_schema"),
                SharedPostgres.container().getUsername(),
                SharedPostgres.container().getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void memoryOwnerIsRequiredAndReferencesUsers() {
        assertFalse(nullable("memories", "owner_id"));
        assertTrue(foreignKey("memories", "owner_id", "users"));
    }

    @Test
    void memoriesAreIndexedByOwner() {
        assertTrue(indexStartsWith("memories", "owner_id"));
    }

    @Test
    void photoOwnershipGoesThroughAnIndexedMemoryReference() {
        assertFalse(nullable("photos", "memory_id"));
        assertTrue(foreignKey("photos", "memory_id", "memories"));
        assertTrue(indexStartsWith("photos", "memory_id"));
    }

    private static boolean nullable(String table, String column) {
        return "YES".equals(jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                String.class, table, column));
    }

    private static boolean foreignKey(String table, String column, String referenced) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.conrelid = ?::regclass AND c.confrelid = ?::regclass
                  AND cardinality(c.conkey) = 1 AND a.attname = ?""",
                Integer.class, table, referenced, column) > 0;
    }

    private static boolean indexStartsWith(String table, String column) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
                WHERE i.indrelid = ?::regclass AND a.attname = ?""",
                Integer.class, table, column) > 0;
    }
}
