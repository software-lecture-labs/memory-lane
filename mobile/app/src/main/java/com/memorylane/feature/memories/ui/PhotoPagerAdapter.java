package com.memorylane.feature.memories.ui;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.memorylane.R;
import com.memorylane.core.image.PhotoImageLoader;
import com.memorylane.databinding.ItemMemoryViewerPhotoBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Pages of the full-screen viewer, one photo each. A page shows a progress ring while its photo
 * loads, the photo when it arrives, or a short message with "Tekrar dene" when it fails. Photos
 * are decoded for the screen's size through the session's {@link PhotoImageLoader}, with the
 * page's image view as owner; a recycled page cancels its download.
 */
final class PhotoPagerAdapter extends RecyclerView.Adapter<PhotoPagerAdapter.PageHolder> {
    private final PhotoImageLoader<Bitmap> photos;
    private final String memoryId;
    private final List<String> photoIds;
    private final int width;
    private final int height;

    /**
     * @param width  decode width in pixels: the screen's, so a photo is never decoded larger
     * @param height decode height in pixels: the screen's
     */
    PhotoPagerAdapter(@NonNull PhotoImageLoader<Bitmap> photos, @NonNull String memoryId,
                      @NonNull List<String> photoIds, int width, int height) {
        this.photos = photos;
        this.memoryId = memoryId;
        this.photoIds = new ArrayList<>(photoIds);
        this.width = width;
        this.height = height;
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        PageHolder holder = new PageHolder(ItemMemoryViewerPhotoBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.viewerRetry.setOnClickListener(button -> {
            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) load(holder, position);
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        holder.binding.viewerPhoto.setContentDescription(holder.binding.getRoot().getContext()
                .getString(R.string.memory_viewer_photo_description, position + 1, photoIds.size()));
        load(holder, position);
    }

    @Override
    public void onViewRecycled(@NonNull PageHolder holder) {
        super.onViewRecycled(holder);
        photos.cancel(holder.binding.viewerPhoto);
        holder.binding.viewerPhoto.setImageDrawable(null);
    }

    @Override
    public int getItemCount() {
        return photoIds.size();
    }

    private void load(PageHolder holder, int position) {
        ItemMemoryViewerPhotoBinding binding = holder.binding;
        binding.viewerPhoto.setImageDrawable(null);
        binding.viewerPhoto.setVisibility(View.INVISIBLE);
        binding.viewerError.setVisibility(View.GONE);
        binding.viewerProgress.setVisibility(View.VISIBLE);
        photos.load(binding.viewerPhoto, memoryId, photoIds.get(position), width, height, bitmap -> {
            binding.viewerProgress.setVisibility(View.GONE);
            if (bitmap != null) {
                binding.viewerPhoto.setImageBitmap(bitmap);
                binding.viewerPhoto.setVisibility(View.VISIBLE);
            } else {
                binding.viewerError.setVisibility(View.VISIBLE);
            }
        });
    }

    static final class PageHolder extends RecyclerView.ViewHolder {
        final ItemMemoryViewerPhotoBinding binding;

        PageHolder(ItemMemoryViewerPhotoBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
