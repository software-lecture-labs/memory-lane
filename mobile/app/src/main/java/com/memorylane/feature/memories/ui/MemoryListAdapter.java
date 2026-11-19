package com.memorylane.feature.memories.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.memorylane.R;
import com.memorylane.databinding.ItemMemoryBinding;
import com.memorylane.databinding.ItemMemoryYearBinding;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/** Year headers and memory cards. DiffUtil matches rows by key and redraws only changed ones. */
final class MemoryListAdapter extends ListAdapter<MemoryListItem, RecyclerView.ViewHolder> {
    private static final int TYPE_YEAR = 0;
    private static final int TYPE_MEMORY = 1;

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

    MemoryListAdapter(@NonNull DateTimeFormatter dateFormat) {
        super(DIFF);
        this.dateFormat = dateFormat;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).getType() == MemoryListItem.Type.YEAR_HEADER ? TYPE_YEAR : TYPE_MEMORY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return viewType == TYPE_YEAR
                ? new YearHolder(ItemMemoryYearBinding.inflate(inflater, parent, false))
                : new MemoryHolder(ItemMemoryBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        MemoryListItem item = getItem(position);
        if (holder instanceof YearHolder) {
            ((YearHolder) holder).bind(item);
        } else {
            ((MemoryHolder) holder).bind(item, dateFormat);
        }
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
            binding.getRoot().setContentDescription(binding.getRoot().getContext()
                    .getString(R.string.memory_list_year_description, item.getYear()));
        }
    }

    static final class MemoryHolder extends RecyclerView.ViewHolder {
        private final ItemMemoryBinding binding;

        MemoryHolder(ItemMemoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(MemoryListItem item, DateTimeFormatter dateFormat) {
            binding.memoryDate.setText(dateFormat.format(Objects.requireNonNull(item.getDate())));
            binding.memoryTitle.setText(item.getTitle());
            String description = item.getDescription();
            boolean hasDescription = description != null && !description.trim().isEmpty();
            binding.memoryDescription.setText(hasDescription ? description : null);
            binding.memoryDescription.setVisibility(hasDescription ? View.VISIBLE : View.GONE);
        }
    }
}
