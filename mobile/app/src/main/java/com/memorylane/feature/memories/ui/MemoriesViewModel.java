package com.memorylane.feature.memories.ui;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryListRepository;
import com.memorylane.feature.memories.data.MemoryRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
 *
 * <p>Temporary (until ML-07 commits 4 and 5): the old screen still reads the form fields and the
 * list mirror fields below and binds through {@link #attach(MemoryRepository, String)}. The
 * mirror fields are derived from the state in one place and are not a second source of truth.
 */
public final class MemoriesViewModel extends ViewModel {
    private static final int UNAUTHORIZED = 401;

    private enum Operation { FIRST_PAGE, REFRESH, NEXT_PAGE }

    private final MutableLiveData<MemoryListUiState> state =
            new MutableLiveData<>(MemoryListUiState.signedOut());
    private final MutableLiveData<Boolean> sessionExpired = new MutableLiveData<>(false);

    private MemoryListRepository listRepository;
    @Nullable private MemoryRepository saveRepository;

    private int nextPage;
    private int generation;
    @Nullable private Cancellable listRequest;
    @Nullable private Operation running;
    @Nullable private Operation failed;

    // --- Temporary fields read by the old MemoriesFragment; removed in commits 4 and 5. ---
    final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    /** Authorization header value of the current session; null when signed out. */
    String session;
    /** Every memory loaded so far, in server order; also the input of the row mapper. */
    final List<MemoryApi.Memory> rows = new ArrayList<>();
    boolean hasMore;
    boolean loaded;
    boolean loading;
    ApiError listError;
    LocalDate date;
    String title = "";
    String description = "";
    ApiError saveError;
    boolean saving;
    private MemoryApi.Draft attempted;
    private String key;
    private Cancellable saveRequest;

    public LiveData<MemoryListUiState> getState() { return state; }

    /** True once a request was rejected with 401, until {@link #consumeSessionExpired()}. */
    public LiveData<Boolean> sessionExpired() { return sessionExpired; }

    public void consumeSessionExpired() { sessionExpired.setValue(false); }

    /** Old screen binding: the remote repository serves both the list and saving. */
    void attach(MemoryRepository repository, @Nullable String authorization) {
        attach(repository, repository, authorization);
    }

    /**
     * Binds the repositories and the current session. Same session: nothing happens, so a
     * recreated view keeps the loaded list. New session: everything restarts. No session:
     * SIGNED_OUT.
     */
    void attach(MemoryListRepository listRepository, @Nullable MemoryRepository saveRepository,
                @Nullable String authorization) {
        this.listRepository = Objects.requireNonNull(listRepository, "listRepository");
        this.saveRepository = saveRepository;
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

    /** Old screen: its refresh button calls load(0). */
    void load(int page) { refresh(); }

    /** Old screen: its "more" button. */
    void more() { loadMore(); }

    /** Signs out locally: stops all requests and shows SIGNED_OUT. */
    void reset() {
        clear();
        session = null;
        publish(MemoryListUiState.signedOut());
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
        // Skipped if a repository answered synchronously and the refresh is already over.
        if (operation == Operation.REFRESH && running == Operation.REFRESH && id == generation) {
            // A refresh keeps the state (rows stay); only the old screen's mirror shows it.
            loading = true;
            publishChanges();
        }
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
        if (operation == Operation.NEXT_PAGE) merged.addAll(rows);
        merged.addAll(body.content());
        List<MemoryListItem> items;
        try {
            items = MemoryListMapper.toItems(merged);
        } catch (IllegalArgumentException contractViolation) {
            onListFailure(operation, ApiError.parse());
            return;
        }
        failed = null;
        rows.clear();
        rows.addAll(merged);
        nextPage = page + 1;
        publish(items.isEmpty()
                ? MemoryListUiState.empty()
                : MemoryListUiState.content(items, body.hasNext()));
    }

    private void onListFailure(Operation operation, ApiError error) {
        if (error.getHttpStatus() == UNAUTHORIZED) {
            expireSession(error);
            return;
        }
        failed = operation;
        MemoryListUiState current = current();
        publish(operation != Operation.FIRST_PAGE && current.getStatus() == MemoryListUiState.Status.CONTENT
                ? current.withLoadMoreError(error)
                : MemoryListUiState.error(error));
    }

    private void expireSession(ApiError error) {
        reset();
        // The old screen clears the stored token when it sees a 401 in listError.
        listError = error;
        sessionExpired.setValue(true);
        publishChanges();
    }

    /** Temporary save path for the old screen; replaced by the create screen in commit 5. */
    void save() {
        if (session == null || saveRepository == null || saving || date == null || title.trim().isEmpty()) {
            return;
        }
        var draft = new MemoryApi.Draft(title.trim(), description, date.toString());
        if (!draft.equals(attempted)) {
            attempted = draft;
            key = UUID.randomUUID().toString();
        }
        saving = true;
        saveError = null;
        publishChanges();
        saveRequest = saveRepository.save(session, key, draft, result -> {
            saving = false;
            saveRequest = null;
            if (result.isSuccess() && result.getData() != null) {
                title = "";
                description = "";
                date = null;
                attempted = null;
                key = null;
                publishChanges();
                refresh();
                return;
            }
            ApiError error = result.isSuccess() ? ApiError.parse() : result.getError();
            if (error != null && error.getHttpStatus() == UNAUTHORIZED) {
                expireSession(error);
                return;
            }
            saveError = error;
            publishChanges();
        });
    }

    private MemoryListUiState current() { return state.getValue(); }

    private void publish(MemoryListUiState next) {
        state.setValue(next);
        // Mirror for the old screen, derived from the single state above.
        hasMore = next.hasMore();
        loaded = next.getStatus() == MemoryListUiState.Status.CONTENT
                || next.getStatus() == MemoryListUiState.Status.EMPTY;
        loading = next.getStatus() == MemoryListUiState.Status.LOADING || next.isLoadingMore()
                || running == Operation.REFRESH;
        listError = next.getError();
        publishChanges();
    }

    private void publishChanges() { changes.setValue(changes.getValue() + 1); }

    /** Stops every request and forgets the list and the form. */
    private void clear() {
        cancelList();
        if (saveRequest != null) saveRequest.cancel();
        saveRequest = null;
        generation++;
        rows.clear();
        nextPage = 0;
        failed = null;
        title = "";
        description = "";
        date = null;
        attempted = null;
        key = null;
        saving = false;
        saveError = null;
    }

    private void cancelList() {
        if (listRequest != null) listRequest.cancel();
        listRequest = null;
        running = null;
    }

    @Override
    protected void onCleared() {
        clear();
    }
}
