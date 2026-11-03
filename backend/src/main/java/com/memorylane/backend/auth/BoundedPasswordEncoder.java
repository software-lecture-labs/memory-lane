package com.memorylane.backend.auth;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Bounds the expensive Argon2 work across registration, login and unknown-user checks. */
final class BoundedPasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate;
    private final Semaphore permits;

    BoundedPasswordEncoder(PasswordEncoder delegate, int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 8) {
            throw new IllegalArgumentException("Auth hash concurrency must be between 1 and 8");
        }
        this.delegate = delegate;
        this.permits = new Semaphore(maxConcurrent);
    }

    @Override
    public String encode(CharSequence password) {
        return execute(() -> delegate.encode(password));
    }

    @Override
    public boolean matches(CharSequence password, String encodedPassword) {
        return execute(() -> delegate.matches(password, encodedPassword));
    }

    private <T> T execute(Supplier<T> operation) {
        if (!permits.tryAcquire()) {
            throw new AuthFailure(503, "AUTH_BUSY", "Kimlik doğrulama meşgul; yeniden deneyin.", 1);
        }
        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }
}
