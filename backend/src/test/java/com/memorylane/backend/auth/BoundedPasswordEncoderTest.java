package com.memorylane.backend.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.*;

class BoundedPasswordEncoderTest {
    @Test
    void encodeAndVerifyShareTheSameBoundAndReleasePermits() throws Exception {
        PasswordEncoder delegate = mock(PasswordEncoder.class);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(delegate.encode("password"))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return "encoded";
                        });
        when(delegate.matches("password", "encoded"))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                            return true;
                        });
        var encoder = new BoundedPasswordEncoder(delegate, 2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var encode = pool.submit(() -> encoder.encode("password"));
            var verify = pool.submit(() -> encoder.matches("password", "encoded"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AuthFailure busy = assertThrows(AuthFailure.class, () -> encoder.encode("password"));
            assertEquals(503, busy.status);
            assertEquals("AUTH_BUSY", busy.code);
            assertEquals(1, busy.retryAfter);
            release.countDown();
            assertEquals("encoded", encode.get(5, TimeUnit.SECONDS));
            assertTrue(verify.get(5, TimeUnit.SECONDS));
            assertEquals("encoded", encoder.encode("password"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void exceptionDoesNotLeakAPermit() {
        PasswordEncoder delegate = mock(PasswordEncoder.class);
        when(delegate.encode("password"))
                .thenThrow(new IllegalStateException("failure"))
                .thenReturn("encoded");
        var encoder = new BoundedPasswordEncoder(delegate, 1);
        assertThrows(IllegalStateException.class, () -> encoder.encode("password"));
        assertEquals("encoded", encoder.encode("password"));
    }

    @Test
    void invalidConcurrencyFailsAtStartup() {
        PasswordEncoder delegate = mock(PasswordEncoder.class);
        assertThrows(IllegalArgumentException.class, () -> new BoundedPasswordEncoder(delegate, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedPasswordEncoder(delegate, 9));
    }
}
