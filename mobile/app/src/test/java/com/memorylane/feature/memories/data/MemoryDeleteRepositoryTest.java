package com.memorylane.feature.memories.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

/** The remote delete (ML-13) against local HTTP: method, path, session and the bodiless 204. */
public class MemoryDeleteRepositoryTest {
    private static final String AUTHORIZATION = "Bearer sahte-test-tokeni";
    private static final String MEMORY_ID = "22222222-2222-4222-8222-222222222222";

    private MockWebServer server;
    private MemoryDeleteRepository repository;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        Gson gson = new Gson();
        repository = new MemoryRepository(
                ApiClient.create(server.url("/").toString(), gson).create(MemoryApi.class), new ApiCaller(gson));
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void deleteSendsTheSessionAndA204WithoutBodyIsSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        ApiResult<Void> result = delete();

        assertTrue(result.isSuccess());
        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("DELETE", sent.getMethod());
        assertEquals("/api/v1/memories/" + MEMORY_ID, sent.getPath());
        assertEquals(AUTHORIZATION, sent.getHeader("Authorization"));
    }

    @Test
    public void missingOrForeignMemoryIsA404WithItsCode() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"code\":\"MEMORY_NOT_FOUND\",\"message\":\"Anı bulunamadı.\"}"));

        ApiResult<Void> result = delete();

        assertFalse(result.isSuccess());
        assertEquals(404, result.getError().getHttpStatus());
        assertEquals("MEMORY_NOT_FOUND", result.getError().getCode());
    }

    private ApiResult<Void> delete() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<Void>> result = new AtomicReference<>();
        repository.deleteMemory(AUTHORIZATION, MEMORY_ID, answer -> {
            result.set(answer);
            done.countDown();
        });
        assertTrue("no answer in time", done.await(5, TimeUnit.SECONDS));
        return result.get();
    }
}
