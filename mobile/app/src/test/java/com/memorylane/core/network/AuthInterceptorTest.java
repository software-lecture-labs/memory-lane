package com.memorylane.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.memorylane.core.session.SessionStore;
import com.memorylane.feature.auth.data.AuthApi;
import com.memorylane.feature.auth.data.AuthRequest;
import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import retrofit2.Retrofit;

/** Real HTTP round trips against MockWebServer; checks what the server actually receives. */
public class AuthInterceptorTest {
    private static final String TOKEN = "tok-abc";
    private static final MediaType JSON = MediaType.get("application/json");

    private MockWebServer server;
    private SessionStore store;
    private final List<SessionStore.EndReason> events = new CopyOnWriteArrayList<>();
    private OkHttpClient client;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        store = new SessionStore(Runnable::run);
        store.addListener(events::add);
        client = clientFor(server.url("/").toString());
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private OkHttpClient clientFor(String baseUrl) {
        return new OkHttpClient.Builder()
                .addInterceptor(new AuthInterceptor(store, baseUrl))
                .build();
    }

    private int get(String path) throws IOException {
        return execute(new Request.Builder().url(server.url(path)).build());
    }

    private int post(String path) throws IOException {
        return execute(new Request.Builder().url(server.url(path))
                .post(RequestBody.create("{}", JSON)).build());
    }

