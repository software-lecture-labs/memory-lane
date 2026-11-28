package com.memorylane.feature.map.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
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

/** RemoteMapRepository against local HTTP, with the contract's example answer. */
public class RemoteMapRepositoryTest {
    private static final String AUTHORIZATION = "Bearer sahte-test-tokeni";
    private static final String POINTS = "{\"points\":[{\"id\":\"22222222-2222-4222-8222-222222222222\","
            + "\"title\":\"Sahil yürüyüşü\",\"memoryDate\":\"2026-10-22\",\"latitude\":41.0082,"
            + "\"longitude\":28.9784,\"coverPhotoId\":null}],\"limit\":1000,\"returned\":1,\"truncated\":false}";

    private MockWebServer server;
    private MapRepository repository;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        Gson gson = new Gson();
        repository = new RemoteMapRepository(
                ApiClient.create(server.url("/").toString(), gson).create(MapApi.class), new ApiCaller(gson));
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void readsThePointsWithTheSession() throws Exception {
        server.enqueue(new MockResponse().setBody(POINTS));

        ApiResult<MapApi.Points> result = load();

        assertTrue(result.isSuccess());
        MapApi.Points points = result.getData();
        assertNotNull(points);
        assertEquals(1, points.points().size());
        MapApi.Point point = points.points().get(0);
        assertEquals("Sahil yürüyüşü", point.title());
        assertEquals("2026-10-22", point.memoryDate());
        assertEquals(41.0082, point.latitude(), 0);
        assertEquals(28.9784, point.longitude(), 0);
        assertNull(point.coverPhotoId());
        assertEquals(1000, points.limit());
        assertFalse(points.truncated());
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("GET", sent.getMethod());
        assertEquals("/api/v1/memories/map", sent.getPath());
        assertEquals(AUTHORIZATION, sent.getHeader("Authorization"));
    }

    @Test
    public void rejectedSessionIsAnErrorWithItsCode() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_SESSION\",\"message\":\"Oturum geçersiz.\"}"));

        ApiResult<MapApi.Points> result = load();

        assertFalse(result.isSuccess());
        assertEquals(401, result.getError().getHttpStatus());
        assertEquals("INVALID_SESSION", result.getError().getCode());
    }

    private ApiResult<MapApi.Points> load() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<MapApi.Points>> result = new AtomicReference<>();
        repository.points(AUTHORIZATION, answer -> {
            result.set(answer);
            done.countDown();
        });
        assertTrue("no answer in time", done.await(5, TimeUnit.SECONDS));
        return result.get();
    }
}
