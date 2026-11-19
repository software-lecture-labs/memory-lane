package com.memorylane.backend.photo;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Retry timing only; the database behaviour is in PhotoFileCleanupIntegrationTest. */
class PhotoFileCleanupTest {
    @Test
    void retriesWaitLongerEachTimeUpToADay() {
        assertEquals(Duration.ofMinutes(1), PhotoFileCleanup.retryDelay(1));
        assertEquals(Duration.ofMinutes(2), PhotoFileCleanup.retryDelay(2));
        assertEquals(Duration.ofMinutes(4), PhotoFileCleanup.retryDelay(3));
        assertEquals(Duration.ofMinutes(512), PhotoFileCleanup.retryDelay(10));
        assertEquals(Duration.ofDays(1), PhotoFileCleanup.retryDelay(12));
        assertEquals(Duration.ofDays(1), PhotoFileCleanup.retryDelay(Integer.MAX_VALUE));
    }

    @Test
    void aJobWithNoAttemptsYetStartsAtTheShortestDelay() {
        assertEquals(Duration.ofMinutes(1), PhotoFileCleanup.retryDelay(0));
    }
}
