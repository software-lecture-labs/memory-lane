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
import com.memorylane.core.network.Tagged;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** The remote MemoryRepository against local HTTP, through its list, create, detail and edit interfaces. */
public class MemoryRepositoryTest {
    private static final String AUTHORIZATION = "Bearer sahte-test-tokeni";
    private static final String PAGE = "{\"content\":[{\"id\":\"m1\",\"title\":\"Bursa\","
            + "\"description\":\"Kestane şekeri\",\"memoryDate\":\"2024-10-28\",\"category\":\"TRAVEL\","
            + "\"emotion\":\"HAPPY\",\"location\":{\"latitude\":41.0082,\"longitude\":28.9784},"
            + "\"createdAt\":\"2026-10-23T09:00:00Z\",\"updatedAt\":\"2026-10-24T09:00:00Z\","
            + "\"photoCount\":2,\"coverPhotoId\":\"p1\"}],\"page\":1,\"size\":20,\"totalElements\":21,"
            + "\"totalPages\":2,\"hasNext\":false}";

    private static final String MEMORY_ID = "22222222-2222-4222-8222-222222222222";
    private static final String ETAG = "\"memory-" + MEMORY_ID + "-r7\"";
    /** MemoryDetail as in the contract; the second photo's order shows gaps are kept. */
    private static final String DETAIL = "{\"id\":\"" + MEMORY_ID + "\",\"title\":\"Bursa\","
            + "\"description\":\"Kestane şekeri\",\"memoryDate\":\"2024-10-28\",\"category\":\"TRAVEL\","
            + "\"emotion\":\"HAPPY\",\"location\":{\"latitude\":41.0082,\"longitude\":28.9784},"
            + "\"createdAt\":\"2026-10-23T09:00:00Z\",\"updatedAt\":\"2026-10-24T09:00:00Z\","
            + "\"photoCount\":2,\"coverPhotoId\":\"p1\",\"photos\":["
            + "{\"id\":\"p1\",\"memoryId\":\"" + MEMORY_ID + "\",\"order\":0,\"contentType\":\"image/jpeg\","
            + "\"sizeBytes\":245678,\"createdAt\":\"2026-10-23T09:05:00Z\","
            + "\"contentUrl\":\"/api/v1/memories/" + MEMORY_ID + "/photos/p1/content\"},"
            + "{\"id\":\"p2\",\"memoryId\":\"" + MEMORY_ID + "\",\"order\":3,\"contentType\":\"image/png\","
            + "\"sizeBytes\":1024,\"createdAt\":\"2026-10-23T09:06:00Z\","
            + "\"contentUrl\":\"/api/v1/memories/" + MEMORY_ID + "/photos/p2/content\"}]}";

    private static final String KEY = "6f1c2b9e-1d1a-4c55-9a51-2f6a1c0b7e44";
    private static final String CREATED = "{\"id\":\"m9\",\"title\":\"Bursa\",\"description\":\"\","
            + "\"memoryDate\":\"2024-10-28\",\"category\":\"OTHER\",\"emotion\":null,\"location\":null,"
            + "\"photoCount\":0,\"coverPhotoId\":null,\"photos\":[]}";

