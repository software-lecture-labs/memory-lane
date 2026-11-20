package com.memorylane.feature.photos.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.ApiClient;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/** Real multipart requests against MockWebServer; the ML-09 endpoint's shape from the client side. */
public class RemotePhotoUploadsTest {
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
    private static final String CREATED = "{\"id\":\"p1\",\"memoryId\":\"m1\",\"order\":0,\"contentType\":\"image/jpeg\","
            + "\"sizeBytes\":8,\"contentUrl\":\"/api/v1/memories/m1/photos/p1/content\"}";

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private MockWebServer server;
    private RemotePhotoUploads uploads;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Gson gson = new Gson();
        uploads = new RemotePhotoUploads(ApiClient.create(server.url("/").toString(), gson).create(PhotoApi.class),
                new ApiCaller(gson));
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void sendsOneFilePartWithSessionAndKey() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody(CREATED));
        PreparedPhoto photo = prepared("key-1");

        ApiResult<PhotoApi.Photo> result = upload(photo);

        assertTrue(result.isSuccess());
        assertEquals("p1", result.getData().id());
        assertEquals(0, result.getData().order());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/v1/memories/m1/photos", request.getPath());
        assertEquals("Bearer token", request.getHeader("Authorization"));
        assertEquals("key-1", request.getHeader("Idempotency-Key"));
        assertTrue(request.getHeader("Content-Type").startsWith("multipart/form-data"));
        String body = request.getBody().readString(StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"file\""));
        assertTrue(body.contains("Content-Type: image/jpeg"));
        assertTrue(body.contains(new String(JPEG, StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void retrySendsTheSameKeyAndTheSameBytes() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(200).setBody(CREATED));
        PreparedPhoto photo = prepared("key-2");

        assertFalse(upload(photo).isSuccess());
        assertTrue(upload(photo).isSuccess());

        RecordedRequest first = server.takeRequest();
        RecordedRequest second = server.takeRequest();
        assertEquals(first.getHeader("Idempotency-Key"), second.getHeader("Idempotency-Key"));
        assertArrayEquals(fileBytes(first), fileBytes(second));
    }

    @Test
    public void contractErrorsReachTheCaller() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(409)
                .setBody("{\"code\":\"PHOTO_LIMIT_REACHED\",\"message\":\"Bir anıya en fazla 5 fotoğraf eklenebilir.\"}"));

        ApiError error = upload(prepared("key-3")).getError();

        assertNotNull(error);
        assertEquals(409, error.getHttpStatus());
        assertEquals("PHOTO_LIMIT_REACHED", error.getCode());
    }

    private PreparedPhoto prepared(String key) throws Exception {
        File file = folder.newFile();
        Files.write(file.toPath(), JPEG);
        return new PreparedPhoto(key, file, "image/jpeg");
    }

    private ApiResult<PhotoApi.Photo> upload(PreparedPhoto photo) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ApiResult<PhotoApi.Photo>> holder = new AtomicReference<>();
        uploads.upload("Bearer token", "m1", photo, result -> {
            holder.set(result);
            done.countDown();
        });
        assertTrue("request did not finish", done.await(20, TimeUnit.SECONDS));
        return holder.get();
    }

    // The part after the headers; multipart boundaries differ per request, the file bytes must not.
    private static byte[] fileBytes(RecordedRequest request) {
        String body = request.getBody().readString(StandardCharsets.ISO_8859_1);
        int start = body.indexOf("\r\n\r\n") + 4;
        int end = body.indexOf("\r\n--", start);
        return body.substring(start, end).getBytes(StandardCharsets.ISO_8859_1);
    }
}
