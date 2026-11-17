package com.memorylane.feature.photos.ui;

import android.content.Context;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;

import com.memorylane.R;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.databinding.ViewPhotoRowBinding;
import com.memorylane.databinding.ViewPhotoSectionBinding;
import com.memorylane.feature.photos.data.PhotoPreparationException;
import com.memorylane.feature.photos.data.PhotoRules;

import java.util.List;
import java.util.function.Consumer;

/**
 * Draws the photo part of the memory form and forwards taps to {@link PhotoSelectionViewModel};
 * all decisions live in the view model and {@link PhotoUploadQueue}.
 */
public final class PhotoSection {
    private final Context context;
    private final ViewPhotoSectionBinding binding;
    private final PhotoSelectionViewModel model;

    /**
     * Fragments must register for results before they are created, so the host calls this from
     * a field initializer and passes the launcher in later. The system photo picker needs no
     * storage permission and only gives the app the photos the user chose.
     */
    public static ActivityResultLauncher<PickVisualMediaRequest> registerPicker(Fragment fragment,
                                                                                Consumer<List<Uri>> picked) {
        return fragment.registerForActivityResult(
                new ActivityResultContracts.PickMultipleVisualMedia(PhotoRules.MAX_PHOTOS_PER_MEMORY),
                uris -> {
                    if (!uris.isEmpty()) picked.accept(uris);
                });
    }

    public PhotoSection(Context context, ViewPhotoSectionBinding binding, PhotoSelectionViewModel model,
                        ActivityResultLauncher<PickVisualMediaRequest> picker) {
        this.context = context;
        this.binding = binding;
        this.model = model;
        binding.photoAdd.setOnClickListener(button -> picker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build()));
        binding.photoRetry.setOnClickListener(button -> model.retry());
        binding.photoGiveUp.setOnClickListener(button -> model.clear());
    }

    public void render() {
        PhotoUploadQueue queue = model.queue();
        int capacity = model.capacity();
        binding.photoAdd.setEnabled(!queue.started() && capacity > 0);
        binding.photoAdd.setText(capacity > 0
                ? context.getString(R.string.photo_add, capacity) : context.getString(R.string.photo_add_full));

        binding.photoRows.removeAllViews();
        int number = 1;
        for (PhotoUploadQueue.Item item : queue.items()) {
            ViewPhotoRowBinding row = ViewPhotoRowBinding.inflate(LayoutInflater.from(context), binding.photoRows, true);
            row.photoRowText.setText(context.getString(R.string.photo_row, number++, status(item)));
            boolean removable = !queue.started();
            row.photoRowRemove.setVisibility(removable ? View.VISIBLE : View.GONE);
            row.photoRowRemove.setOnClickListener(removable ? button -> model.remove(item) : null);
        }

        int failedRetryable = 0;
        for (PhotoUploadQueue.Item item : queue.items()) {
            if (item.status() == PhotoUploadQueue.Status.FAILED && item.retryable()) failedRetryable++;
        }
        boolean waitingForUser = queue.started() && queue.idle() && failedRetryable > 0 && !queue.sessionExpired();
        binding.photoRetry.setVisibility(waitingForUser ? View.VISIBLE : View.GONE);
        binding.photoGiveUp.setVisibility(waitingForUser ? View.VISIBLE : View.GONE);
        binding.photoSummary.setText(summary(queue, failedRetryable));
    }

    private String summary(PhotoUploadQueue queue, int failedRetryable) {
        if (model.preparingCount() > 0) {
            return context.getString(R.string.photo_preparing, model.preparingCount());
        }
        if (model.lastPickError() != null) {
            return context.getString(model.lastPickError() == PhotoPreparationException.Reason.UNSUPPORTED
                    ? R.string.photo_pick_unsupported : R.string.photo_pick_unreadable);
        }
        if (queue.started() && !queue.idle()) {
            int settled = queue.count(PhotoUploadQueue.Status.UPLOADED) + queue.count(PhotoUploadQueue.Status.FAILED);
            return context.getString(R.string.photo_progress, settled + 1, queue.items().size());
        }
        if (queue.started() && failedRetryable > 0) {
            return context.getString(R.string.photo_partial, failedRetryable);
        }
        PhotoSelectionViewModel.Summary done = model.summary();
        if (done == null) return "";
        if (done.rejected() == 0) return context.getString(R.string.photo_done, done.uploaded());
        return context.getString(R.string.photo_done_with_rejected, done.uploaded(), done.rejected(),
                describe(done.firstRejection()));
    }

    private String status(PhotoUploadQueue.Item item) {
        switch (item.status()) {
            case UPLOADING:
                return context.getString(R.string.photo_status_uploading);
            case WAITING:
                return context.getString(R.string.photo_status_waiting);
            case UPLOADED:
                return context.getString(R.string.photo_status_uploaded);
            case FAILED:
                return context.getString(item.retryable() ? R.string.photo_status_failed : R.string.photo_status_rejected,
                        describe(item.error()));
            case READY:
            default:
                return context.getString(R.string.photo_status_ready);
        }
    }

    // Same wording rules as every other screen: the server's Turkish message when it sent one.
    private String describe(ApiError error) {
        return ErrorMessages.describe(context, error != null ? error : ApiError.unexpected());
    }
}
