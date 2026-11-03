package com.memorylane.backend.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Single-process demo limiter. Expired keys are removed; no raw email/IP is retained. */
@Component
public class AuthLimiter {
    private static final int IP_ATTEMPTS_PER_MINUTE = 20;
    private static final int ACCOUNT_FAILURES_PER_MINUTE = 5;
    private static final int MAX_TRACKED_KEYS = 10_000;

    private record Window(Instant end, int count) {}

    private final Map<String, Window> windows = new HashMap<>();
    private final Object[] accountLocks = new Object[256];
    private final Clock clock;

    public AuthLimiter(Clock clock) {
        this.clock = clock;
        java.util.Arrays.setAll(accountLocks, i -> new Object());
    }

    private String key(String scope, String value) {
        return scope + AuthService.digest(value);
    }

    private void purge() {
        Instant now = clock.instant();
        windows.values().removeIf(w -> !w.end().isAfter(now));
    }

    private void check(String key, int limit) {
        Window w = windows.get(key);
        if (w != null && w.count() >= limit)
            throw new AuthFailure(
                    429,
                    "RATE_LIMITED",
                    "Daha sonra yeniden deneyin.",
                    Math.max(1, (w.end().toEpochMilli() - clock.millis() + 999) / 1000));
    }

    private void increment(String key) {
        Window w = windows.get(key);
        if (w == null && windows.size() >= MAX_TRACKED_KEYS)
            throw new AuthFailure(429, "RATE_LIMITED", "Daha sonra yeniden deneyin.", 60);
        windows.put(
                key,
                w == null
                        ? new Window(clock.instant().plusSeconds(60), 1)
                        : new Window(w.end(), w.count() + 1));
    }

    public synchronized void ip(String ip) {
        purge();
        String k = key("ip:", ip);
        check(k, IP_ATTEMPTS_PER_MINUTE);
        increment(k);
    }

    public <T> T login(String email, Supplier<T> authenticate) {
        String k = key("email:", email);
        // Bounded stripes serialize each account without holding the global counter lock during
        // hashing.
        synchronized (accountLocks[Math.floorMod(k.hashCode(), accountLocks.length)]) {
            checkAccount(k);
            try {
                T result = authenticate.get();
                clearFailures(k);
                return result;
            } catch (AuthFailure failure) {
                if (failure.status == 401) {
                    recordFailure(k);
                }
                throw failure;
            }
        }
    }

    private synchronized void checkAccount(String key) {
        purge();
        check(key, ACCOUNT_FAILURES_PER_MINUTE);
    }

    private synchronized void recordFailure(String key) {
        increment(key);
    }

    private synchronized void clearFailures(String key) {
        windows.remove(key);
    }
}
