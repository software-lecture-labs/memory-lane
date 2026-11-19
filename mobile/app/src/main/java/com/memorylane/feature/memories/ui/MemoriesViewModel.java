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
import com.memorylane.feature.memories.ui.MemoriesUiState.Validation;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Holds the memory form and list. The draft lives here, so it survives rotation and a failed
 * request; it is cleared only after a successful save, on logout or when the session expires.
 */
public final class MemoriesViewModel extends ViewModel {
    /** Same limits as the server (MemoryInput): Unicode characters, not UTF-16 units. */
    static final int TITLE_MAX = 100;
    static final int DESCRIPTION_MAX = 3000;

    private final MemoryRepository repository;
    private final Clock clock;
    private String session;
    private LocalDate date;
    private String title = "";
    private String description = "";
    private String category = MemoryChoices.DEFAULT_CATEGORY;
    private String emotion;
    private String latitude = "";
    private String longitude = "";
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

    private final EnumSet<Validation> errors = EnumSet.noneOf(Validation.class);
    private boolean sessionExpired;
    private final MutableLiveData<MemoriesUiState> state;

    MemoriesViewModel(MemoryRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        state = new MutableLiveData<>(snapshot());
    }

    static ViewModelProvider.Factory factory(MemoryRepository repository) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                return modelClass.cast(new MemoriesViewModel(repository, Clock.systemDefaultZone()));
            }
        };
    }

    LiveData<MemoriesUiState> state() { return state; }

    LocalDate today() { return LocalDate.now(clock); }

    void onTitleChanged(String title) {
        this.title = title;
        errors.remove(Validation.TITLE_REQUIRED);
        errors.remove(Validation.TITLE_TOO_LONG);
        publish();
    }

    void onDescriptionChanged(String description) {
        this.description = description;
        errors.remove(Validation.DESCRIPTION_TOO_LONG);
        publish();
    }

    void onDateSelected(LocalDate date) {
        this.date = date;
        errors.remove(Validation.DATE_REQUIRED);
        errors.remove(Validation.DATE_IN_FUTURE);
        publish();
    }

    void onTodaySelected() { onDateSelected(today()); }

    void onCategorySelected(String code) {
        category = MemoryChoices.contains(MemoryChoices.CATEGORIES, code) ? code : MemoryChoices.DEFAULT_CATEGORY;
        publish();
    }

    /** {@code null} clears the optional emotion. */
    void onEmotionSelected(String code) {
        emotion = MemoryChoices.contains(MemoryChoices.EMOTIONS, code) ? code : null;
        publish();
    }

    void onLatitudeChanged(String value) {
        latitude = value;
        clearLocationErrors();
        publish();
    }

    void onLongitudeChanged(String value) {
        longitude = value;
        clearLocationErrors();
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
        // A second tap while the request is in flight is ignored, so one tap makes one request.
        if (session == null || saving) return;
        MemoryApi.Draft draft = validate();
        if (draft == null) {
            publish();
            return;
        }
        // Retrying the same content reuses the key, so a lost response cannot create a second memory.
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
                clearForm();
                load(0);
            } else {
                // The draft is kept untouched so the user can fix it or retry.
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

    /** Returns the request body, or {@code null} after recording why the form cannot be sent. */
    private MemoryApi.Draft validate() {
        errors.clear();
        String cleanTitle = title.trim();
        if (cleanTitle.isEmpty()) errors.add(Validation.TITLE_REQUIRED);
        else if (length(cleanTitle) > TITLE_MAX) errors.add(Validation.TITLE_TOO_LONG);
        if (length(description) > DESCRIPTION_MAX) errors.add(Validation.DESCRIPTION_TOO_LONG);
        if (date == null) errors.add(Validation.DATE_REQUIRED);
        else if (date.isAfter(today())) errors.add(Validation.DATE_IN_FUTURE);

        MemoryApi.Location location = null;
        String lat = latitude.trim();
        String lng = longitude.trim();
        if (lat.isEmpty() != lng.isEmpty()) {
            errors.add(Validation.LOCATION_INCOMPLETE);
        } else if (!lat.isEmpty()) {
            Double parsedLat = coordinate(lat, 90);
            Double parsedLng = coordinate(lng, 180);
            if (parsedLat == null || parsedLng == null) errors.add(Validation.LOCATION_INVALID);
            else location = new MemoryApi.Location(parsedLat, parsedLng);
        }
        if (!errors.isEmpty()) return null;
        return new MemoryApi.Draft(cleanTitle, description, date.toString(), category, emotion, location);
    }

    static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** Accepts a decimal comma as well, since Turkish keyboards may offer one. */
    static Double coordinate(String text, int limit) {
        try {
            double value = Double.parseDouble(text.replace(',', '.'));
            if (!Double.isFinite(value) || Math.abs(value) > limit) return null;
            return value;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private void clearLocationErrors() {
        errors.remove(Validation.LOCATION_INCOMPLETE);
        errors.remove(Validation.LOCATION_INVALID);
    }

    private void clearForm() {
        title = "";
        description = "";
        date = null;
        category = MemoryChoices.DEFAULT_CATEGORY;
        emotion = null;
        latitude = "";
        longitude = "";
        attempted = null;
        key = null;
        errors.clear();
    }

    private void reset() {
        cancelRequests();
        session = null;
        clearForm();
        rows.clear();
        nextPage = 0;
        hasMore = false;
        loaded = false;
        saving = false;
        loading = false;
        saveError = null;
        listError = null;
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
        return new MemoriesUiState(session != null, title, description, date, category, emotion,
                latitude, longitude, rows, saving, loading, loaded, hasMore, saveError, listError,
                errors, sessionExpired);
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
