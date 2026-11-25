package com.memorylane.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.memorylane.feature.health.data.HealthApi;
import com.memorylane.feature.health.data.HealthResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import retrofit2.Call;
import retrofit2.http.GET;

/** Real HTTP round trips against a local MockWebServer; no Android device needed. */
public class ApiCallerTest {
    private MockWebServer server;
    private final Gson gson = new Gson();

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void successfulHealthResponseIsParsed() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"UP\",\"database\":\"UP\"}"));

        ApiResult<HealthResponse> result = callHealth(server.url("/").toString());

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals("UP", result.getData().getDatabase());
        assertEquals("/api/health", server.takeRequest().getPath());
    }

    @Test
    public void healthDownIsHttpErrorWithStatus() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"status\":\"DOWN\",\"database\":\"DOWN\"}"));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.HTTP, error.getKind());
        assertEquals(503, error.getHttpStatus());
        assertNull(error.getCode());
    }

    @Test
    public void contractErrorBodyIsRead() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_SESSION\",\"message\":\"Oturum süresi doldu.\"}"));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals("INVALID_SESSION", error.getCode());
        assertEquals("Oturum süresi doldu.", error.getMessage());
    }

    @Test
    public void rateLimitKeepsTheRetryAfterSeconds() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "17")
                .setBody("{\"code\":\"RATE_LIMITED\",\"message\":\"Biraz bekleyin.\"}"));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals("RATE_LIMITED", error.getCode());
        assertEquals(17, error.getRetryAfterSeconds());
    }

    @Test
    public void missingOrUnusableRetryAfterIsReportedAsMissing() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"code\":\"RATE_LIMITED\"}"));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals(ApiError.NO_RETRY_AFTER, error.getRetryAfterSeconds());
        assertEquals(ApiError.NO_RETRY_AFTER, ApiCaller.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertEquals(ApiError.NO_RETRY_AFTER, ApiCaller.retryAfterSeconds("-5"));
        assertEquals(0, ApiCaller.retryAfterSeconds(" 0 "));
    }

    @Test
    public void unreadableSuccessBodyIsParseErrorNotNetwork() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>proxy</html>"));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.PARSE, error.getKind());
    }

    @Test
    public void emptySuccessBodyIsParseError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(""));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.PARSE, error.getKind());
    }

    @Test
    public void incompleteJsonInCompleteResponseIsParseError() throws Exception {
        // The server finished the response normally; the JSON itself is cut off.
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":"));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.PARSE, error.getKind());
    }

    @Test
    public void connectionClosedBeforeResponseIsNetworkError() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.NETWORK, error.getKind());
    }

    @Test
    public void connectionDroppedDuringBodyIsNetworkError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"status\":\"UP\",\"database\":\"UP\"}")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.NETWORK, error.getKind());
    }

    @Test
    public void connectionDroppedDuringChunkedBodyIsNetworkError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setChunkedBody("{\"status\":\"UP\",\"database\":\"UP\"}", 4)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.NETWORK, error.getKind());
    }

    @Test
    public void stoppedServerIsNetworkError() throws Exception {
        String url = server.url("/").toString();
        server.shutdown();

        ApiError error = callHealth(url).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.NETWORK, error.getKind());
    }

    @Test
    public void cancelledCallDoesNotReportBack() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"UP\",\"database\":\"UP\"}")
                .setBodyDelay(500, TimeUnit.MILLISECONDS));
        CountDownLatch called = new CountDownLatch(1);

        Cancellable call = new ApiCaller(gson).enqueue(healthApi(server.url("/").toString()).getHealth(),
                result -> called.countDown());
        call.cancel();

        assertFalse(called.await(1, TimeUnit.SECONDS));
    }

    /** Any JSON endpoint will do for the ETag rules; the memory detail is the real user. */
    interface TaggedApi {
        @GET("api/v1/thing")
        Call<HealthResponse> get();
    }

    @Test
    public void taggedSuccessKeepsTheBodyAndTheEtagExactlyAsSent() throws Exception {
        server.enqueue(new MockResponse().setHeader("ETag", "\"memory-x-r7\"")
                .setBody("{\"status\":\"UP\",\"database\":\"UP\"}"));

        ApiResult<Tagged<HealthResponse>> result = callTagged();

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals("\"memory-x-r7\"", result.getData().etag());
        assertEquals("UP", result.getData().value().getDatabase());
    }

    @Test
    public void taggedSuccessWithoutEtagIsAParseError() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"status\":\"UP\",\"database\":\"UP\"}"));
        server.enqueue(new MockResponse().setHeader("ETag", "  ").setBody("{\"status\":\"UP\",\"database\":\"UP\"}"));

        for (int i = 0; i < 2; i++) {
            ApiError error = callTagged().getError();
            assertNotNull(error);
            assertEquals(ApiError.Kind.PARSE, error.getKind());
        }
    }

    @Test
    public void taggedSuccessWithoutBodyIsAParseError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204).setHeader("ETag", "\"memory-x-r7\""));

        ApiError error = callTagged().getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.PARSE, error.getKind());
    }

    @Test
    public void taggedErrorsAreClassifiedLikeAnyOtherCall() throws Exception {
        int[] statuses = {400, 401, 404, 412, 428};
        String[] codes = {"INVALID_INPUT", "INVALID_SESSION", "MEMORY_NOT_FOUND", "MEMORY_MODIFIED", "PRECONDITION_REQUIRED"};
        for (int i = 0; i < statuses.length; i++) {
            // An ETag on an error answer must not turn it into a success.
            server.enqueue(new MockResponse().setResponseCode(statuses[i]).setHeader("ETag", "\"memory-x-r7\"")
                    .setBody("{\"code\":\"" + codes[i] + "\",\"message\":\"m\"}"));
        }
        for (int i = 0; i < statuses.length; i++) {
            ApiError error = callTagged().getError();
            assertNotNull(error);
            assertEquals(ApiError.Kind.HTTP, error.getKind());
            assertEquals(statuses[i], error.getHttpStatus());
            assertEquals(codes[i], error.getCode());
        }
    }

    private ApiResult<Tagged<HealthResponse>> callTagged() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<Tagged<HealthResponse>>> holder = new AtomicReference<>();
        TaggedApi api = ApiClient.create(server.url("/").toString(), gson).create(TaggedApi.class);
        new ApiCaller(gson).enqueueTagged(api.get(), result -> {
            holder.set(result);
            done.countDown();
        });
        assertTrue("request did not finish", done.await(20, TimeUnit.SECONDS));
        return holder.get();
    }

    private ApiResult<HealthResponse> callHealth(String baseUrl) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<HealthResponse>> holder = new AtomicReference<>();
        new ApiCaller(gson).enqueue(healthApi(baseUrl).getHealth(), result -> {
            holder.set(result);
            done.countDown();
        });
        assertTrue("request did not finish", done.await(20, TimeUnit.SECONDS));
        return holder.get();
    }

    private HealthApi healthApi(String baseUrl) {
        return ApiClient.create(baseUrl, gson).create(HealthApi.class);
    }
}
