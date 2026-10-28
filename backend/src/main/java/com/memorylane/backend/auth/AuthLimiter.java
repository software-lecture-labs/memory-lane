package com.memorylane.backend.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Single-process demo limiter. Expired keys are removed; no raw email/IP is retained. */
@Component
public class AuthLimiter {
    private record Window(Instant end, int count) { }
    private final Map<String, Window> windows = new HashMap<>();
    private final Object[] accountLocks = new Object[256];
    private final Clock clock;
    public AuthLimiter(Clock clock) {
        this.clock = clock;
        java.util.Arrays.setAll(accountLocks, i -> new Object());
    }
    private String key(String scope, String value) { return scope + AuthService.digest(value); }
    private void purge() { Instant now = clock.instant(); windows.values().removeIf(w -> !w.end().isAfter(now)); }
    private void check(String key, int limit) {
        Window w = windows.get(key);
        if (w != null && w.count() >= limit) throw new AuthFailure(429, "RATE_LIMITED", "Daha sonra yeniden deneyin.",
                Math.max(1, (w.end().toEpochMilli() - clock.millis() + 999) / 1000));
    }
    private void increment(String key) {
        Window w = windows.get(key);
        if (w == null && windows.size() >= 10000) throw new AuthFailure(429, "RATE_LIMITED", "Daha sonra yeniden deneyin.", 60);
        windows.put(key, w == null ? new Window(clock.instant().plusSeconds(60), 1) : new Window(w.end(), w.count() + 1));
    }
    public synchronized void ip(String ip) { purge(); String k = key("ip:", ip); check(k, 20); increment(k); }
    public AuthService.LoginResponse login(String email, String password, AuthService service) {
        String k = key("email:", email);
        // Bounded stripes serialize each account without holding the global counter lock during hashing.
        synchronized (accountLocks[Math.floorMod(k.hashCode(), accountLocks.length)]) {
            synchronized (this) { purge(); check(k, 5); increment(k); }
            try {
                var result = service.login(email, password);
                synchronized (this) { windows.remove(k); }
                return result;
            } catch (RuntimeException e) {
                if (!(e instanceof AuthFailure failure) || failure.status != 401) {
                    synchronized (this) {
                        Window w = windows.get(k);
                        if (w != null) windows.put(k, new Window(w.end(), Math.max(0, w.count()-1)));
                    }
                }
                throw e;
            }
        }
    }
}
