package com.memorylane.feature.photos.ui;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.photos.data.PhotoPreparationException;
import com.memorylane.feature.photos.data.PhotoPreparer;
import com.memorylane.feature.photos.data.PhotoUploads;
import com.memorylane.feature.photos.data.PreparedPhoto;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photos picked for the memory being written, from picking to the last upload attempt. Keeps the
 * queue across rotation; preparing (copying, converting) runs on one background thread so the
 * screen never waits for it.
 */
public class PhotoSelectionViewModel extends ViewModel {
    private final PhotoUploads uploads;
    private final PhotoPreparer preparer;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    private PhotoUploadQueue queue;
    private int preparing;
    private boolean cleared;
    @Nullable private PhotoPreparationException.Reason lastPickError;
    @Nullable private Summary summary;

    /** Result of the last finished upload, shown until new photos are picked. */
    public record Summary(int uploaded, int rejected, @Nullable ApiError firstRejection) {}

    public PhotoSelectionViewModel(PhotoUploads uploads, PhotoPreparer preparer) {
        this.uploads = uploads;
        this.preparer = preparer;
        this.queue = newQueue();
    }

    public LiveData<Integer> changes() {
        return changes;
    }

    public PhotoUploadQueue queue() {
        return queue;
    }

    /** True while picked photos are still being copied; the memory should not be saved yet. */
    public boolean preparing() {
        return preparing > 0;
    }

    public int preparingCount() {
        return preparing;
    }

    /** Why the most recent picked photo could not be used, or null. */
    @Nullable
    public PhotoPreparationException.Reason lastPickError() {
        return lastPickError;
    }

    @Nullable
    public Summary summary() {
        return summary;
    }

    /** Space left for this memory, counting photos still being prepared. */
    public int capacity() {
        return queue.capacity() - preparing;
    }

    public void pick(List<Uri> uris) {
        lastPickError = null;
        summary = null;
        for (Uri uri : uris.subList(0, Math.min(uris.size(), capacity()))) {
            preparing++;
            background.execute(() -> {
                try {
                    PreparedPhoto photo = preparer.prepare(uri);
                    main.post(() -> added(photo));
                } catch (PhotoPreparationException error) {
                    main.post(() -> failed(error.reason()));
                } catch (RuntimeException unexpected) {
                    // A gallery provider or decoder can throw anything for a malformed file. The
                    // counter must still go down, or "Anıyı kaydet" would stay disabled for good.
                    main.post(() -> failed(PhotoPreparationException.Reason.UNREADABLE));
                }
            });
        }
        publish();
    }

    /**
     * True while the photos of a saved memory are still uploading or waiting for "Tekrar dene" /
     * "Vazgeç". The screen does not save another memory meanwhile: these photos belong to the
     * earlier one.
     */
    public boolean busy() {
        return queue.started();
    }

    /**
     * Called once the memory is saved; a memory saved without photos needs nothing here. A queue
     * still busy with an earlier memory is left alone instead of being handed the new one.
     */
    public void startUpload(String session, String memoryId) {
        if (queue.items().isEmpty() || busy()) return;
        queue.start(session, memoryId);
    }

    public void retry() {
        queue.retry();
    }

    public void remove(PhotoUploadQueue.Item item) {
        if (queue.remove(item)) preparer.discard(item.photo());
    }

    /** Clears the section for the next memory; the gallery originals are not touched. */
    public void clear() {
        queue.cancel();
        discardAll();
        queue = newQueue();
        lastPickError = null;
        summary = null;
        publish();
    }

    @Override
    protected void onCleared() {
        cleared = true;
        queue.cancel();
        background.shutdownNow();
        discardAll();
    }

    private void added(PreparedPhoto photo) {
        preparing--;
        // The screen may have closed, or the memory been saved, while this photo was prepared;
        // its copy is then deleted instead of being left behind in the cache.
        if (cleared || !queue.add(photo)) {
            preparer.discard(photo);
            return;
        }
        publish();
    }

    private void failed(PhotoPreparationException.Reason reason) {
        preparing--;
        if (cleared) return;
        lastPickError = reason;
        publish();
    }

    private PhotoUploadQueue newQueue() {
        return new PhotoUploadQueue(uploads, (delayMillis, task) -> {
            main.postDelayed(task, delayMillis);
            return () -> main.removeCallbacks(task);
        }, this::uploadChanged);
    }

    // Uploaded copies are no longer needed; delete them as soon as the server has the photo.
    // When every photo is settled and none can be retried, keep only a summary and free the
    // section for the next memory.
    private void uploadChanged() {
        for (PhotoUploadQueue.Item item : queue.items()) {
            if (item.status() == PhotoUploadQueue.Status.UPLOADED) preparer.discard(item.photo());
        }
        if (queue.started() && queue.idle() && retryableFailures() == 0) {
            summary = summarize();
            discardAll();
            queue = newQueue();
        }
        publish();
    }

    private int retryableFailures() {
        int count = 0;
        for (PhotoUploadQueue.Item item : queue.items()) {
            if (item.status() == PhotoUploadQueue.Status.FAILED && item.retryable()) count++;
        }
        return count;
    }

    private Summary summarize() {
        ApiError firstRejection = null;
        for (PhotoUploadQueue.Item item : queue.items()) {
            if (item.status() == PhotoUploadQueue.Status.FAILED && firstRejection == null) firstRejection = item.error();
        }
        return new Summary(queue.count(PhotoUploadQueue.Status.UPLOADED),
                queue.count(PhotoUploadQueue.Status.FAILED), firstRejection);
    }

    private void discardAll() {
        for (PhotoUploadQueue.Item item : queue.items()) preparer.discard(item.photo());
    }

    private void publish() {
        if (cleared) return;
        Integer value = changes.getValue();
        changes.setValue(value == null ? 1 : value + 1);
    }

    public static ViewModelProvider.Factory factory(PhotoUploads uploads, PhotoPreparer preparer) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(PhotoSelectionViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
                }
                return modelClass.cast(new PhotoSelectionViewModel(uploads, preparer));
            }
        };
    }
}
