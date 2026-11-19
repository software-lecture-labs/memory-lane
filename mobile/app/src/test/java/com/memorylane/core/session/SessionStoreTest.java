package com.memorylane.core.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionStoreTest {
    private final List<Runnable> pending = new ArrayList<>();
    private final List<SessionStore.EndReason> events = new CopyOnWriteArrayList<>();
    private SessionStore store;

    @Before
    public void setUp() {
        // Queues notifications so tests can tell "notified via the executor" from "notified inline".
        store = new SessionStore(pending::add);
        store.addListener(events::add);
    }

    private void runPending() {
        List<Runnable> tasks = new ArrayList<>(pending);
        pending.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    public void startsSignedOut() {
        assertFalse(store.isActive());
        assertNull(store.token());
        assertNull(store.authorization());
        assertNull(store.email());
    }

    @Test
    public void startKeepsTokenAndEmailExactly() {
        store.start("tok-123", "ayse@example.com");

        assertTrue(store.isActive());
        assertEquals("tok-123", store.token());
        assertEquals("Bearer tok-123", store.authorization());
        assertEquals("ayse@example.com", store.email());
    }

    @Test
    public void startRejectsBlankValues() {
        assertThrows(IllegalArgumentException.class, () -> store.start(" ", "a@example.com"));
        assertThrows(IllegalArgumentException.class, () -> store.start(null, "a@example.com"));
        assertThrows(IllegalArgumentException.class, () -> store.start("tok", ""));
        assertThrows(IllegalArgumentException.class, () -> store.start("tok", null));
        assertFalse(store.isActive());
    }

    @Test
    public void startReplacesPreviousSessionWithoutEndEvent() {
        store.start("old", "a@example.com");
        store.start("new", "b@example.com");
        runPending();

        assertEquals("Bearer new", store.authorization());
        assertEquals("b@example.com", store.email());
        assertTrue(events.isEmpty());
    }

    @Test
    public void logoutClearsAtOnceAndReturnsEndedAuthorization() {
        store.start("tok", "a@example.com");

        assertEquals("Bearer tok", store.logout());

        // Cleared before any listener runs: the server's answer to logout is never awaited.
        assertFalse(store.isActive());
        assertNull(store.authorization());
        assertNull(store.email());
        assertTrue(events.isEmpty());
        runPending();
        assertEquals(List.of(SessionStore.EndReason.LOGGED_OUT), events);
    }

    @Test
    public void logoutWithoutSessionReturnsNullAndSendsNoEvent() {
        assertNull(store.logout());
        runPending();
        assertTrue(events.isEmpty());
    }

    @Test
    public void secondLogoutSendsNoSecondEvent() {
        store.start("tok", "a@example.com");
        store.logout();
        assertNull(store.logout());
        runPending();
        assertEquals(List.of(SessionStore.EndReason.LOGGED_OUT), events);
    }

    @Test
    public void expireWithCurrentTokenEndsSession() {
        store.start("tok", "a@example.com");

        assertTrue(store.expire("tok"));
        runPending();

        assertFalse(store.isActive());
        assertEquals(List.of(SessionStore.EndReason.EXPIRED), events);
    }

    @Test
    public void lateRejectionOfPreviousAccountKeepsNewSession() {
        store.start("old", "a@example.com");
        store.logout();
        store.start("new", "b@example.com");
        runPending();
        events.clear();

        assertFalse(store.expire("old"));
        runPending();

        assertEquals("Bearer new", store.authorization());
        assertEquals("b@example.com", store.email());
        assertTrue(events.isEmpty());
    }

    @Test
    public void expireWithoutSessionOrTokenDoesNothing() {
        assertFalse(store.expire("tok"));
        store.start("tok", "a@example.com");
        assertFalse(store.expire(null));
        assertFalse(store.expire("Bearer tok"));
        runPending();

        assertTrue(store.isActive());
        assertTrue(events.isEmpty());
    }

    @Test
    public void removedListenerIsNotCalled() {
        List<SessionStore.EndReason> other = new ArrayList<>();
        SessionStore.Listener listener = other::add;
        store.addListener(listener);
        store.removeListener(listener);
        store.start("tok", "a@example.com");
        store.logout();
        runPending();

        assertTrue(other.isEmpty());
        assertEquals(List.of(SessionStore.EndReason.LOGGED_OUT), events);
    }

    @Test
    public void concurrentRejectionsEndSessionOnce() throws InterruptedException {
        AtomicInteger ended = new AtomicInteger();
        SessionStore shared = new SessionStore(Runnable::run);
        shared.addListener(reason -> ended.incrementAndGet());
        shared.start("tok", "a@example.com");
        int threads = 8;
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        go.await();
                        shared.expire("tok");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            go.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, ended.get());
        assertFalse(shared.isActive());
    }
}
