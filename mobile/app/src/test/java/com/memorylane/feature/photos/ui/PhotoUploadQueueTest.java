package com.memorylane.feature.photos.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.feature.photos.data.PhotoApi;
import com.memorylane.feature.photos.data.PhotoUploads;
import com.memorylane.feature.photos.data.PreparedPhoto;
import com.memorylane.feature.photos.ui.PhotoUploadQueue.Status;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** ML-10 retry rules from the ML-03 contract, without a server or device. */
public class PhotoUploadQueueTest {
    private final FakeUploads uploads = new FakeUploads();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final PhotoUploadQueue queue = new PhotoUploadQueue(uploads, scheduler, () -> { });

    @Test
    public void uploadsOneAtATimeInTheOrderPicked() {
        PreparedPhoto first = photo("a"), second = photo("b");
        queue.add(first);
        queue.add(second);

        queue.start("Bearer t", "m1");
        assertEquals(1, uploads.calls.size());
        assertSame(first, uploads.calls.get(0).photo);

        uploads.answer(0, success());
        assertEquals(2, uploads.calls.size());
        assertSame(second, uploads.calls.get(1).photo);
        uploads.answer(1, success());

        assertEquals(2, queue.count(Status.UPLOADED));
        assertTrue(queue.idle());
    }

    @Test
    public void connectionFailureStopsTheQueueAndRetrySendsOnlyThatPhotoAgain() {
        PreparedPhoto first = photo("a"), second = photo("b"), third = photo("c");
        queue.add(first);
        queue.add(second);
        queue.add(third);
        queue.start("Bearer t", "m1");
        uploads.answer(0, success());

        uploads.answer(1, ApiResult.failure(ApiError.network()));
        assertEquals(2, uploads.calls.size());
        assertEquals(Status.FAILED, queue.items().get(1).status());
        assertTrue(queue.items().get(1).retryable());
        assertEquals(Status.READY, queue.items().get(2).status());

        queue.retry();
        assertSame(second, uploads.calls.get(2).photo);
        uploads.answer(2, success());
        assertSame(third, uploads.calls.get(3).photo);
        uploads.answer(3, success());
        assertEquals(3, queue.count(Status.UPLOADED));
    }

    @Test
    public void serverErrorIsUncertainAndAlsoStopsTheQueue() {
        queue.add(photo("a"));
        queue.add(photo("b"));
        queue.start("Bearer t", "m1");

        uploads.answer(0, ApiResult.failure(ApiError.http(503, "DATABASE_UNAVAILABLE", null)));

        assertEquals(1, uploads.calls.size());
        assertTrue(queue.items().get(0).retryable());
    }

    @Test
    public void definiteRejectionIsMarkedAndTheNextPhotoStillUploads() {
        queue.add(photo("a"));
        queue.add(photo("b"));
        queue.start("Bearer t", "m1");

        uploads.answer(0, ApiResult.failure(ApiError.http(413, "FILE_TOO_LARGE", "Fotoğraf en fazla 5 MB olabilir.")));
        assertEquals(2, uploads.calls.size());
        uploads.answer(1, success());

        PhotoUploadQueue.Item rejected = queue.items().get(0);
        assertEquals(Status.FAILED, rejected.status());
        assertFalse(rejected.retryable());
        assertEquals("FILE_TOO_LARGE", rejected.error().getCode());

        queue.retry();
        assertEquals("a definite rejection is not sent again", 2, uploads.calls.size());
    }

    @Test
    public void rateLimitWaitsForRetryAfterThenResendsTheSamePhoto() {
        PreparedPhoto first = photo("a");
        queue.add(first);
        queue.start("Bearer t", "m1");

        uploads.answer(0, ApiResult.failure(ApiError.http(429, "RATE_LIMITED", null, 17)));
        assertEquals(Status.WAITING, queue.items().get(0).status());
        assertEquals(17_000, scheduler.delays.get(0).longValue());
        assertEquals(1, uploads.calls.size());

        scheduler.runNext();
        assertSame(first, uploads.calls.get(1).photo);
    }

    @Test
    public void rateLimitWithoutRetryAfterWaitsTheContractDefault() {
        queue.add(photo("a"));
        queue.start("Bearer t", "m1");

        uploads.answer(0, ApiResult.failure(ApiError.http(429, "RATE_LIMITED", null)));

        assertEquals(PhotoUploadQueue.DEFAULT_RETRY_AFTER_SECONDS * 1000, scheduler.delays.get(0).longValue());
    }

    @Test
    public void absurdRetryAfterIsCappedInsteadOfOverflowing() {
        queue.add(photo("a"));
        queue.start("Bearer t", "m1");

        uploads.answer(0, ApiResult.failure(ApiError.http(429, "RATE_LIMITED", null, Long.MAX_VALUE)));

        assertEquals(PhotoUploadQueue.MAX_RETRY_AFTER_SECONDS * 1000, scheduler.delays.get(0).longValue());
    }

    @Test
    public void expiredSessionStopsEverythingIncludingRetry() {
        queue.add(photo("a"));
        queue.add(photo("b"));
        queue.start("Bearer t", "m1");

        uploads.answer(0, ApiResult.failure(ApiError.http(401, "INVALID_SESSION", null)));
        queue.retry();

        assertTrue(queue.sessionExpired());
        assertEquals(1, uploads.calls.size());
    }

