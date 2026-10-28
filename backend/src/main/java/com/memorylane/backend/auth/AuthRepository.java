package com.memorylane.backend.auth;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA mappings use the existing Flyway schema; conflict-safe registration uses PostgreSQL's atomic
 * insert.
 */
@Repository
public class AuthRepository {
    private final EntityManager entityManager;

    public AuthRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    record Account(UUID id, String email, String passwordHash) {
        @Override
        public String toString() {
            return "Account[credentials=REDACTED]";
        }
    }

    Optional<Account> find(String email) {
        return entityManager
                .createQuery("SELECT u FROM UserAccount u WHERE u.email=:email", UserAccount.class)
                .setParameter("email", email)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst()
                .map(u -> new Account(u.id, u.email, u.passwordHash));
    }

    boolean insert(UUID id, String email, String hash) {
        return entityManager
                        .createNativeQuery(
                                "INSERT INTO users(id,email,password_hash) VALUES"
                                        + " (:id,:email,:hash) ON CONFLICT(email) DO NOTHING")
                        .setParameter("id", id)
                        .setParameter("email", email)
                        .setParameter("hash", hash)
                        .executeUpdate()
                == 1;
    }

    void session(UUID user, String hash, Instant now, Instant expires) {
        UserSession session = new UserSession();
        session.user = entityManager.getReference(UserAccount.class, user);
        session.tokenHash = hash;
        session.createdAt = now;
        session.expiresAt = expires;
        entityManager.persist(session);
    }

    Optional<Account> authenticated(String hash, Instant now) {
        return entityManager
                .createQuery(
                        "SELECT s.user FROM UserSession s WHERE s.tokenHash=:hash "
                                + "AND s.revokedAt IS NULL AND s.expiresAt>:now",
                        UserAccount.class)
                .setParameter("hash", hash)
                .setParameter("now", now)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst()
                .map(u -> new Account(u.id, u.email, u.passwordHash));
    }

    void revoke(String hash, Instant now) {
        entityManager
                .createQuery(
                        "UPDATE UserSession s SET s.revokedAt=:now WHERE s.tokenHash=:hash AND"
                                + " s.revokedAt IS NULL")
                .setParameter("now", now)
                .setParameter("hash", hash)
                .executeUpdate();
    }
}
