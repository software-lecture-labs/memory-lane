package com.memorylane.feature.photos.ui;

import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.feature.photos.data.PhotoApi;
import com.memorylane.feature.photos.data.PhotoRules;
import com.memorylane.feature.photos.data.PhotoUploads;
import com.memorylane.feature.photos.data.PreparedPhoto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Uploads the photos picked for one memory, one at a time and in the order they were picked
 * (ML-03, "Kısmi başarı ve tekrar deneme"). Plain Java with no Android types, so every retry rule
 * is unit-tested without a device; callbacks are expected on one thread (the main thread).
 */
public final class PhotoUploadQueue {
    public enum Status {
        READY,
        UPLOADING,
        /** Rate limited; resent automatically after the server's Retry-After. */
        WAITING,
        UPLOADED,
        FAILED
    }

    /** Delays a task; on Android a main-thread Handler, in tests a manual clock. */
    public interface Scheduler {
        Cancellable schedule(long delayMillis, Runnable task);
    }

    /** Contract: without a usable Retry-After header the safe default is 60 seconds. */
    static final long DEFAULT_RETRY_AFTER_SECONDS = 60;
    /**
     * Longest wait honoured. The header comes from the network; an absurd value would leave the
     * photo waiting forever, and a huge one would overflow into a negative delay that resends
     * at once in a loop.
     */
    static final long MAX_RETRY_AFTER_SECONDS = 3600;

    public static final class Item {
        private final PreparedPhoto photo;
        private Status status = Status.READY;
        @Nullable private ApiError error;
        private boolean retryable;

        Item(PreparedPhoto photo) {
            this.photo = photo;
        }

        public PreparedPhoto photo() {
            return photo;
        }

        public Status status() {
            return status;
        }

        /** Why the last attempt failed; null unless {@link #status()} is FAILED. */
        @Nullable
        public ApiError error() {
            return error;
        }

        /** True when "Tekrar dene" can send this photo again; false for a definite rejection. */
        public boolean retryable() {
            return retryable;
        }
    }

    private final PhotoUploads uploads;
    private final Scheduler scheduler;
    private final Runnable onChange;
    private final List<Item> items = new ArrayList<>();
    @Nullable private String session;
    @Nullable private String memoryId;
    @Nullable private Cancellable pending;
    private boolean sessionExpired;

    public PhotoUploadQueue(PhotoUploads uploads, Scheduler scheduler, Runnable onChange) {
        this.uploads = uploads;
        this.scheduler = scheduler;
        this.onChange = onChange;
    }

    public List<Item> items() {
        return Collections.unmodifiableList(items);
    }

    /** How many more photos may be picked; the server enforces the same limit (5 per memory). */
    public int capacity() {
        return PhotoRules.MAX_PHOTOS_PER_MEMORY - items.size();
    }

    public boolean started() {
        return memoryId != null;
    }

    public boolean sessionExpired() {
        return sessionExpired;
    }

    /** Photos can only be added before the memory is saved; afterwards the order is fixed. */
    public boolean add(PreparedPhoto photo) {
        if (started() || capacity() <= 0) return false;
        items.add(new Item(photo));
        onChange.run();
        return true;
    }

    /** Removes a photo that has not been sent; returns false for one that is uploading or uploaded. */
    public boolean remove(Item item) {
        if (item.status == Status.UPLOADING || item.status == Status.UPLOADED || item.status == Status.WAITING) {
            return false;
        }
        boolean removed = items.remove(item);
        if (removed) onChange.run();
        return removed;
    }

    /** Starts uploading to a memory that was just saved. */
    public void start(String session, String memoryId) {
        this.session = session;
        this.memoryId = memoryId;
        uploadNext();
    }

    /** Sends every retryable failure again with its original key and bytes. */
    public void retry() {
        if (!started() || sessionExpired) return;
        for (Item item : items) {
            if (item.status == Status.FAILED && item.retryable) {
                item.status = Status.READY;
                item.error = null;
            }
        }
        onChange.run();
        uploadNext();
    }

    public int count(Status status) {
        int count = 0;
        for (Item item : items) {
            if (item.status == status) count++;
        }
        return count;
    }

    /** Nothing is in flight or queued; the remaining items are uploaded or failed. */
    public boolean idle() {
        // UPLOADING is checked as well as pending: listeners are told about a new upload just
        // before its request handle is stored, and must not take that moment for the end.
        return pending == null && count(Status.READY) == 0 && count(Status.WAITING) == 0
                && count(Status.UPLOADING) == 0;
    }

    /** Stops any request or timer, e.g. when the screen goes away; items keep their state. */
    public void cancel() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        for (Item item : items) {
            if (item.status == Status.UPLOADING || item.status == Status.WAITING) {
                item.status = Status.FAILED;
                item.error = ApiError.network();
                item.retryable = true;
            }
        }
    }

    private void uploadNext() {
        if (pending != null || sessionExpired || !started()) return;
        Item next = null;
        for (Item item : items) {
            if (item.status == Status.READY) {
                next = item;
                break;
            }
        }
        if (next == null) {
            onChange.run();
            return;
        }
        Item item = next;
        item.status = Status.UPLOADING;
        onChange.run();
        pending = uploads.upload(session, memoryId, item.photo, result -> {
            pending = null;
            finished(item, result);
        });
    }

    private void finished(Item item, ApiResult<PhotoApi.Photo> result) {
        if (result.isSuccess()) {
            item.status = Status.UPLOADED;
            item.error = null;
            uploadNext();
            return;
        }
        ApiError error = result.getError() != null ? result.getError() : ApiError.unexpected();
        int status = error.getHttpStatus();
        if (status == 401) {
            // The session is gone; nothing else can succeed until the user signs in again.
            fail(item, error, true);
            sessionExpired = true;
            onChange.run();
        } else if (status == 429) {
            waitAndResend(item, error);
        } else if (error.getKind() == ApiError.Kind.HTTP && status >= 400 && status < 500) {
            // A definite answer (413, 415, 409 PHOTO_LIMIT_REACHED, 404): sending the same file
            // again would get the same answer, so mark it and continue with the next photo.
            fail(item, error, false);
            uploadNext();
        } else {
            // No answer, a 5xx or an unreadable answer: the server may or may not have stored it.
            // Stop here instead of moving on, so the next attempt settles this one with the same
            // key first and the photos keep the order they were picked in.
            fail(item, error, true);
            onChange.run();
        }
    }

    private void waitAndResend(Item item, ApiError error) {
        long seconds = error.getRetryAfterSeconds() == ApiError.NO_RETRY_AFTER
                ? DEFAULT_RETRY_AFTER_SECONDS : Math.min(error.getRetryAfterSeconds(), MAX_RETRY_AFTER_SECONDS);
        item.status = Status.WAITING;
        onChange.run();
        pending = scheduler.schedule(seconds * 1000, () -> {
            pending = null;
            item.status = Status.READY;
            uploadNext();
        });
    }

    private static void fail(Item item, ApiError error, boolean retryable) {
        item.status = Status.FAILED;
        item.error = error;
        item.retryable = retryable;
    }
}
