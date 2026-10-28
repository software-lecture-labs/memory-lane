package com.memorylane.backend.auth;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "sessions")
class UserSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    UserAccount user;

    @Column(name = "token_hash", nullable = false, length = 128)
    String tokenHash;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "revoked_at")
    Instant revokedAt;

    protected UserSession() {}
}