    @Test
    public void atMostFivePhotosAndNoChangesAfterTheUploadStarted() {
        for (int i = 0; i < 5; i++) assertTrue(queue.add(photo("p" + i)));
        assertFalse(queue.add(photo("sixth")));
        assertEquals(0, queue.capacity());

        assertTrue(queue.remove(queue.items().get(4)));
        queue.start("Bearer t", "m1");
        assertFalse(queue.add(photo("late")));
        assertFalse("an uploading photo cannot be removed", queue.remove(queue.items().get(0)));
    }

    @Test
    public void cancelStopsTheRequestAndLeavesItRetryable() {
        queue.add(photo("a"));
        queue.start("Bearer t", "m1");

        queue.cancel();

        assertTrue(uploads.calls.get(0).cancelled);
        assertEquals(Status.FAILED, queue.items().get(0).status());
        assertTrue(queue.items().get(0).retryable());
        assertNull(scheduler.next);
    }

    @Test
    public void neverReportsIdleWhileThePhotoBeingSentHasNoAnswerYet() {
        // The screen frees the section (and deletes the copies) as soon as the queue says idle;
        // the last photo starting its upload must not look like the end of the queue.
        List<Boolean> idleWhileUploading = new ArrayList<>();
        PhotoUploadQueue[] watched = new PhotoUploadQueue[1];
        watched[0] = new PhotoUploadQueue(uploads, scheduler, () -> {
            if (watched[0].count(Status.UPLOADING) > 0) idleWhileUploading.add(watched[0].idle());
        });
        watched[0].add(photo("a"));
        watched[0].add(photo("b"));

        watched[0].start("Bearer t", "m1");
        uploads.answer(0, success());
        uploads.answer(1, success());

        assertFalse(idleWhileUploading.isEmpty());
        assertFalse(idleWhileUploading.contains(true));
        assertTrue(watched[0].idle());
    }

    @Test
    public void startingAgainForTheSameMemorySendsNothingTwice() {
        queue.add(photo("a"));
        queue.add(photo("b"));
        queue.start("Bearer t", "m1");

        queue.start("Bearer t", "m1");

        assertEquals(1, uploads.calls.size());
        uploads.answer(0, success());
        uploads.answer(1, success());
        assertEquals(2, uploads.calls.size());
        assertEquals(2, queue.count(Status.UPLOADED));
    }

    @Test
    public void aQueueUploadingForOneMemoryIsNeverMovedToAnother() {
        queue.add(photo("a"));
        queue.add(photo("b"));
        queue.start("Bearer t", "m1");

        // A second memory saved while the first photo is still on its way.
        assertThrows(IllegalStateException.class, () -> queue.start("Bearer t", "m2"));
        uploads.answer(0, success());

        assertEquals(2, uploads.calls.size());
        assertEquals("m1", uploads.calls.get(0).memoryId);
        assertEquals("m1", uploads.calls.get(1).memoryId);
    }

    @Test
    public void photosWaitingForRetryStayWithTheirOwnMemory() {
        queue.add(photo("a"));
        queue.add(photo("b"));
        queue.start("Bearer t", "m1");
        uploads.answer(0, ApiResult.failure(ApiError.network()));

        // The queue is idle and waiting for "Tekrar dene" when the next memory is saved.
        assertThrows(IllegalStateException.class, () -> queue.start("Bearer t", "m2"));
        queue.retry();

        assertEquals(2, uploads.calls.size());
        assertEquals("m1", uploads.calls.get(1).memoryId);
        assertSame(uploads.calls.get(0).photo, uploads.calls.get(1).photo);
    }

    private static PreparedPhoto photo(String key) {
        return new PreparedPhoto(key, new File(key + ".jpg"), "image/jpeg");
    }

    private static ApiResult<PhotoApi.Photo> success() {
        return ApiResult.success(new PhotoApi.Photo("id", "m1", 0, "image/jpeg", 10, "/content"));
    }

    private static final class Call {
        final String memoryId;
        final PreparedPhoto photo;
        final ResultCallback<PhotoApi.Photo> callback;
        boolean cancelled;

        Call(String memoryId, PreparedPhoto photo, ResultCallback<PhotoApi.Photo> callback) {
            this.memoryId = memoryId;
            this.photo = photo;
            this.callback = callback;
        }
    }

    /** Records each upload so the test decides when and how the "server" answers. */
    private static final class FakeUploads implements PhotoUploads {
        final List<Call> calls = new ArrayList<>();

        @Override
        public Cancellable upload(String session, String memoryId, PreparedPhoto photo,
                                  ResultCallback<PhotoApi.Photo> callback) {
            Call call = new Call(memoryId, photo, callback);
            calls.add(call);
            return () -> call.cancelled = true;
        }

        void answer(int index, ApiResult<PhotoApi.Photo> result) {
            calls.get(index).callback.onResult(result);
        }
    }

    /** A clock moved by hand: delays are recorded and run only when the test says so. */
    private static final class FakeScheduler implements PhotoUploadQueue.Scheduler {
        final List<Long> delays = new ArrayList<>();
        Runnable next;

        @Override
        public Cancellable schedule(long delayMillis, Runnable task) {
            delays.add(delayMillis);
            next = task;
            return () -> next = null;
        }

        void runNext() {
            Runnable task = next;
            next = null;
            task.run();
        }
    }
}
