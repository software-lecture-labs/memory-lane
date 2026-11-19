package com.memorylane.feature.memories.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.memorylane.R;
import com.memorylane.databinding.ItemMemoryFooterBinding;

import java.util.Objects;

/** Zero or one row after the memories: next-page progress, an error with retry, or "load more". */
final class MemoryListFooterAdapter extends RecyclerView.Adapter<MemoryListFooterAdapter.Holder> {
    enum Mode { NONE, LOADING, ERROR, LOAD_MORE }

    private final Runnable onLoadMore;
    private final Runnable onRetry;
    private Mode mode = Mode.NONE;
    @Nullable private CharSequence message;

    MemoryListFooterAdapter(@NonNull Runnable onLoadMore, @NonNull Runnable onRetry) {
        this.onLoadMore = onLoadMore;
        this.onRetry = onRetry;
    }

    /** @param message the error text; only used in ERROR mode */
    void submit(@NonNull Mode newMode, @Nullable CharSequence newMessage) {
        CharSequence shownMessage = newMode == Mode.ERROR ? newMessage : null;
        if (newMode == mode && Objects.equals(shownMessage, message)) return;
        boolean wasShown = mode != Mode.NONE;
        boolean isShown = newMode != Mode.NONE;
        mode = newMode;
        message = shownMessage;
        if (wasShown && isShown) {
            notifyItemChanged(0);
        } else if (isShown) {
            notifyItemInserted(0);
        } else if (wasShown) {
            notifyItemRemoved(0);
        }
    }

    @Override
    public int getItemCount() {
        return mode == Mode.NONE ? 0 : 1;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemMemoryFooterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ItemMemoryFooterBinding binding = holder.binding;
        binding.footerProgress.setVisibility(mode == Mode.LOADING ? View.VISIBLE : View.GONE);
        binding.footerMessage.setVisibility(mode == Mode.ERROR ? View.VISIBLE : View.GONE);
        binding.footerMessage.setText(message);
        boolean hasAction = mode == Mode.ERROR || mode == Mode.LOAD_MORE;
        binding.footerAction.setVisibility(hasAction ? View.VISIBLE : View.GONE);
        binding.footerAction.setText(mode == Mode.ERROR ? R.string.state_retry : R.string.memory_list_load_more);
        binding.footerAction.setOnClickListener(hasAction
                ? button -> (mode == Mode.ERROR ? onRetry : onLoadMore).run()
                : null);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ItemMemoryFooterBinding binding;

        Holder(ItemMemoryFooterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
