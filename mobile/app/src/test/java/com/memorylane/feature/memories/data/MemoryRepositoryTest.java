package com.memorylane.feature.memories.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.ApiClient;
import com.memorylane.core.network.ApiError;
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

/** The remote MemoryRepository against local HTTP, through its list and create interfaces. */
public class MemoryRepositoryTest {
    private static final String AUTHORIZATION = "Bearer sahte-test-tokeni";
    private static final String PAGE = "{\"content\":[{\"id\":\"m1\",\"title\":\"Bursa\","
            + "\"description\":\"Kestane şekeri\",\"memoryDate\":\"2024-10-28\",\"category\":\"TRAVEL\","
            + "\"photoCount\":2}],\"page\":1,\"size\":20,\"totalElements\":21,\"totalPages\":2,"
            + "\"hasNext\":false}";

    private static final String KEY = "6f1c2b9e-1d1a-4c55-9a51-2f6a1c0b7e44";
    private static final String CREATED = "{\"id\":\"m9\",\"title\":\"Bursa\",\"description\":\"\","
            + "\"memoryDate\":\"2024-10-28\",\"category\":\"OTHER\",\"emotion\":null,\"location\":null,"
            + "\"photoCount\":0,\"coverPhotoId\":null,\"photos\":[]}";

    private final Gson gson = new Gson();
    private MockWebServer server;
    private MemoryListRepository repository;
    private MemoryCreateRepository creator;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        MemoryApi api = ApiClient.create(server.url("/").toString(), gson).create(MemoryApi.class);
        MemoryRepository remote = new MemoryRepository(api, new ApiCaller(gson));
        repository = remote;
        creator = remote;
    }

    @Test
    public void createPostsTheDraftWithSessionAndIdempotencyKey() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(CREATED));

        ApiResult<MemoryApi.Memory> result = create();

        assertTrue(result.isSuccess());
        assertEquals(new MemoryApi.Memory("m9", "Bursa", "", "2024-10-28"), result.getData());
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("POST", sent.getMethod());
        assertEquals("/api/v1/memories", sent.getPath());
        assertEquals(AUTHORIZATION, sent.getHeader("Authorization"));
        assertEquals(KEY, sent.getHeader("Idempotency-Key"));
        JsonObject body = gson.fromJson(sent.getBody().readUtf8(), JsonObject.class);
        assertEquals(3, body.size());
        assertEquals("Bursa", body.get("title").getAsString());
        assertEquals("Kestane", body.get("description").getAsString());
        assertEquals("2024-10-28", body.get("memoryDate").getAsString());
    }

    @Test
    public void createSendsCategoryEmotionAndLocationWhenGiven() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(CREATED));

        create(new MemoryApi.Draft("Bursa", "", "2024-10-28", "TRAVEL", "NOSTALGIC",
                new MemoryApi.Location(40.1885, 29.061)));

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        JsonObject body = gson.fromJson(sent.getBody().readUtf8(), JsonObject.class);
        assertEquals(6, body.size());
        assertEquals("TRAVEL", body.get("category").getAsString());
        assertEquals("NOSTALGIC", body.get("emotion").getAsString());
        JsonObject location = body.getAsJsonObject("location");
        assertEquals(2, location.size());
        assertEquals(40.1885, location.get("latitude").getAsDouble(), 0);
        assertEquals(29.061, location.get("longitude").getAsDouble(), 0);
    }

    @Test
    public void createLeavesOutEmptyOptionalFields() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(CREATED));

        create(new MemoryApi.Draft("Bursa", "", "2024-10-28", "OTHER", null, null));

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        JsonObject body = gson.fromJson(sent.getBody().readUtf8(), JsonObject.class);
        assertEquals(4, body.size());
        assertEquals("OTHER", body.get("category").getAsString());
        assertFalse(body.has("emotion"));
        assertFalse(body.has("location"));
    }

    @Test
    public void createKeepsServerValidationErrors() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"code\":\"INVALID_INPUT\",\"message\":\"Anı tarihi gelecekte olamaz.\"}"));

        ApiError error = create().getError();

        assertNotNull(error);
        assertEquals(400, error.getHttpStatus());
        assertEquals("INVALID_INPUT", error.getCode());
        assertEquals("Anı tarihi gelecekte olamaz.", error.getMessage());
    }

    private ApiResult<MemoryApi.Memory> create() throws InterruptedException {
        return create(new MemoryApi.Draft("Bursa", "Kestane", "2024-10-28"));
    }

    private ApiResult<MemoryApi.Memory> create(MemoryApi.Draft draft) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<MemoryApi.Memory>> holder = new AtomicReference<>();
        creator.create(AUTHORIZATION, KEY, draft, result -> {
            holder.set(result);
            done.countDown();
        });
        assertTrue("request did not finish", done.await(20, TimeUnit.SECONDS));
        return holder.get();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void listRequestsThePageWithTheSessionHeader() throws Exception {
        server.enqueue(new MockResponse().setBody(PAGE));

        await(1);

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("GET", sent.getMethod());
        assertEquals("/api/v1/memories?page=1", sent.getPath());
        assertEquals(AUTHORIZATION, sent.getHeader("Authorization"));
    }

    @Test
    public void listReadsRowsAndPagingAndIgnoresFieldsTheListDoesNotUse() throws Exception {
        server.enqueue(new MockResponse().setBody(PAGE));

        ApiResult<MemoryApi.Page> result = await(1);

        assertTrue(result.isSuccess());
        MemoryApi.Page page = result.getData();
        assertNotNull(page);
        assertFalse(page.hasNext());
        assertEquals(1, page.content().size());
        assertEquals(new MemoryApi.Memory("m1", "Bursa", "Kestane şekeri", "2024-10-28"),
                page.content().get(0));
    }

    @Test
    public void expiredSessionKeepsTheContractError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_SESSION\",\"message\":\"Geçerli oturum gereklidir.\"}"));

        ApiError error = await(0).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.HTTP, error.getKind());
        assertEquals(401, error.getHttpStatus());
        assertEquals("INVALID_SESSION", error.getCode());
    }

    @Test
    public void malformedBodyIsAParseErrorNotAnEmptyList() throws Exception {
        server.enqueue(new MockResponse().setBody("<html>not json</html>"));

        ApiResult<MemoryApi.Page> result = await(0);

        assertFalse(result.isSuccess());
        assertNull(result.getData());
        assertNotNull(result.getError());
        assertEquals(ApiError.Kind.PARSE, result.getError().getKind());
    }

    @Test
    public void unreachableServerIsANetworkError() throws Exception {
        server.shutdown();

        ApiError error = await(0).getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.NETWORK, error.getKind());
    }

    @Test
    public void cancelledListDoesNotReportBack() throws Exception {
        server.enqueue(new MockResponse().setBody(PAGE).setBodyDelay(500, TimeUnit.MILLISECONDS));
        CountDownLatch called = new CountDownLatch(1);

        repository.list(AUTHORIZATION, 0, result -> called.countDown()).cancel();

        assertFalse(called.await(1, TimeUnit.SECONDS));
    }

    private ApiResult<MemoryApi.Page> await(int page) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<MemoryApi.Page>> holder = new AtomicReference<>();
        repository.list(AUTHORIZATION, page, result -> {
            holder.set(result);
            done.countDown();
        });
        assertTrue("request did not finish", done.await(20, TimeUnit.SECONDS));
        return holder.get();
    }
}
