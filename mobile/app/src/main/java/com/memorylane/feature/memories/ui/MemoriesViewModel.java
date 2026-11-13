package com.memorylane.feature.memories.ui;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

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
    final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    private MemoryRepository repository;
    String session;
    LocalDate date;
    String title = "";
    String description = "";
    private MemoryApi.Draft attempted;
    private String key;
    final List<MemoryApi.Memory> rows = new ArrayList<>();
    private int nextPage;
    boolean hasMore;
    boolean loaded;
    ApiError saveError;
    ApiError listError;
    boolean saving;
    boolean loading;
    private Cancellable saveRequest;
    private Cancellable listRequest;

    void attach(MemoryRepository repository, String session) {
        this.repository = repository;
        if (Objects.equals(this.session, session)) return;
        reset();
        this.session = session;
        if (session != null) load(0);
    }

    void save() {
        if (session == null || saving || date == null || title.trim().isEmpty()) return;
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
                publish();
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
            publish();
        });
    }

    void reset() {
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
    }

    private void publish() { changes.setValue(changes.getValue() + 1); }

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
