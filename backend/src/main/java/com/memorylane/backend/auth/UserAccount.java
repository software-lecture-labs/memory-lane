package com.memorylane.backend.auth;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserAccount {
    @Id UUID id;

    @Column(nullable = false, length = 254)
    String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    String passwordHash;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    protected UserAccount() {}
}
