package com.memorylane.feature.memories.data;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.ApiClient;
import com.memorylane.core.network.ApiResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Pins the create body the server's MemoryInput reads; local HTTP only, synthetic data. */
public class RemoteMemoryRepositoryTest {
    private static final String KEY = "22222222-2222-4222-8222-222222222222";
    private static final String CREATED = "{\"id\":\"11111111-1111-4111-8111-111111111111\","
            + "\"title\":\"Sahil\",\"description\":\"\",\"memoryDate\":\"2026-01-01\"}";
    private final Gson gson = new Gson();
    private MockWebServer server;
    private MemoryRepository repository;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        MemoryApi api = ApiClient.create(server.url("/").toString(), gson).create(MemoryApi.class);
        repository = new RemoteMemoryRepository(api, new ApiCaller(gson));
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void createOmitsEmptyOptionalFields() throws Exception {
        JsonObject body = send(new MemoryApi.Draft("Sahil", "", "2026-01-01", "OTHER", null, null));

        assertEquals(4, body.size());
        assertEquals("Sahil", body.get("title").getAsString());
        assertEquals("", body.get("description").getAsString());
        assertEquals("2026-01-01", body.get("memoryDate").getAsString());
        assertEquals("OTHER", body.get("category").getAsString());
    }

    @Test
    public void createSendsEmotionAndLocationPair() throws Exception {
        JsonObject body = send(new MemoryApi.Draft("Sahil", "Akşam", "2026-01-01", "TRAVEL", "HAPPY",
                new MemoryApi.Location(41.0082, 28.9784)));

        assertEquals("TRAVEL", body.get("category").getAsString());
        assertEquals("HAPPY", body.get("emotion").getAsString());
        JsonObject location = body.getAsJsonObject("location");
        assertEquals(2, location.size());
        assertEquals(41.0082, location.get("latitude").getAsDouble(), 0);
        assertEquals(28.9784, location.get("longitude").getAsDouble(), 0);
    }

    private JsonObject send(MemoryApi.Draft draft) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(CREATED));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<MemoryApi.Memory>> result = new AtomicReference<>();
        repository.save("Bearer test", KEY, draft, value -> {
            result.set(value);
            done.countDown();
        });
        assertTrue("request did not finish", done.await(20, TimeUnit.SECONDS));
        assertTrue(result.get().isSuccess());

        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("/api/v1/memories", request.getPath());
        assertEquals(KEY, request.getHeader("Idempotency-Key"));
        return gson.fromJson(request.getBody().readUtf8(), JsonObject.class);
    }
}
