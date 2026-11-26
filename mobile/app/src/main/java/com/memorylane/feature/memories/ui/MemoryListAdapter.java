package com.memorylane.feature.memories.ui;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.memorylane.R;
import com.memorylane.core.image.PhotoImageLoader;
import com.memorylane.databinding.ItemMemoryBinding;
import com.memorylane.databinding.ItemMemoryYearBinding;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Year headers and memory cards. DiffUtil matches rows by key and redraws only changed ones.
 *
 * <p>Covers come from {@link PhotoImageLoader} with the card's cover view as owner, so a
 * recycled card only ever shows its latest memory's cover; {@link #onViewRecycled} also cancels
 * the download of a card that leaves the screen.
 */
final class MemoryListAdapter extends ListAdapter<MemoryListItem, RecyclerView.ViewHolder> {
    private static final int TYPE_YEAR = 0;
    private static final int TYPE_MEMORY = 1;

    /** Opens the memory with the given id. */
    interface OnMemoryClick {
        void open(@NonNull String memoryId);
    }

    static final DiffUtil.ItemCallback<MemoryListItem> DIFF = new DiffUtil.ItemCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull MemoryListItem oldItem, @NonNull MemoryListItem newItem) {
            return oldItem.getKey().equals(newItem.getKey());
        }

        @Override
        public boolean areContentsTheSame(@NonNull MemoryListItem oldItem, @NonNull MemoryListItem newItem) {
            return oldItem.equals(newItem);
        }
    };

    private final DateTimeFormatter dateFormat;
    private final PhotoImageLoader<Bitmap> covers;
    private final int coverWidth;
    private final int coverHeight;
    private final OnMemoryClick onClick;

    /**
     * @param coverWidth  decode size of a cover in pixels: about the card's width, so a cover is
     *                    never decoded at the photo's full resolution
     * @param coverHeight decode height in pixels, the cover's height on screen
     */
    MemoryListAdapter(@NonNull DateTimeFormatter dateFormat, @NonNull PhotoImageLoader<Bitmap> covers,
                      int coverWidth, int coverHeight, @NonNull OnMemoryClick onClick) {
        super(DIFF);
        this.dateFormat = dateFormat;
        this.covers = covers;
        this.coverWidth = coverWidth;
        this.coverHeight = coverHeight;
        this.onClick = onClick;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType() == MemoryListItem.Type.YEAR_HEADER ? TYPE_YEAR : TYPE_MEMORY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_YEAR) {
            return new YearHolder(ItemMemoryYearBinding.inflate(inflater, parent, false));
        }
        MemoryHolder holder = new MemoryHolder(ItemMemoryBinding.inflate(inflater, parent, false));
        holder.binding.memoryCard.setOnClickListener(card -> {
            int position = holder.getBindingAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return;
            String id = getItem(position).getId();
            if (id != null) onClick.open(id);
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MemoryListItem item = getItem(position);
        if (holder instanceof YearHolder) {
            ((YearHolder) holder).bind(item);
        } else {
            bindMemory((MemoryHolder) holder, item);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof MemoryHolder) {
            MemoryHolder card = (MemoryHolder) holder;
            covers.cancel(card.binding.memoryCover);
            card.binding.memoryCover.setImageResource(R.color.memory_pink_soft);
        }
    }

    private void bindMemory(MemoryHolder holder, MemoryListItem item) {
        ItemMemoryBinding binding = holder.binding;
        Resources resources = binding.getRoot().getResources();
        binding.memoryDate.setText(dateFormat.format(Objects.requireNonNull(item.getDate())));
        binding.memoryTitle.setText(item.getTitle());
        String description = item.getDescription();
        boolean hasDescription = description != null && !description.trim().isEmpty();
        binding.memoryDescription.setText(hasDescription ? description : null);
        binding.memoryDescription.setVisibility(hasDescription ? View.VISIBLE : View.GONE);

        String cover = item.getCoverPhotoId();
        boolean hasCover = cover != null;
        binding.memoryCoverFrame.setVisibility(hasCover ? View.VISIBLE : View.GONE);
        binding.memoryStripe.setVisibility(hasCover ? View.GONE : View.VISIBLE);
        if (hasCover) {
            binding.memoryPhotoCount.setText(resources.getQuantityString(
                    R.plurals.memory_photo_count, item.getPhotoCount(), item.getPhotoCount()));
            binding.memoryCover.setImageResource(R.color.memory_pink_soft);
            covers.load(binding.memoryCover, Objects.requireNonNull(item.getId()), cover, coverWidth, coverHeight,
                    bitmap -> {
                        if (bitmap != null) binding.memoryCover.setImageBitmap(bitmap);
                    });
        } else {
            covers.cancel(binding.memoryCover);
        }

        boolean category = showLabel(binding.memoryBadgeCategory, MemoryLabels.category(item.getCategory()));
        boolean emotion = showLabel(binding.memoryBadgeEmotion, MemoryLabels.emotion(item.getEmotion()));
        boolean location = item.getLocation() != null;
        if (location) {
            binding.memoryBadgeLocation.setText(MemoryLabels.coordinates(item.getLocation(),
                    resources.getString(R.string.memory_direction_north),
                    resources.getString(R.string.memory_direction_south),
                    resources.getString(R.string.memory_direction_east),
                    resources.getString(R.string.memory_direction_west)));
        }
        binding.memoryBadgeLocation.setVisibility(location ? View.VISIBLE : View.GONE);
        binding.memoryBadges.setVisibility(category || emotion || location ? View.VISIBLE : View.GONE);
    }

    private static boolean showLabel(Chip badge, @StringRes int label) {
        boolean shown = label != MemoryLabels.NONE;
        if (shown) badge.setText(label);
        badge.setVisibility(shown ? View.VISIBLE : View.GONE);
        return shown;
    }

    static final class YearHolder extends RecyclerView.ViewHolder {
        private final ItemMemoryYearBinding binding;

        YearHolder(ItemMemoryYearBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.getRoot(), true);
        }

        void bind(MemoryListItem item) {
            // Locale.ROOT keeps plain digits; a year is not a quantity to group ("2.024").
            binding.memoryYear.setText(String.format(Locale.ROOT, "%d", item.getYear()));
            // Alternating node colours by year, as on the Stitch timeline; stable for a given year.
            binding.memoryYearNode.setBackgroundResource(item.getYear() % 2 == 0
                    ? R.drawable.memory_timeline_node : R.drawable.memory_timeline_node_alt);
            binding.getRoot().setContentDescription(binding.getRoot().getContext()
                    .getString(R.string.memory_list_year_description, item.getYear()));
        }

        /** Vertical centre of the node within the row; where the timeline line starts. */
        float nodeCenterY() {
            return binding.memoryYearNode.getTop() + binding.memoryYearNode.getHeight() / 2f;
        }
    }

    static final class MemoryHolder extends RecyclerView.ViewHolder {
        final ItemMemoryBinding binding;

        MemoryHolder(ItemMemoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
