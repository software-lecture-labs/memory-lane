package com.memorylane.feature.memories.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
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

/** The remote MemoryRepository against local HTTP, through the MemoryListRepository interface. */
public class MemoryRepositoryTest {
    private static final String AUTHORIZATION = "Bearer sahte-test-tokeni";
    private static final String PAGE = "{\"content\":[{\"id\":\"m1\",\"title\":\"Bursa\","
            + "\"description\":\"Kestane şekeri\",\"memoryDate\":\"2024-10-28\",\"category\":\"TRAVEL\","
            + "\"photoCount\":2}],\"page\":1,\"size\":20,\"totalElements\":21,\"totalPages\":2,"
            + "\"hasNext\":false}";

    private final Gson gson = new Gson();
    private MockWebServer server;
    private MemoryListRepository repository;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        MemoryApi api = ApiClient.create(server.url("/").toString(), gson).create(MemoryApi.class);
        repository = new MemoryRepository(api, new ApiCaller(gson));
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
