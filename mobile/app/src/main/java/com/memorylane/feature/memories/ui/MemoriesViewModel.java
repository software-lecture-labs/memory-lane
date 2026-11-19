package com.memorylane.feature.memories.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.Cancellable;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class MemoriesViewModel extends ViewModel {

    private final MemoryRepository repository;
    private String session;
    private LocalDate date;
    private String title = "";
    private String description = "";
    private MemoryApi.Draft attempted;
    private String key;
    private final List<MemoryApi.Memory> rows = new ArrayList<>();
    private int nextPage;
    private boolean hasMore;
    private boolean loaded;
    private ApiError saveError;
    private ApiError listError;
    private boolean saving;
    private boolean loading;
    private Cancellable saveRequest;
    private Cancellable listRequest;

    private MemoriesUiState.Validation validation = MemoriesUiState.Validation.NONE;
    private boolean sessionExpired;
    private final MutableLiveData<MemoriesUiState> state = new MutableLiveData<>(snapshot());

    MemoriesViewModel(MemoryRepository repository) {
        this.repository = repository;
    }

    static ViewModelProvider.Factory factory(MemoryRepository repository) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                return modelClass.cast(new MemoriesViewModel(repository));
            }
        };
    }

    LiveData<MemoriesUiState> state() { return state; }

    void onTitleChanged(String title) {
        this.title = title;
        if (validation == MemoriesUiState.Validation.TITLE_REQUIRED) {
            validation = MemoriesUiState.Validation.NONE;
        }
        publish();
    }

    void onDescriptionChanged(String description) {
        this.description = description;
        publish();
    }

    void onDateSelected(LocalDate date) {
        this.date = date;
        if (validation == MemoriesUiState.Validation.DATE_REQUIRED) {
            validation = MemoriesUiState.Validation.NONE;
        }
        publish();
    }

    void attach(String session) {
        if (Objects.equals(this.session, session)) return;
        reset();
        this.session = session;
        if (session != null) load(0);
        else publish();
    }

    void save() {
        if (session == null || saving) return;
        validation = title.trim().isEmpty() ? MemoriesUiState.Validation.TITLE_REQUIRED
                : date == null ? MemoriesUiState.Validation.DATE_REQUIRED : MemoriesUiState.Validation.NONE;
        if (validation != MemoriesUiState.Validation.NONE) {
            publish();
            return;
        }
        var draft = new MemoryApi.Draft(title.trim(), description, date.toString());
        if (!draft.equals(attempted)) {
            attempted = draft;
            key = UUID.randomUUID().toString();
        }
        saving = true;
        saveError = null;
        publish();
        saveRequest = repository.save(session, key, draft, result -> {
            saving = false;
            if (result.isSuccess() && result.getData() != null) {
                title = "";
                description = "";
                date = null;
                attempted = null;
                key = null;
                load(0);
            } else {
                saveError = result.isSuccess() ? ApiError.parse() : result.getError();
                if (!expireIfUnauthorized(saveError)) publish();
            }
        });
    }

    void more() {
        if (hasMore && !loading) load(nextPage);
    }

    void load(int page) {
        if (session == null) return;
        if (listRequest != null) listRequest.cancel();
        loading = true;
        listError = null;
        publish();
        listRequest = repository.list(session, page, result -> {
            loading = false;
            if (!result.isSuccess()) {
                listError = result.getError();
            } else if (result.getData() == null || result.getData().content() == null) {
                listError = ApiError.parse();
            } else {
                if (page == 0) rows.clear();
                rows.addAll(result.getData().content());
                loaded = true;
                hasMore = result.getData().hasNext();
                nextPage = page + 1;
            }
            if (!expireIfUnauthorized(listError)) publish();
        });
    }

    void onLogout() {
        reset();
        publish();
    }

    private void reset() {
        cancelRequests();
        session = null;
        title = "";
        description = "";
        date = null;
        attempted = null;
        key = null;
        rows.clear();
        nextPage = 0;
        hasMore = false;
        loaded = false;
        saving = false;
        loading = false;
        saveError = null;
        listError = null;
        validation = MemoriesUiState.Validation.NONE;
        sessionExpired = false;
    }

    private boolean expireIfUnauthorized(ApiError error) {
        if (error == null || error.getHttpStatus() != 401) return false;
        reset();
        sessionExpired = true;
        publish();
        return true;
    }

    private MemoriesUiState snapshot() {
        return new MemoriesUiState(session != null, title, description, date, rows, saving,
                loading, loaded, hasMore, saveError, listError, validation, sessionExpired);
    }

    private void publish() { state.setValue(snapshot()); }

    @Override
    protected void onCleared() {
        cancelRequests();
    }

    private void cancelRequests() {
        if (saveRequest != null) saveRequest.cancel();
        if (listRequest != null) listRequest.cancel();
        saveRequest = null;
        listRequest = null;
    }
}
