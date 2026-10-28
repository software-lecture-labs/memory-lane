package com.memorylane.backend.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Uses the existing Flyway-owned schema; credentials never leave this package as DTOs. */
@Repository
public class AuthRepository {
    private final JdbcTemplate jdbc;
    public AuthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    record Account(UUID id, String email, String passwordHash) { }

    Optional<Account> find(String email) {
        return jdbc.query("SELECT id,email,password_hash FROM users WHERE email=?",
                (rs, row) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), email)
                .stream().findFirst();
    }
    boolean insert(UUID id, String email, String hash) {
        return jdbc.update("INSERT INTO users(id,email,password_hash) VALUES (?,?,?) ON CONFLICT(email) DO NOTHING",
                id, email, hash) == 1;
    }
    void session(UUID user, String hash, Instant now, Instant expires) {
        jdbc.update("INSERT INTO sessions(user_id,token_hash,created_at,expires_at) VALUES (?,?,?,?)",
                user, hash, Timestamp.from(now), Timestamp.from(expires));
    }
    Optional<Account> authenticated(String hash, Instant now) {
        return jdbc.query("SELECT u.id,u.email,u.password_hash FROM users u JOIN sessions s ON s.user_id=u.id "
                        + "WHERE s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>?",
                (rs, row) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
                hash, Timestamp.from(now)).stream().findFirst();
    }
    void revoke(String hash, Instant now) {
        jdbc.update("UPDATE sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL", Timestamp.from(now), hash);
    }
}
