package com.memorylane.backend.auth;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthLimiterTest {
    @Test void concurrentFailuresCannotExceedAccountQuota() throws Exception {
        var limiter = new AuthLimiter(Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC));
        AuthService service = mock(AuthService.class);
        when(service.login("user@example.test", "wrong"))
                .thenThrow(new AuthFailure(401,"INVALID_CREDENTIALS","Invalid credentials"));
        var pool = Executors.newFixedThreadPool(6);
        try {
            Callable<Integer> login = () -> {
                try { limiter.login("user@example.test", "wrong", service); return 200; }
                catch (AuthFailure failure) { return failure.status; }
            };
            var futures = pool.invokeAll(Collections.nCopies(6, login));
            var results = new ArrayList<Integer>(); for(var f:futures) results.add(f.get());
            assertEquals(5, Collections.frequency(results, 401));
            assertEquals(1, Collections.frequency(results, 429));
            verify(service,times(5)).login("user@example.test", "wrong");
        } finally { pool.shutdownNow(); }
    }
    @Test void successResetsFailuresAndExpiredWindowReopens() {
        var time = new AtomicReference<>(Instant.parse("2026-10-28T12:00:00Z"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(i -> time.get());
        when(clock.millis()).thenAnswer(i -> time.get().toEpochMilli());
        var limiter = new AuthLimiter(clock); AuthService service = mock(AuthService.class);
        when(service.login(anyString(),eq("bad"))).thenThrow(new AuthFailure(401,"INVALID_CREDENTIALS","Invalid"));
        when(service.login(anyString(),eq("good"))).thenReturn(new AuthService.LoginResponse("token","Bearer",time.get(),null));
        for(int i=0;i<4;i++) assertThrows(AuthFailure.class,()->limiter.login("a","bad",service));
        assertNotNull(limiter.login("a","good",service));
        for(int i=0;i<5;i++) assertEquals(401,assertThrows(AuthFailure.class,()->limiter.login("a","bad",service)).status);
        assertEquals(429,assertThrows(AuthFailure.class,()->limiter.login("a","good",service)).status);
        time.set(time.get().plusSeconds(60)); assertNotNull(limiter.login("a","good",service));
    }
}
