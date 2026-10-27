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
    public void unreadableSuccessBodyIsParseErrorNotNetwork() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("<html>proxy</html>"));

        ApiError error = callHealth(server.url("/").toString()).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.PARSE, error.getKind());
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
