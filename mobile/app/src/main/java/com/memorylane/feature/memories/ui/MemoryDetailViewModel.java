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
import com.memorylane.core.network.Tagged;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryDetailRepository;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * State of one memory's detail screen, exposed as {@link #getState()}.
 *
 * <ul>
 *   <li>Only one request runs at a time; a retry or a session change cancels the running one, and
 *       its generation number drops an answer that arrives after it was superseded.</li>
 *   <li>404 becomes NOT_FOUND (missing and foreign memories look the same, ADR-0003); 401 becomes
 *       SIGNED_OUT, the session layer has already ended the session and opens the login screen;
 *       any other failure is ERROR with "Tekrar dene".</li>
 *   <li>A detail that breaks the contract (no id, title, valid date or photo list) is not drawn
 *       half-way; it is a PARSE error.</li>
 * </ul>
 */
public final class MemoryDetailViewModel extends ViewModel {
    private static final int UNAUTHORIZED = 401;
    private static final int NOT_FOUND = 404;

    private final MemoryDetailRepository repository;
    private final String memoryId;
    private final MutableLiveData<MemoryDetailUiState> state = new MutableLiveData<>(MemoryDetailUiState.loading());

    @Nullable private String session;
    private boolean started;
    private int generation;
    @Nullable private Cancellable request;

    public MemoryDetailViewModel(@NonNull MemoryDetailRepository repository, @NonNull String memoryId) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.memoryId = Objects.requireNonNull(memoryId, "memoryId");
    }

    public static ViewModelProvider.Factory factory(MemoryDetailRepository repository, String memoryId) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(MemoryDetailViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
                }
                return modelClass.cast(new MemoryDetailViewModel(repository, memoryId));
            }
        };
    }

    public LiveData<MemoryDetailUiState> getState() { return state; }

    /** The memory this screen shows. */
    public String memoryId() { return memoryId; }

    /**
     * Follows the current session. The same session again (a recreated view) keeps what is shown;
     * a new session loads again; null is SIGNED_OUT without a request.
     */
    public void start(@Nullable String authorization) {
        if (started && Objects.equals(session, authorization)) return;
        started = true;
        session = authorization;
        if (authorization == null) {
            cancel();
            state.setValue(MemoryDetailUiState.signedOut());
        } else {
            load();
        }
    }

    /** Loads again after an error. */
    public void retry() {
        if (session != null) load();
    }

    private void load() {
        cancel();
        int id = ++generation;
        state.setValue(MemoryDetailUiState.loading());
        request = repository.getMemoryDetail(session, memoryId, result -> {
            if (id != generation) return;
            request = null;
            onResult(result);
        });
    }

    private void onResult(ApiResult<Tagged<MemoryApi.Detail>> result) {
        if (!result.isSuccess()) {
            ApiError error = result.getError() != null ? result.getError() : ApiError.unexpected();
            if (error.getHttpStatus() == UNAUTHORIZED) {
                session = null;
                state.setValue(MemoryDetailUiState.signedOut());
            } else if (error.getHttpStatus() == NOT_FOUND) {
                state.setValue(MemoryDetailUiState.notFound());
            } else {
                state.setValue(MemoryDetailUiState.error(error));
            }
            return;
        }
        Tagged<MemoryApi.Detail> tagged = result.getData();
        MemoryApi.Detail memory = tagged == null ? null : tagged.value();
        LocalDate date = memory == null ? null : validDate(memory);
        state.setValue(date == null ? MemoryDetailUiState.error(ApiError.parse())
                : MemoryDetailUiState.content(memory, date));
    }

    /** The memory's day if the detail keeps the contract; null if it does not. */
    @Nullable
    private static LocalDate validDate(MemoryApi.Detail memory) {
        if (isBlank(memory.id()) || isBlank(memory.title()) || memory.memoryDate() == null
                || memory.photos() == null) {
            return null;
        }
        for (MemoryApi.Photo photo : memory.photos()) {
            if (photo == null || isBlank(photo.id())) return null;
        }
        try {
            return LocalDate.parse(memory.memoryDate());
        } catch (DateTimeParseException notADay) {
            return null;
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private void cancel() {
        if (request != null) request.cancel();
        request = null;
        generation++;
    }

    @Override
    protected void onCleared() {
        cancel();
    }
}