    private final Gson gson = new Gson();
    private MockWebServer server;
    private MemoryListRepository repository;
    private MemoryCreateRepository creator;
    private MemoryDetailRepository details;
    private MemoryEditRepository editor;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        MemoryApi api = ApiClient.create(server.url("/").toString(), gson).create(MemoryApi.class);
        MemoryRepository remote = new MemoryRepository(api, new ApiCaller(gson));
        repository = remote;
        creator = remote;
        details = remote;
        editor = remote;
    }

    @Test
    public void createPostsTheDraftWithSessionAndIdempotencyKey() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(CREATED));

        ApiResult<MemoryApi.Memory> result = create();

        assertTrue(result.isSuccess());
        MemoryApi.Memory created = result.getData();
        assertNotNull(created);
        assertEquals("m9", created.id());
        assertEquals("2024-10-28", created.memoryDate());
        assertEquals("OTHER", created.category());
        assertNull(created.location());
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
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<MemoryApi.Memory>> holder = new AtomicReference<>();
        creator.create(AUTHORIZATION, KEY, MemoryFixtures.draft("Bursa", "Kestane", "2024-10-28"), result -> {
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
    public void listReadsRowsWithEverySummaryFieldAndPaging() throws Exception {
        server.enqueue(new MockResponse().setBody(PAGE));

        ApiResult<MemoryApi.Page> result = await(1);

        assertTrue(result.isSuccess());
        MemoryApi.Page page = result.getData();
        assertNotNull(page);
        assertFalse(page.hasNext());
        assertEquals(1, page.content().size());
        assertEquals(new MemoryApi.Memory("m1", "Bursa", "Kestane şekeri", "2024-10-28", "TRAVEL", "HAPPY",
                        new MemoryApi.Location(41.0082, 28.9784), "2026-10-23T09:00:00Z", "2026-10-24T09:00:00Z",
                        2, "p1"),
                page.content().get(0));
    }

    @Test
    public void detailReadsTheMemoryItsPhotosAndTheEtagExactlyAsSent() throws Exception {
        server.enqueue(new MockResponse().setHeader("ETag", ETAG).setBody(DETAIL));

        ApiResult<Tagged<MemoryApi.Detail>> result = detail();

        assertTrue(result.isSuccess());
        Tagged<MemoryApi.Detail> tagged = result.getData();
        assertNotNull(tagged);
        assertEquals(ETAG, tagged.etag());
        MemoryApi.Detail detail = tagged.value();
        assertEquals(MEMORY_ID, detail.id());
        assertEquals("TRAVEL", detail.category());
        assertEquals("HAPPY", detail.emotion());
        assertEquals(new MemoryApi.Location(41.0082, 28.9784), detail.location());
        assertEquals(2, detail.photoCount());
        assertEquals("p1", detail.coverPhotoId());
        assertEquals(List.of(
                new MemoryApi.Photo("p1", MEMORY_ID, 0, "image/jpeg", 245678, "2026-10-23T09:05:00Z",
                        "/api/v1/memories/" + MEMORY_ID + "/photos/p1/content"),
                new MemoryApi.Photo("p2", MEMORY_ID, 3, "image/png", 1024, "2026-10-23T09:06:00Z",
                        "/api/v1/memories/" + MEMORY_ID + "/photos/p2/content")), detail.photos());
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("GET", sent.getMethod());
        assertEquals("/api/v1/memories/" + MEMORY_ID, sent.getPath());
        assertEquals(AUTHORIZATION, sent.getHeader("Authorization"));
    }

    @Test
    public void detailWithoutPhotosOrLocationIsReadAsEmptyAndNull() throws Exception {
        server.enqueue(new MockResponse().setHeader("ETag", ETAG).setBody("{\"id\":\"" + MEMORY_ID + "\","
                + "\"title\":\"Sade\",\"description\":\"\",\"memoryDate\":\"2025-01-01\",\"category\":\"OTHER\","
                + "\"emotion\":null,\"location\":null,\"photoCount\":0,\"coverPhotoId\":null,\"photos\":[]}"));

        MemoryApi.Detail detail = detail().getData().value();

        assertNull(detail.location());
        assertNull(detail.emotion());
        assertNull(detail.coverPhotoId());
        assertTrue(detail.photos().isEmpty());
    }

    @Test
    public void detailWithoutEtagIsAParseErrorSoNoEditStartsWithoutATag() throws Exception {
        server.enqueue(new MockResponse().setBody(DETAIL));

        ApiError error = detail().getError();

        assertNotNull(error);
        assertEquals(ApiError.Kind.PARSE, error.getKind());
    }

    @Test
    public void detailKeepsNotFoundAndExpiredSessionErrors() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"code\":\"MEMORY_NOT_FOUND\",\"message\":\"Anı bulunamadı.\"}"));
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_SESSION\",\"message\":\"Geçerli oturum gereklidir.\"}"));

        assertHttpError(404, "MEMORY_NOT_FOUND", detail().getError());
        assertHttpError(401, "INVALID_SESSION", detail().getError());
    }

    @Test
    public void updatePutsEveryWritableFieldWithIfMatchAndReadsTheCurrentDetail() throws Exception {
        server.enqueue(new MockResponse().setBody(DETAIL));

        ApiResult<MemoryApi.Detail> result = update(new MemoryApi.Draft("Bursa", "Kestane şekeri", "2024-10-28",
                "TRAVEL", "HAPPY", new MemoryApi.Location(41.0082, 28.9784)));

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(2, result.getData().photos().size());
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("PUT", sent.getMethod());
        assertEquals("/api/v1/memories/" + MEMORY_ID, sent.getPath());
        assertEquals(AUTHORIZATION, sent.getHeader("Authorization"));
        assertEquals(ETAG, sent.getHeader("If-Match"));
        JsonObject body = gson.fromJson(sent.getBody().readUtf8(), JsonObject.class);
        assertEquals(6, body.size());
        assertEquals("TRAVEL", body.get("category").getAsString());
        assertEquals("HAPPY", body.get("emotion").getAsString());
        assertEquals(41.0082, body.getAsJsonObject("location").get("latitude").getAsDouble(), 0);
        assertEquals(28.9784, body.getAsJsonObject("location").get("longitude").getAsDouble(), 0);
    }

    @Test
    public void updateLeavesClearedOptionalFieldsOutOfTheBody() throws Exception {
        server.enqueue(new MockResponse().setBody(DETAIL));

        update(new MemoryApi.Draft("Sade", "", "2025-01-01", "OTHER", null, null));

        JsonObject body = gson.fromJson(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8(), JsonObject.class);
        assertEquals(4, body.size());
        assertFalse(body.has("emotion"));
        assertFalse(body.has("location"));
    }

    @Test
    public void updateKeepsConflictPreconditionFieldAndOwnershipErrorsApart() throws Exception {
        String[][] answers = {
                {"412", "MEMORY_MODIFIED"}, {"428", "PRECONDITION_REQUIRED"}, {"400", "INVALID_PRECONDITION"},
                {"400", "INVALID_INPUT"}, {"404", "MEMORY_NOT_FOUND"}, {"401", "INVALID_SESSION"}};
        for (String[] answer : answers) {
            server.enqueue(new MockResponse().setResponseCode(Integer.parseInt(answer[0]))
                    .setBody("{\"code\":\"" + answer[1] + "\",\"message\":\"Sunucu mesajı\"}"));
        }
        for (String[] answer : answers) {
            ApiError error = update(MemoryFixtures.draft("Bursa", "", "2024-10-28")).getError();
            assertHttpError(Integer.parseInt(answer[0]), answer[1], error);
            assertEquals("Sunucu mesajı", error.getMessage());
        }
    }

    private ApiResult<Tagged<MemoryApi.Detail>> detail() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<Tagged<MemoryApi.Detail>>> holder = new AtomicReference<>();
        details.getMemoryDetail(AUTHORIZATION, MEMORY_ID, result -> {
            holder.set(result);
            done.countDown();
        });
        assertTrue("request did not finish", done.await(20, TimeUnit.SECONDS));
        return holder.get();
    }

    private ApiResult<MemoryApi.Detail> update(MemoryApi.Draft draft) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<MemoryApi.Detail>> holder = new AtomicReference<>();
        editor.updateMemory(AUTHORIZATION, MEMORY_ID, ETAG, draft, result -> {
            holder.set(result);
            done.countDown();
        });
        assertTrue("request did not finish", done.await(20, TimeUnit.SECONDS));
        return holder.get();
    }

    private static void assertHttpError(int status, String code, ApiError error) {
        assertNotNull(error);
        assertEquals(ApiError.Kind.HTTP, error.getKind());
        assertEquals(status, error.getHttpStatus());
        assertEquals(code, error.getCode());
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
