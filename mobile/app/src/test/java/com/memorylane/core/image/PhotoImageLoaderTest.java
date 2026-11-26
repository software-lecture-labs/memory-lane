package com.memorylane.core.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.memorylane.core.network.ApiClient;
import com.memorylane.core.network.AuthInterceptor;
import com.memorylane.core.session.SessionStore;
import com.memorylane.feature.photos.data.PhotoApi;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * {@link PhotoImageLoader} over real HTTP with the app's session interceptor. Images are Strings
 * here ("bytes@WxH"), because Android's Bitmap does not exist on the JVM; the caching, session
 * and recycling rules are the same.
 */
public class PhotoImageLoaderTest {
    private static final String TOKEN = "sahte-token";
    private static final String MEMORY = "22222222-2222-4222-8222-222222222222";

    private MockWebServer server;
    private SessionStore session;
    private ExecutorService background;
    private PhotoImageLoader<String> loader;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        session = new SessionStore(Runnable::run);
        session.start(TOKEN, "ayse@example.com");
        String baseUrl = server.url("/").toString();
        PhotoApi api = ApiClient.create(baseUrl, new Gson(), new AuthInterceptor(session, baseUrl)).create(PhotoApi.class);
        background = Executors.newCachedThreadPool();
        loader = loader(api, 1_000);
        session.addListener(loader);
    }

    @After
    public void tearDown() throws IOException {
        background.shutdownNow();
        server.shutdown();
    }

    private PhotoImageLoader<String> loader(PhotoApi api, long maxBytes) {
        PhotoImageLoader.Decoder<String> decoder = (bytes, width, height) -> {
            String text = new String(bytes, StandardCharsets.UTF_8);
            return text.startsWith("bozuk") ? null : text + "@" + width + "x" + height;
        };
        return new PhotoImageLoader<>(api, decoder, String::length, maxBytes, background, Runnable::run);
    }

    @Test
    public void firstLoadDownloadsWithTheSessionAndDecodesAtTheTargetSize() throws Exception {
        server.enqueue(photo("kapak"));

        assertEquals("kapak@360x240", load(new Object(), "p1", 360, 240));

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(sent);
        assertEquals("GET", sent.getMethod());
        assertEquals("/api/v1/memories/" + MEMORY + "/photos/p1/content", sent.getPath());
        assertEquals("Bearer " + TOKEN, sent.getHeader("Authorization"));
    }

    @Test
    public void secondLoadIsServedFromMemoryWithoutTheNetwork() throws Exception {
        server.enqueue(photo("kapak"));
        load(new Object(), "p1", 360, 240);

        List<String> results = new CopyOnWriteArrayList<>();
        loader.load(new Object(), MEMORY, "p1", 360, 240, results::add);

        assertEquals(List.of("kapak@360x240"), results); // delivered at once, no waiting
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void anotherSizeOfTheSamePhotoIsASeparateImage() throws Exception {
        server.enqueue(photo("kapak"));
        server.enqueue(photo("kapak"));

        load(new Object(), "p1", 360, 240);
        assertEquals("kapak@1080x1920", load(new Object(), "p1", 1080, 1920));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void clearDropsTheCacheSoTheNextLoadDownloadsAgain() throws Exception {
        server.enqueue(photo("kapak"));
        server.enqueue(photo("yeni"));
        load(new Object(), "p1", 360, 240);

        loader.clear();

        assertEquals("yeni@360x240", load(new Object(), "p1", 360, 240));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void failedOrUnreadableDownloadsGiveNullAndAreNotCached() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404)
                .setBody("{\"code\":\"PHOTO_NOT_FOUND\",\"message\":\"Fotoğraf bulunamadı.\"}"));
        server.enqueue(photo("bozuk"));
        server.enqueue(photo("kapak"));

        assertNull(load(new Object(), "p1", 100, 100));
        assertNull(load(new Object(), "p1", 100, 100));
        assertEquals("kapak@100x100", load(new Object(), "p1", 100, 100));
        assertEquals(3, server.getRequestCount());
    }

    @Test
    public void oneMissingPhotoLeavesTheOthersAndReopeningRetriesOnlyThatOne() throws Exception {
        // A five-photo gallery whose third file is missing on the server the first time.
        AtomicBoolean fileRestored = new AtomicBoolean(false);
        List<String> requested = new CopyOnWriteArrayList<>();
        server.setDispatcher(new Dispatcher() {
            @NonNull
            @Override
            public MockResponse dispatch(@NonNull RecordedRequest request) {
                String path = String.valueOf(request.getPath());
                String id = path.replaceAll(".*/photos/([^/]+)/content", "$1");
                requested.add(id);
                if (id.equals("p3") && !fileRestored.get()) {
                    return new MockResponse().setResponseCode(404)
                            .setBody("{\"code\":\"PHOTO_NOT_FOUND\",\"message\":\"Fotoğraf bulunamadı.\"}");
                }
                return photo("foto-" + id);
            }
        });
        List<String> gallery = List.of("p1", "p2", "p3", "p4", "p5");

        // First opening: every page gets its own answer; only the missing one is null.
        List<String> first = new ArrayList<>();
        for (String id : gallery) first.add(load(new Object(), id, 300, 200));
        assertEquals(Arrays.asList("foto-p1@300x200", "foto-p2@300x200", null, "foto-p4@300x200", "foto-p5@300x200"),
                first);

        // Reopened after the file is back: the four photos come from memory, only p3 is asked again.
        fileRestored.set(true);
        requested.clear();
        List<String> reopened = new ArrayList<>();
        for (String id : gallery) reopened.add(load(new Object(), id, 300, 200));
        assertEquals(List.of("foto-p1@300x200", "foto-p2@300x200", "foto-p3@300x200", "foto-p4@300x200",
                "foto-p5@300x200"), reopened);
        assertEquals(List.of("p3"), requested);
    }

    @Test
    public void unreachableServerGivesNull() throws Exception {
        server.shutdown();

        assertNull(load(new Object(), "p1", 100, 100));
    }

    @Test
    public void recycledOwnerOnlyGetsItsLatestPhoto() throws Exception {
        // The first photo answers late; meanwhile the same cell was rebound to another photo.
        server.setDispatcher(new Dispatcher() {
            @NonNull
            @Override
            public MockResponse dispatch(@NonNull RecordedRequest request) {
                boolean slow = request.getPath() != null && request.getPath().contains("/photos/eski/");
                MockResponse response = photo(slow ? "eski" : "yeni");
                return slow ? response.setBodyDelay(300, TimeUnit.MILLISECONDS) : response;
            }
        });
        Object cell = new Object();
        List<String> results = new CopyOnWriteArrayList<>();
        CountDownLatch latest = new CountDownLatch(1);

        loader.load(cell, MEMORY, "eski", 100, 100, results::add);
        loader.load(cell, MEMORY, "yeni", 100, 100, image -> {
            results.add(image);
            latest.countDown();
        });

        assertTrue(latest.await(5, TimeUnit.SECONDS));
        Thread.sleep(600); // long enough for the old answer to arrive, had it not been cancelled
        assertEquals(List.of("yeni@100x100"), results);
    }

    @Test
    public void cancelledRequestIsNeverDelivered() throws Exception {
        server.enqueue(photo("kapak").setBodyDelay(300, TimeUnit.MILLISECONDS));
        Object cell = new Object();
        CountDownLatch delivered = new CountDownLatch(1);

        loader.load(cell, MEMORY, "p1", 100, 100, image -> delivered.countDown());
        loader.cancel(cell);

        assertFalse(delivered.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void answerOfARequestRunningWhileTheCacheIsClearedIsDropped() throws Exception {
        // Whether the cancelled download reached the server or not, later requests get the new photo.
        AtomicBoolean cleared = new AtomicBoolean();
        server.setDispatcher(new Dispatcher() {
            @NonNull
            @Override
            public MockResponse dispatch(@NonNull RecordedRequest request) {
                return cleared.get() ? photo("yeni")
                        : photo("onceki-hesap").setBodyDelay(300, TimeUnit.MILLISECONDS);
            }
        });
        CountDownLatch delivered = new CountDownLatch(1);

        loader.load(new Object(), MEMORY, "p1", 100, 100, image -> delivered.countDown());
        loader.clear();
        cleared.set(true);

        assertFalse(delivered.await(1, TimeUnit.SECONDS));
        assertEquals("nothing of it was cached", "yeni@100x100", load(new Object(), "p1", 100, 100));
    }

    @Test
    public void sessionEndClearsTheCache() throws Exception {
        server.enqueue(photo("kapak"));
        server.enqueue(photo("kapak"));
        load(new Object(), "p1", 100, 100);

        session.logout();
        session.start(TOKEN, "ayse@example.com");

        load(new Object(), "p1", 100, 100);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void rejectedSessionEndsItAndDropsTheAnswer() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"INVALID_SESSION\",\"message\":\"Geçerli oturum gereklidir.\"}"));
        CountDownLatch delivered = new CountDownLatch(1);

        loader.load(new Object(), MEMORY, "p1", 100, 100, image -> delivered.countDown());

        assertFalse(delivered.await(1, TimeUnit.SECONDS));
        assertFalse(session.isActive());
    }

    @Test
    public void leastRecentlyUsedImagesLeaveFirstWhenTheBudgetIsFull() throws Exception {
        // Budget 30 "bytes"; each image is "xxxxxxxx@10x10" = 14.
        String baseUrl = server.url("/").toString();
        loader = loader(ApiClient.create(baseUrl, new Gson(), new AuthInterceptor(session, baseUrl))
                .create(PhotoApi.class), 30);
        for (String name : new String[] {"aaaaaaaa", "bbbbbbbb", "cccccccc", "aaaaaaaa"}) {
            server.enqueue(photo(name));
        }
        load(new Object(), "a", 10, 10);
        load(new Object(), "b", 10, 10);
        load(new Object(), "a", 10, 10); // cached; "a" is now the most recently used
        load(new Object(), "c", 10, 10); // over budget: "b" leaves
        assertEquals(3, server.getRequestCount());

        load(new Object(), "a", 10, 10);
        assertEquals(3, server.getRequestCount());
        load(new Object(), "b", 10, 10);
        assertEquals(4, server.getRequestCount());
    }

    @Test
    public void imageLargerThanTheBudgetIsShownButNotKept() throws Exception {
        server.enqueue(photo("x".repeat(2_000)));
        server.enqueue(photo("x".repeat(2_000)));

        assertNotNull(load(new Object(), "p1", 10, 10));
        assertNotNull(load(new Object(), "p1", 10, 10));
        assertEquals(2, server.getRequestCount());
    }

    private static MockResponse photo(String content) {
        return new MockResponse().setHeader("Content-Type", "image/jpeg").setHeader("Cache-Control", "no-store")
                .setBody(content);
    }

    /** Loads and waits for the answer; null when the loader reported a failure. */
    private String load(Object owner, String photoId, int width, int height) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        loader.load(owner, MEMORY, photoId, width, height, image -> {
            result[0] = image;
            done.countDown();
        });
        assertTrue("load did not finish", done.await(10, TimeUnit.SECONDS));
        return result[0];
    }
}
