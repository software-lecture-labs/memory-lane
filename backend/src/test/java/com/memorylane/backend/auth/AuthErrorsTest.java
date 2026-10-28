package com.memorylane.backend.auth;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

class AuthErrorsTest {
    private final AuthErrors errors = new AuthErrors();

    @Test
    void unavailableDatabaseIsRetryableButIntegrityFailureIsInternal() {
        var unavailable = errors.database();
        assertEquals(503, unavailable.getStatusCode().value());
        assertEquals("DATABASE_UNAVAILABLE", unavailable.getBody().get("code"));
        var integrity =
                errors.unexpected(new DataIntegrityViolationException("sensitive SQL detail"));
        assertEquals(500, integrity.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", integrity.getBody().get("code"));
        assertFalse(integrity.getBody().toString().contains("sensitive"));
    }

    @Test
    void responseHeadersDescribeSessionAndRateLimitFailures() {
        var session = errors.auth(AuthService.invalidSession());
        assertEquals("Bearer", session.getHeaders().getFirst("WWW-Authenticate"));
        assertEquals("no-store", session.getHeaders().getFirst("Cache-Control"));
        var limit = errors.auth(new AuthFailure(429, "RATE_LIMITED", "Wait", 17));
        assertEquals("17", limit.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void credentialObjectsDoNotExposeSecretsThroughToString() {
        var account =
                new AuthRepository.Account(
                        UUID.randomUUID(), "private@example.test", "secret-password-hash");
        var login =
                new AuthService.LoginResponse(
                        "secret-token",
                        "Bearer",
                        Instant.now(),
                        new AuthService.UserResponse(account.id(), account.email()));
        assertFalse(account.toString().contains("secret-password-hash"));
        assertFalse(account.toString().contains(account.email()));
        assertFalse(login.toString().contains("secret-token"));
        assertFalse(login.toString().contains(account.email()));
    }
}
