package com.memorylane.core.di;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.session.SessionStore;
import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

/**
 * Sign-out and session wiring of {@link AppContainer} over real HTTP: the local sign-out must be
 * complete before, and regardless of, the server's answer to the logout call.
 */
public class SessionLogoutTest {
    private static final String TOKEN = "tok-abc";
    private static final String EMAIL = "ayse@example.com";

    private MockWebServer server;
    private AppContainer app;
    private final List<SessionStore.EndReason> events = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        app = containerFor(server.url("/").toString());
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private AppContainer containerFor(String baseUrl) {
        AppContainer container = new AppContainer(baseUrl, Runnable::run);
        container.getSessionStore().addListener(events::add);
        return container;
    }

    /** Signs out and waits for the server's answer (or the transport failure). */
    private ApiResult<Void> endSessionAndAwaitServer(AppContainer container) throws InterruptedException {
        AtomicReference<ApiResult<Void>> answer = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        container.endSession(result -> {
            answer.set(result);
            done.countDown();
        });
        // Signed out before the server could possibly have answered.
        assertNull(container.authorization());
        assertTrue("logout call never finished", done.await(10, TimeUnit.SECONDS));
        return answer.get();
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
        if (recorded == null) throw new AssertionError("server received no request");
        return recorded;
    }

    private void assertSignedOut(AppContainer container) {
        assertNull(container.authorization());
        assertFalse(container.getSessionStore().isActive());
        assertNull(container.getSessionStore().email());
        assertEquals(List.of(SessionStore.EndReason.LOGGED_OUT), events);
    }

    @Test
    public void startSessionExposesTokenAndEmail() {
        app.startSession(TOKEN, EMAIL);

        assertEquals("Bearer " + TOKEN, app.authorization());
        assertEquals(EMAIL, app.getSessionStore().email());
    }

    @Test
    public void logoutRevokesOldTokenOnServer() throws Exception {
        app.startSession(TOKEN, EMAIL);
        server.enqueue(new MockResponse().setResponseCode(204));

        ApiResult<Void> answer = endSessionAndAwaitServer(app);

        assertTrue(answer.isSuccess());
        RecordedRequest recorded = takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/api/v1/auth/logout", recorded.getPath());
        assertEquals(List.of("Bearer " + TOKEN), recorded.getHeaders().values("Authorization"));
        assertSignedOut(app);
    }

    @Test
    public void localSignOutDoesNotWaitForSlowServer() throws Exception {
        app.startSession(TOKEN, EMAIL);
        server.enqueue(new MockResponse().setResponseCode(204).setHeadersDelay(500, TimeUnit.MILLISECONDS));

        app.endSession();

        assertSignedOut(app);
        assertEquals("/api/v1/auth/logout", takeRequest().getPath());
    }

    @Test
    public void serverErrorKeepsLocalSignOut() throws Exception {
        app.startSession(TOKEN, EMAIL);
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("{\"code\":\"INTERNAL_ERROR\",\"message\":\"x\"}"));

        ApiResult<Void> answer = endSessionAndAwaitServer(app);

        assertFalse(answer.isSuccess());
        assertEquals(500, answer.getError().getHttpStatus());
        assertSignedOut(app);
    }

    @Test
    public void droppedConnectionKeepsLocalSignOut() throws Exception {
        app.startSession(TOKEN, EMAIL);
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        ApiResult<Void> answer = endSessionAndAwaitServer(app);

        assertFalse(answer.isSuccess());
        assertEquals(ApiError.Kind.NETWORK, answer.getError().getKind());
        assertSignedOut(app);
    }

    @Test
    public void stoppedServerKeepsLocalSignOut() throws Exception {
        String baseUrl = server.url("/").toString();
        server.shutdown();
        AppContainer offline = containerFor(baseUrl);
        offline.startSession(TOKEN, EMAIL);

        ApiResult<Void> answer = endSessionAndAwaitServer(offline);

        assertFalse(answer.isSuccess());
        assertEquals(ApiError.Kind.NETWORK, answer.getError().getKind());
        assertSignedOut(offline);
    }

    @Test
    public void rejectedLogoutDoesNotEndNextAccountsSession() throws Exception {
        app.startSession("old-token", "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(401).setHeadersDelay(300, TimeUnit.MILLISECONDS)
                .setBody("{\"code\":\"INVALID_SESSION\",\"message\":\"x\"}"));
        AtomicReference<ApiResult<Void>> answer = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        app.endSession(result -> {
            answer.set(result);
            done.countDown();
        });
        app.startSession("new-token", "b@example.com");
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(401, answer.get().getError().getHttpStatus());
        assertEquals("Bearer new-token", app.authorization());
        assertEquals("b@example.com", app.getSessionStore().email());
        assertEquals(List.of(SessionStore.EndReason.LOGGED_OUT), events);
    }

    @Test
    public void endSessionWithoutSessionSendsNothing() throws Exception {
        CountDownLatch called = new CountDownLatch(1);

        app.endSession(result -> called.countDown());
        app.endSession();

        assertFalse(called.await(300, TimeUnit.MILLISECONDS));
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS));
        assertEquals(0, server.getRequestCount());
        assertTrue(events.isEmpty());
    }

    @Test
    public void secondEndSessionSendsOnlyOneLogout() throws Exception {
        app.startSession(TOKEN, EMAIL);
        server.enqueue(new MockResponse().setResponseCode(204));

        endSessionAndAwaitServer(app);
        app.endSession();

        takeRequest();
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS));
        assertSignedOut(app);
    }

    @Test
    public void clearSessionExpiresCurrentSession() {
        app.startSession(TOKEN, EMAIL);

        app.clearSession();
        app.clearSession();

        assertNull(app.authorization());
        assertEquals(List.of(SessionStore.EndReason.EXPIRED), events);
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void repositoriesSendSessionAndExpireItOn401() throws Exception {
        app.startSession(TOKEN, EMAIL);
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_SESSION\",\"message\":\"x\"}"));
        AtomicReference<ApiResult<MemoryApi.Page>> answer = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // No token passed by the caller: the container's interceptor supplies it.
        app.getMemoryRepository().list(null, 0, result -> {
            answer.set(result);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(List.of("Bearer " + TOKEN), takeRequest().getHeaders().values("Authorization"));
        assertEquals(401, answer.get().getError().getHttpStatus());
        // Already expired when the screen receives the 401.
        assertNull(app.authorization());
        assertEquals(List.of(SessionStore.EndReason.EXPIRED), events);
    }
}
