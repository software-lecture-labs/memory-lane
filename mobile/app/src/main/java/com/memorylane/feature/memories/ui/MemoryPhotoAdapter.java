package com.memorylane.feature.memories.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.memorylane.R;
import com.memorylane.core.image.PhotoImageLoader;
import com.memorylane.databinding.ItemMemoryPhotoBinding;
import com.memorylane.feature.memories.data.MemoryApi;

import java.util.ArrayList;
import java.util.List;

/**
 * Pages of the detail gallery, one photo each, in the server's {@code order ASC}. Photos load
 * through the session's {@link PhotoImageLoader} with the page's image view as owner; a page that
 * is recycled cancels its download.
 */
final class MemoryPhotoAdapter extends RecyclerView.Adapter<MemoryPhotoAdapter.PhotoHolder> {
    /** A photo was tapped; the full-screen viewer opens at that position. */
    interface OnPhotoClick {
        void open(int position);
    }

    private final PhotoImageLoader<Bitmap> photos;
    private final String memoryId;
    private final List<MemoryApi.Photo> items;
    private final int width;
    private final int height;
    private final OnPhotoClick onClick;

    MemoryPhotoAdapter(@NonNull PhotoImageLoader<Bitmap> photos, @NonNull String memoryId,
                       @NonNull List<MemoryApi.Photo> items, int width, int height, @NonNull OnPhotoClick onClick) {
        this.photos = photos;
        this.memoryId = memoryId;
        this.items = new ArrayList<>(items);
        this.width = width;
        this.height = height;
        this.onClick = onClick;
    }

    @NonNull
    @Override
    public PhotoHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        PhotoHolder holder = new PhotoHolder(ItemMemoryPhotoBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.memoryPhoto.setOnClickListener(photo -> {
            int position = holder.getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) onClick.open(position);
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull PhotoHolder holder, int position) {
        ItemMemoryPhotoBinding binding = holder.binding;
        Context context = binding.getRoot().getContext();
        binding.memoryPhoto.setContentDescription(
                context.getString(R.string.memory_detail_photo_description, position + 1, items.size()));
        binding.memoryPhoto.setImageResource(R.color.memory_pink_soft);
        photos.load(binding.memoryPhoto, memoryId, items.get(position).id(), width, height, bitmap -> {
            if (bitmap != null) {
                binding.memoryPhoto.setImageBitmap(bitmap);
                return;
            }
            // Missing file, unreadable image or no connection: the other photos still show, this
            // page says so, and tapping it opens the viewer, which tries again.
            binding.memoryPhoto.setImageResource(R.drawable.memory_photo_missing);
            binding.memoryPhoto.setContentDescription(
                    context.getString(R.string.memory_detail_photo_missing, position + 1, items.size()));
        });
    }

    @Override
    public void onViewRecycled(@NonNull PhotoHolder holder) {
        super.onViewRecycled(holder);
        photos.cancel(holder.binding.memoryPhoto);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class PhotoHolder extends RecyclerView.ViewHolder {
        final ItemMemoryPhotoBinding binding;

        PhotoHolder(ItemMemoryPhotoBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
