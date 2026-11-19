package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryListRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Memory list state for the signed-in user, exposed as {@link #getState()}.
 *
 * <p>Request rules:
 * <ul>
 *   <li>Only one list request runs at a time. A refresh or a session change cancels whatever is
 *       running; "load more" is ignored while any list request runs.</li>
 *   <li>Every request carries a generation number, so an answer that arrives after it was
 *       superseded is dropped even if cancelling came too late.</li>
 *   <li>A refresh keeps the rows on screen until the new first page arrives.</li>
 *   <li>A 401 from any request ends the session: requests stop, rows are dropped, the state
 *       becomes SIGNED_OUT and {@link #sessionExpired()} tells the screen to clear the token.</li>
 * </ul>
 */
public final class MemoriesViewModel extends ViewModel {
    private static final int UNAUTHORIZED = 401;

    private enum Operation { FIRST_PAGE, REFRESH, NEXT_PAGE }

    private final MemoryListRepository listRepository;

    private final MutableLiveData<MemoryListUiState> state =
            new MutableLiveData<>(MemoryListUiState.signedOut());
    private final MutableLiveData<Boolean> sessionExpired = new MutableLiveData<>(false);

    /** Authorization header value of the current session; null when signed out. */
    @Nullable private String session;
    /** Every memory loaded so far, in server order; the input of the row mapper. */
    private final List<MemoryApi.Memory> loaded = new ArrayList<>();
    private int nextPage;
    private int generation;
    @Nullable private Cancellable listRequest;
    @Nullable private Operation running;
    @Nullable private Operation failed;

    public MemoriesViewModel(@NonNull MemoryListRepository listRepository) {
        this.listRepository = Objects.requireNonNull(listRepository, "listRepository");
    }

    public static ViewModelProvider.Factory factory(MemoryListRepository listRepository) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(MemoriesViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
                }
                return modelClass.cast(new MemoriesViewModel(listRepository));
            }
        };
    }

    public LiveData<MemoryListUiState> getState() { return state; }

    /** True once a request was rejected with 401, until {@link #consumeSessionExpired()}. */
    public LiveData<Boolean> sessionExpired() { return sessionExpired; }

    public void consumeSessionExpired() { sessionExpired.setValue(false); }

    /**
     * Sets the current session. Same session: nothing happens, so a recreated view keeps the
     * loaded list. New session: everything restarts. Null: SIGNED_OUT.
     */
    public void setSession(@Nullable String authorization) {
        if (Objects.equals(session, authorization)) return;
        clear();
        session = authorization;
        if (authorization == null) {
            publish(MemoryListUiState.signedOut());
        } else {
            requestFirstPage();
        }
    }

    /** Loads the first page again. Rows already shown stay visible until it arrives. */
    public void refresh() {
        if (session == null) return;
        MemoryListUiState current = current();
        if (current.getStatus() == MemoryListUiState.Status.CONTENT) {
            if (current.isLoadingMore()) {
                // The refresh cancels the next page, so its progress must not stay on screen.
                publish(MemoryListUiState.content(current.getItems(), current.hasMore()));
            }
            request(Operation.REFRESH, 0);
        } else {
            requestFirstPage();
        }
    }

    /** Loads the next page; ignored on the last page and while any list request runs. */
    public void loadMore() {
        if (running != null || !current().canLoadMore()) return;
        publish(current().withLoadingMore());
        request(Operation.NEXT_PAGE, nextPage);
    }

    /** Repeats what failed: the first load, a refresh or the next page. */
    public void retry() {
        if (failed == Operation.NEXT_PAGE) {
            loadMore();
        } else {
            refresh();
        }
    }

    private void requestFirstPage() {
        publish(MemoryListUiState.loading());
        request(Operation.FIRST_PAGE, 0);
    }

    private void request(Operation operation, int page) {
        cancelList();
        int id = ++generation;
        running = operation;
        listRequest = listRepository.list(session, page, result -> {
            if (id != generation) return;
            listRequest = null;
            running = null;
            onPage(operation, page, result);
        });
    }

    private void onPage(Operation operation, int page, ApiResult<MemoryApi.Page> result) {
        if (!result.isSuccess()) {
            onListFailure(operation, result.getError() != null ? result.getError() : ApiError.unexpected());
            return;
        }
        MemoryApi.Page body = result.getData();
        if (body == null || body.content() == null) {
            onListFailure(operation, ApiError.parse());
            return;
        }
        List<MemoryApi.Memory> merged = new ArrayList<>();
        if (operation == Operation.NEXT_PAGE) merged.addAll(loaded);
        merged.addAll(body.content());
        List<MemoryListItem> items;
        try {
            items = MemoryListMapper.toItems(merged);
        } catch (IllegalArgumentException contractViolation) {
            onListFailure(operation, ApiError.parse());
            return;
        }
        failed = null;
        loaded.clear();
        loaded.addAll(merged);
        nextPage = page + 1;
        publish(items.isEmpty()
                ? MemoryListUiState.empty()
                : MemoryListUiState.content(items, body.hasNext()));
    }

    private void onListFailure(Operation operation, ApiError error) {
        if (error.getHttpStatus() == UNAUTHORIZED) {
            expireSession();
            return;
        }
        failed = operation;
        MemoryListUiState current = current();
        publish(operation != Operation.FIRST_PAGE && current.getStatus() == MemoryListUiState.Status.CONTENT
                ? current.withLoadMoreError(error)
                : MemoryListUiState.error(error));
    }

    private void expireSession() {
        clear();
        session = null;
        publish(MemoryListUiState.signedOut());
        sessionExpired.setValue(true);
    }

    private MemoryListUiState current() { return state.getValue(); }

    private void publish(MemoryListUiState next) { state.setValue(next); }

    /** Stops the list request and forgets the loaded pages. */
    private void clear() {
        cancelList();
        generation++;
        loaded.clear();
        nextPage = 0;
        failed = null;
    }

    private void cancelList() {
        if (listRequest != null) listRequest.cancel();
        listRequest = null;
        running = null;
    }

    @Override
    protected void onCleared() {
        cancelList();
    }
}