    private int execute(Request request) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            return response.code();
        }
    }

    private String sentAuthorization() throws InterruptedException {
        RecordedRequest recorded = server.takeRequest(5, TimeUnit.SECONDS);
        if (recorded == null) throw new AssertionError("server received no request");
        List<String> values = recorded.getHeaders().values("Authorization");
        assertTrue("Authorization sent more than once: " + values, values.size() <= 1);
        return values.isEmpty() ? null : values.get(0);
    }

    // --- Adding the header ---

    @Test
    public void protectedRequestGetsCurrentBearerToken() throws Exception {
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(200));

        get("/api/v1/memories?page=0");

        assertEquals("Bearer " + TOKEN, sentAuthorization());
    }

    @Test
    public void meAndLogoutAreProtected() throws Exception {
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(204));

        get("/api/v1/auth/me");
        post("/api/v1/auth/logout");

        assertEquals("Bearer " + TOKEN, sentAuthorization());
        assertEquals("Bearer " + TOKEN, sentAuthorization());
    }

    @Test
    public void signedOutProtectedRequestIsSentWithoutHeader() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));

        get("/api/v1/memories");

        assertNull(sentAuthorization());
    }

    @Test
    public void loginRegisterAndHealthNeverGetToken() throws Exception {
        store.start(TOKEN, "a@example.com");
        for (int i = 0; i < 4; i++) server.enqueue(new MockResponse().setResponseCode(200));

        post("/api/v1/auth/login");
        post("/api/v1/auth/register");
        post("/api/v1/auth/login/");
        get("/api/health");

        for (int i = 0; i < 4; i++) assertNull(sentAuthorization());
    }

    @Test
    public void onlyExactApiV1PathsAreProtected() throws Exception {
        store.start(TOKEN, "a@example.com");
        String[] unprotected = {"/api/v10/memories", "/apiv1/memories", "/api", "/other/api/v1/memories"};
        String[] protectedPaths = {"/api/v1", "/api/v1/auth/login/extra", "/api/v1/auth/loginx"};
        for (int i = 0; i < unprotected.length + protectedPaths.length; i++) {
            server.enqueue(new MockResponse().setResponseCode(200));
        }

        for (String path : unprotected) get(path);
        for (String path : protectedPaths) get(path);

        for (String path : unprotected) assertNull(path, sentAuthorization());
        for (String path : protectedPaths) assertEquals(path, "Bearer " + TOKEN, sentAuthorization());
    }

    @Test
    public void existingHeaderIsKeptNotDuplicated() throws Exception {
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(200));

        execute(new Request.Builder().url(server.url("/api/v1/memories"))
                .header("Authorization", "Bearer explicit").build());

        assertEquals("Bearer explicit", sentAuthorization());
    }

    @Test
    public void tokenIsNotSentToAnotherHostOrPort() throws Exception {
        store.start(TOKEN, "a@example.com");
        try (MockWebServer other = new MockWebServer()) {
            other.start();
            other.enqueue(new MockResponse().setResponseCode(401));

            execute(new Request.Builder().url(other.url("/api/v1/memories")).build());

            RecordedRequest recorded = other.takeRequest(5, TimeUnit.SECONDS);
            assertNull(recorded.getHeader("Authorization"));
        }
        // A 401 from a foreign server says nothing about our session.
        assertTrue(store.isActive());
    }

    @Test
    public void baseUrlPathPrefixIsRespected() throws Exception {
        client = clientFor(server.url("/prefix").toString());
        store.start(TOKEN, "a@example.com");
        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(200));

        get("/prefix/api/v1/memories");
        get("/prefix/api/v1/auth/login");
        get("/api/v1/memories");

        assertEquals("Bearer " + TOKEN, sentAuthorization());
        assertNull(sentAuthorization());
        assertNull(sentAuthorization());
    }

    // --- Reacting to 401 ---

    @Test
    public void unauthorizedProtectedResponseExpiresSession() throws Exception {
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_SESSION\",\"message\":\"x\"}"));

        assertEquals(401, get("/api/v1/memories"));

        assertFalse(store.isActive());
        assertEquals(List.of(SessionStore.EndReason.EXPIRED), events);
    }

    @Test
    public void loginRejectionKeepsSession() throws Exception {
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"x\"}"));

        assertEquals(401, post("/api/v1/auth/login"));

        assertTrue(store.isActive());
        assertTrue(events.isEmpty());
    }

    @Test
    public void unauthorizedOutsideApiV1KeepsSession() throws Exception {
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(401));

        get("/api/health");

        assertTrue(store.isActive());
        assertTrue(events.isEmpty());
    }

    @Test
    public void lateRejectionOfOldExplicitTokenKeepsNewSession() throws Exception {
        store.start("new-token", "b@example.com");
        server.enqueue(new MockResponse().setResponseCode(401));

        execute(new Request.Builder().url(server.url("/api/v1/memories"))
                .header("Authorization", "Bearer old-token").build());

        assertEquals("Bearer new-token", store.authorization());
        assertTrue(events.isEmpty());
    }

    @Test
    public void rejectionOfCurrentExplicitTokenExpiresSession() throws Exception {
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(401));

        // Scheme is case-insensitive in HTTP.
        execute(new Request.Builder().url(server.url("/api/v1/memories"))
                .header("Authorization", "bearer " + TOKEN).build());

        assertFalse(store.isActive());
        assertEquals(List.of(SessionStore.EndReason.EXPIRED), events);
    }

    @Test
    public void nonBearerExplicitHeaderNeverExpiresSession() throws Exception {
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(401));

        execute(new Request.Builder().url(server.url("/api/v1/memories"))
                .header("Authorization", "Basic " + TOKEN).build());

        assertTrue(store.isActive());
    }

    @Test
    public void tokenReplacedDuringRequestIsNotExpiredByItsOldRejection() throws Exception {
        store.start("old-token", "a@example.com");
        // The account switches while the old request is in flight.
        server.enqueue(new MockResponse().setResponseCode(401).setHeadersDelay(300, TimeUnit.MILLISECONDS));
        Thread switcher = new Thread(() -> {
            try {
                server.takeRequest(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            store.start("new-token", "b@example.com");
        });
        switcher.start();

        get("/api/v1/memories");
        switcher.join(5000);

        assertEquals("Bearer new-token", store.authorization());
        assertTrue(events.isEmpty());
    }

    @Test
    public void signedOutUnauthorizedSendsNoEvent() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401));

        get("/api/v1/memories");

        assertTrue(events.isEmpty());
    }

    @Test
    public void otherStatusesKeepSession() throws Exception {
        store.start(TOKEN, "a@example.com");
        int[] codes = {200, 204, 400, 403, 404, 500, 503};
        for (int code : codes) server.enqueue(new MockResponse().setResponseCode(code));

        for (int code : codes) assertEquals(code, get("/api/v1/memories"));

        assertTrue(store.isActive());
        assertTrue(events.isEmpty());
    }

    @Test
    public void networkFailurePropagatesAndKeepsSession() {
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        assertThrows(IOException.class, () -> get("/api/v1/memories"));

        assertTrue(store.isActive());
    }

    // --- Wiring through ApiClient and Retrofit ---

    @Test
    public void apiClientAppliesInterceptorToRetrofitEndpoints() throws Exception {
        String baseUrl = server.url("/").toString();
        Retrofit retrofit = ApiClient.create(baseUrl, new Gson(), new AuthInterceptor(store, baseUrl));
        store.start(TOKEN, "a@example.com");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"content\":[],\"hasNext\":false}"));
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"x\"}"));
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_SESSION\",\"message\":\"x\"}"));

        // A null @Header value is omitted by Retrofit, so the interceptor supplies the session.
        retrofit.create(MemoryApi.class).list(null, 0).execute();
        retrofit.create(AuthApi.class).login(new AuthRequest("a@example.com", "pw")).execute();
        assertTrue(store.isActive());
        retrofit.create(MemoryApi.class).list(null, 0).execute();

        assertEquals("Bearer " + TOKEN, sentAuthorization());
        assertNull(sentAuthorization());
        assertEquals("Bearer " + TOKEN, sentAuthorization());
        assertFalse(store.isActive());
        assertEquals(List.of(SessionStore.EndReason.EXPIRED), events);
    }

    @Test
    public void apiClientWithoutInterceptorsSendsNoToken() throws Exception {
        store.start(TOKEN, "a@example.com");
        Retrofit retrofit = ApiClient.create(server.url("/").toString(), new Gson());
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"content\":[],\"hasNext\":false}"));

        retrofit.create(MemoryApi.class).list(null, 0).execute();

        assertNull(sentAuthorization());
    }
}
