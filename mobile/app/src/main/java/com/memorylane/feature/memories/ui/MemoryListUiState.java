package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Everything the memory list screen draws. Immutable: every change produces a new state.
 *
 * <p>ERROR is a failed first load and replaces the list. A failed "load more" keeps CONTENT and
 * its rows and carries the error alongside them, so the rows already shown never disappear.
 * EMPTY is only a successful load without memories; a connection error is never shown as empty.
 */
public final class MemoryListUiState {
    public enum Status { SIGNED_OUT, LOADING, CONTENT, EMPTY, ERROR }

    private final Status status;
    private final List<MemoryListItem> items;
    private final boolean hasMore;
    private final boolean loadingMore;
    @Nullable private final ApiError error;

    private MemoryListUiState(Status status, List<MemoryListItem> items, boolean hasMore,
                              boolean loadingMore, @Nullable ApiError error) {
        this.status = status;
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.hasMore = hasMore;
        this.loadingMore = loadingMore;
        this.error = error;
    }

    public static MemoryListUiState signedOut() {
        return new MemoryListUiState(Status.SIGNED_OUT, Collections.emptyList(), false, false, null);
    }

    public static MemoryListUiState loading() {
        return new MemoryListUiState(Status.LOADING, Collections.emptyList(), false, false, null);
    }

    public static MemoryListUiState empty() {
        return new MemoryListUiState(Status.EMPTY, Collections.emptyList(), false, false, null);
    }

    public static MemoryListUiState error(@NonNull ApiError error) {
        return new MemoryListUiState(Status.ERROR, Collections.emptyList(), false, false,
                Objects.requireNonNull(error, "error"));
    }

    /** Rows of a successful load. An empty result is {@link #empty()}, never CONTENT. */
    public static MemoryListUiState content(@NonNull List<MemoryListItem> items, boolean hasMore) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Use empty() for a load without memories");
        }
        return new MemoryListUiState(Status.CONTENT, items, hasMore, false, null);
    }

    /** The next page was requested; rows stay, an earlier load-more error is cleared. */
    public MemoryListUiState withLoadingMore() {
        requireContent();
        return new MemoryListUiState(Status.CONTENT, items, hasMore, true, null);
    }

    /** The next page failed; rows and hasMore stay so the user can try again. */
    public MemoryListUiState withLoadMoreError(@NonNull ApiError error) {
        requireContent();
        return new MemoryListUiState(Status.CONTENT, items, hasMore, false,
                Objects.requireNonNull(error, "error"));
    }

    private void requireContent() {
        if (status != Status.CONTENT) {
            throw new IllegalStateException("Only a list with rows can load more, not " + status);
        }
    }

    public Status getStatus() { return status; }

    /** Read-only rows; empty unless the status is CONTENT. */
    public List<MemoryListItem> getItems() { return items; }

    public boolean hasMore() { return hasMore; }

    public boolean isLoadingMore() { return loadingMore; }

    /** The first-load error for ERROR, or the last load-more error for CONTENT. */
    @Nullable public ApiError getError() { return error; }

    /** The "load more" button is offered only when more exists and no request is running. */
    public boolean canLoadMore() {
        return status == Status.CONTENT && hasMore && !loadingMore;
    }
}
