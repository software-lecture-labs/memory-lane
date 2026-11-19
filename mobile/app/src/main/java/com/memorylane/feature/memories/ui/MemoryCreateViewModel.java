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
import com.memorylane.feature.memories.data.MemoryCreateRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * New memory form. Field rules match the server's MemoryInput (docs/api-contract.md "Anı
 * alanları"): title required, at most 100 Unicode characters after trimming; description at
 * most 3000; date required and not in the future. The client only gives early feedback; the
 * server stays the authority.
 *
 * <p>Retrying the same draft reuses its idempotency key, so a save whose answer was lost (e.g.
 * a dropped connection) cannot create the memory twice. Changing the draft gets a new key.
 */
public final class MemoryCreateViewModel extends ViewModel {
    static final int TITLE_MAX_LENGTH = 100;
    static final int DESCRIPTION_MAX_LENGTH = 3000;
    private static final int UNAUTHORIZED = 401;

    private final MemoryCreateRepository repository;
    private final Clock clock;

    private final MutableLiveData<MemoryCreateUiState> state =
            new MutableLiveData<>(MemoryCreateUiState.initial());
    private final MutableLiveData<Boolean> saved = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> sessionExpired = new MutableLiveData<>(false);

    // Field texts live here so a recreated view gets them back; the inputs do not save their own
    // text, so restoring cannot look like a user edit that clears the errors.
    private String title = "";
    private String description = "";

    @Nullable private MemoryApi.Draft attempted;
    @Nullable private String idempotencyKey;
    @Nullable private Cancellable request;

    public MemoryCreateViewModel(@NonNull MemoryCreateRepository repository, @NonNull Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static ViewModelProvider.Factory factory(MemoryCreateRepository repository) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(MemoryCreateViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
                }
                return modelClass.cast(new MemoryCreateViewModel(repository, Clock.systemDefaultZone()));
            }
        };
    }

    public LiveData<MemoryCreateUiState> getState() { return state; }

    /** True once the memory was created, until {@link #consumeSaved()}. */
    public LiveData<Boolean> saved() { return saved; }

    public void consumeSaved() { saved.setValue(false); }

    /** True once the save was rejected with 401 or there is no session, until consumed. */
    public LiveData<Boolean> sessionExpired() { return sessionExpired; }

    public void consumeSessionExpired() { sessionExpired.setValue(false); }

    String title() { return title; }

    String description() { return description; }

    /** Today in the device's time zone; the picker does not offer later days. */
    LocalDate today() { return LocalDate.now(clock); }

    public void onTitleChanged(String text) {
        title = text;
        MemoryCreateUiState current = current();
        update(MemoryCreateUiState.TitleError.NONE, current.getDescriptionError(),
                current.getDateError(), current.getDate(), current.isSaving());
    }

    public void onDescriptionChanged(String text) {
        description = text;
        MemoryCreateUiState current = current();
        update(current.getTitleError(), MemoryCreateUiState.DescriptionError.NONE,
                current.getDateError(), current.getDate(), current.isSaving());
    }

    public void onDateSelected(LocalDate date) {
        MemoryCreateUiState current = current();
        update(current.getTitleError(), current.getDescriptionError(),
                MemoryCreateUiState.DateError.NONE, date, current.isSaving());
    }

    /**
     * Validates and sends the memory. Ignored while a save runs. Without a session the screen is
     * told to sign in again instead of sending a request that can only fail.
     */
    public void save(@Nullable String authorization) {
        MemoryCreateUiState current = current();
        if (current.isSaving()) return;

        LocalDate date = current.getDate();
        MemoryCreateUiState.TitleError titleError = titleError(title);
        MemoryCreateUiState.DescriptionError descriptionError = descriptionError(description);
        MemoryCreateUiState.DateError dateError = date == null ? MemoryCreateUiState.DateError.REQUIRED
                : date.isAfter(today()) ? MemoryCreateUiState.DateError.FUTURE
                : MemoryCreateUiState.DateError.NONE;
        MemoryCreateUiState.Focus focus = titleError != MemoryCreateUiState.TitleError.NONE
                ? MemoryCreateUiState.Focus.TITLE
                : dateError != MemoryCreateUiState.DateError.NONE
                        ? MemoryCreateUiState.Focus.DATE
                        : descriptionError != MemoryCreateUiState.DescriptionError.NONE
                                ? MemoryCreateUiState.Focus.DESCRIPTION : MemoryCreateUiState.Focus.NONE;
        if (focus != MemoryCreateUiState.Focus.NONE) {
            state.setValue(new MemoryCreateUiState(titleError, descriptionError, dateError, focus,
                    date, false, null));
            return;
        }
        if (authorization == null) {
            sessionExpired.setValue(true);
            return;
        }

        MemoryApi.Draft draft = new MemoryApi.Draft(strip(title), description, date.toString());
        if (!draft.equals(attempted)) {
            attempted = draft;
            idempotencyKey = UUID.randomUUID().toString();
        }
        update(titleError, descriptionError, dateError, date, true);
        request = repository.create(authorization, idempotencyKey, draft, this::onResult);
    }

    private void onResult(ApiResult<MemoryApi.Memory> result) {
        request = null;
        MemoryCreateUiState current = current();
        if (result.isSuccess() && result.getData() != null) {
            attempted = null;
            idempotencyKey = null;
            update(current.getTitleError(), current.getDescriptionError(), current.getDateError(),
                    current.getDate(), false);
            saved.setValue(true);
            return;
        }
        ApiError error = !result.isSuccess() && result.getError() != null ? result.getError()
                : result.isSuccess() ? ApiError.parse() : ApiError.unexpected();
        if (error.getHttpStatus() == UNAUTHORIZED) {
            update(current.getTitleError(), current.getDescriptionError(), current.getDateError(),
                    current.getDate(), false);
            sessionExpired.setValue(true);
            return;
        }
        state.setValue(new MemoryCreateUiState(current.getTitleError(), current.getDescriptionError(),
                current.getDateError(), MemoryCreateUiState.Focus.NONE, current.getDate(), false, error));
    }

    /** Any edit drops focus requests and the last server error; it is about the old draft. */
    private void update(MemoryCreateUiState.TitleError titleError,
                        MemoryCreateUiState.DescriptionError descriptionError,
                        MemoryCreateUiState.DateError dateError, @Nullable LocalDate date, boolean saving) {
        state.setValue(new MemoryCreateUiState(titleError, descriptionError, dateError,
                MemoryCreateUiState.Focus.NONE, date, saving, null));
    }

    private MemoryCreateUiState current() { return state.getValue(); }

    /**
     * Removes leading and trailing whitespace as the server's {@code String.strip()} does,
     * Unicode spaces included; {@code strip()} itself needs API 33.
     */
    static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && Character.isWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private static MemoryCreateUiState.TitleError titleError(String title) {
        String trimmed = strip(title);
        if (trimmed.isEmpty()) return MemoryCreateUiState.TitleError.REQUIRED;
        return trimmed.codePointCount(0, trimmed.length()) > TITLE_MAX_LENGTH
                ? MemoryCreateUiState.TitleError.TOO_LONG : MemoryCreateUiState.TitleError.NONE;
    }

    private static MemoryCreateUiState.DescriptionError descriptionError(String description) {
        return description.codePointCount(0, description.length()) > DESCRIPTION_MAX_LENGTH
                ? MemoryCreateUiState.DescriptionError.TOO_LONG : MemoryCreateUiState.DescriptionError.NONE;
    }

    @Override
    protected void onCleared() {
        if (request != null) request.cancel();
    }
}
