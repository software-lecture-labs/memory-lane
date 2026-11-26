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
import com.memorylane.feature.memories.data.MemoryEditRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Edit form of one memory (docs/api-contract.md, "Eşzamanlı güncelleme: ETag ve If-Match").
 *
 * <ul>
 *   <li>The form opens with a detail GET: its fields fill the form and become the starting
 *       snapshot ({@link #hasUnsavedChanges()}), its ETag is kept for the PUT's If-Match. The list
 *       is never the source of the tag.</li>
 *   <li>Emotion and location have no control on this screen; their current values are sent back
 *       unchanged, because a PUT clears every optional field it leaves out.</li>
 *   <li>412: the draft is kept and nothing is resent. "Son Halini Yükle" reads the latest version
 *       and its tag; the latest version is shown next to the draft and the user either takes it
 *       over or keeps the draft. Only a new tap on save writes, with the new tag.</li>
 *   <li>404 (missing or foreign memory) becomes NOT_FOUND; 401 SIGNED_OUT, the session layer
 *       has already ended the session; any other refusal is shown with its message.</li>
 * </ul>
 */
public final class MemoryEditViewModel extends ViewModel {
    private static final int UNAUTHORIZED = 401;
    private static final int NOT_FOUND = 404;
    private static final int PRECONDITION_FAILED = 412;

    private final MemoryDetailRepository details;
    private final MemoryEditRepository editor;
    private final String memoryId;
    private final Clock clock;

    private final MutableLiveData<MemoryEditUiState> state =
            new MutableLiveData<>(MemoryEditUiState.of(MemoryEditUiState.Status.LOADING));
    private final MutableLiveData<Boolean> saved = new MutableLiveData<>(false);
    /** Raised whenever the field texts are replaced from the server, so the screen rewrites its inputs. */
    private final MutableLiveData<Integer> formVersion = new MutableLiveData<>(0);

    // Field texts live here so a recreated view gets them back (as on the create screen).
    private String title = "";
    private String description = "";
    /** The server's writable fields the form started from (or was last reconciled with). */
    @Nullable private MemoryApi.Draft base;
    @Nullable private String etag;
    @Nullable private String emotion;
    @Nullable private MemoryApi.Location location;

    @Nullable private String session;
    private boolean started;
    private int generation;
    @Nullable private Cancellable request;

    public MemoryEditViewModel(@NonNull MemoryDetailRepository details, @NonNull MemoryEditRepository editor,
                               @NonNull String memoryId, @NonNull Clock clock) {
        this.details = Objects.requireNonNull(details, "details");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.memoryId = Objects.requireNonNull(memoryId, "memoryId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static <R extends MemoryDetailRepository & MemoryEditRepository> ViewModelProvider.Factory factory(
            R repository, String memoryId) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(MemoryEditViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
                }
                return modelClass.cast(new MemoryEditViewModel(repository, repository, memoryId,
                        Clock.systemDefaultZone()));
            }
        };
    }

    public LiveData<MemoryEditUiState> getState() { return state; }

    /** True once the memory was saved, until {@link #consumeSaved()}. */
    public LiveData<Boolean> saved() { return saved; }

    public void consumeSaved() { saved.setValue(false); }

    public LiveData<Integer> formVersion() { return formVersion; }

    String title() { return title; }

    String description() { return description; }

    /** Today in the device's time zone; the picker offers no later day. */
    LocalDate today() { return LocalDate.now(clock); }

    /**
     * Follows the current session. The same session again (a recreated view) keeps the form;
     * a new session loads again; null is SIGNED_OUT without a request.
     */
    public void start(@Nullable String authorization) {
        if (started && Objects.equals(session, authorization)) return;
        started = true;
        session = authorization;
        if (authorization == null) {
            signOut();
        } else {
            load();
        }
    }

    /** Loads again after LOAD_ERROR. */
    public void retryLoad() {
        if (session != null && current().getStatus() == MemoryEditUiState.Status.LOAD_ERROR) load();
    }

    public void onTitleChanged(String text) {
        title = text;
        MemoryEditUiState.Builder next = edited();
        next.titleError = MemoryCreateUiState.TitleError.NONE;
        publish(next);
    }

    public void onDescriptionChanged(String text) {
        description = text;
        MemoryEditUiState.Builder next = edited();
        next.descriptionError = MemoryCreateUiState.DescriptionError.NONE;
        publish(next);
    }

    public void onDateSelected(LocalDate date) {
        MemoryEditUiState.Builder next = edited();
        next.date = date;
        next.dateError = MemoryCreateUiState.DateError.NONE;
        publish(next);
    }

    /** Selects one of {@link MemoryLabels#CATEGORIES}; anything else is ignored. */
    public void onCategorySelected(String code) {
        if (!MemoryLabels.contains(MemoryLabels.CATEGORIES, code)) return;
        MemoryEditUiState.Builder next = edited();
        next.category = code;
        publish(next);
    }

    /** True when the form differs from the version it started from. */
    public boolean hasUnsavedChanges() {
        MemoryEditUiState current = current();
        return current.getStatus() == MemoryEditUiState.Status.READY && base != null
                && !draft(current).equals(base);
    }

    /**
     * Validates and sends the form with the kept ETag. Ignored while a save or reload runs, and
     * after a 412 until the latest version was read: an old tag can only fail again.
     */
    public void save() {
        MemoryEditUiState current = current();
        if (current.getStatus() != MemoryEditUiState.Status.READY || current.isSaving() || etag == null
                || current.getConflict() == MemoryEditUiState.Conflict.MODIFIED
                || current.getConflict() == MemoryEditUiState.Conflict.RELOADING) {
            return;
        }
        MemoryEditUiState.Builder next = current.toBuilder();
        next.titleError = MemoryFormRules.titleError(title);
        next.descriptionError = MemoryFormRules.descriptionError(description);
        next.dateError = MemoryFormRules.dateError(current.getDate(), today());
        next.focus = next.titleError != MemoryCreateUiState.TitleError.NONE ? MemoryCreateUiState.Focus.TITLE
                : next.dateError != MemoryCreateUiState.DateError.NONE ? MemoryCreateUiState.Focus.DATE
                : next.descriptionError != MemoryCreateUiState.DescriptionError.NONE
                        ? MemoryCreateUiState.Focus.DESCRIPTION : MemoryCreateUiState.Focus.NONE;
        next.saveError = null;
        if (next.focus != MemoryCreateUiState.Focus.NONE) {
            publish(next);
            return;
        }
        // Saving after reviewing the latest version is the user's explicit decision.
        next.conflict = MemoryEditUiState.Conflict.NONE;
        next.latest = null;
        next.saving = true;
        publish(next);
        MemoryApi.Draft draft = draft(current);
        int id = ++generation;
        request = editor.updateMemory(session, memoryId, etag, draft, result -> {
            if (id != generation) return;
            request = null;
            onSaved(result);
        });
    }

    /** After a 412: reads the latest version and its tag; the draft stays as it is. */
    public void reloadLatest() {
        MemoryEditUiState current = current();
        if (current.getStatus() != MemoryEditUiState.Status.READY || current.isSaving()) return;
        MemoryEditUiState.Builder next = current.toBuilder();
        next.conflict = MemoryEditUiState.Conflict.RELOADING;
        next.saving = true;
        next.saveError = null;
        publish(next);
        requestDetail(this::onLatestLoaded);
    }

    /** During REVIEW: replaces the draft with the latest version. */
    public void useLatest() {
        MemoryEditUiState current = current();
        MemoryApi.Detail latest = current.getLatest();
        if (current.getConflict() != MemoryEditUiState.Conflict.REVIEW || latest == null) return;
        MemoryEditUiState.Builder next = current.toBuilder();
        fillForm(next, latest);
        next.conflict = MemoryEditUiState.Conflict.NONE;
        next.latest = null;
        publish(next);
    }

    /** During REVIEW: keeps the draft; saving it later uses the latest tag. */
    public void keepDraft() {
        if (current().getConflict() != MemoryEditUiState.Conflict.REVIEW) return;
        MemoryEditUiState.Builder next = current().toBuilder();
        next.conflict = MemoryEditUiState.Conflict.NONE;
        next.latest = null;
        publish(next);
    }

    private void load() {
        state.setValue(MemoryEditUiState.of(MemoryEditUiState.Status.LOADING));
        requestDetail(this::onLoaded);
    }

    private void requestDetail(ResultHandler handler) {
        cancel();
        int id = ++generation;
        request = details.getMemoryDetail(session, memoryId, result -> {
            if (id != generation) return;
            request = null;
            handler.handle(result);
        });
    }

    private interface ResultHandler {
        void handle(ApiResult<Tagged<MemoryApi.Detail>> result);
    }

    private void onLoaded(ApiResult<Tagged<MemoryApi.Detail>> result) {
        ApiError error = failure(result);
        if (error != null) {
            if (!endedBy(error)) state.setValue(MemoryEditUiState.loadError(error));
            return;
        }
        Tagged<MemoryApi.Detail> tagged = Objects.requireNonNull(result.getData());
        keepServerVersion(tagged);
        MemoryEditUiState.Builder ready = new MemoryEditUiState.Builder(MemoryEditUiState.Status.READY);
        fillForm(ready, tagged.value());
        publish(ready);
    }

    private void onLatestLoaded(ApiResult<Tagged<MemoryApi.Detail>> result) {
        ApiError error = failure(result);
        if (error != null) {
            if (endedBy(error)) return;
            MemoryEditUiState.Builder next = current().toBuilder();
            next.conflict = MemoryEditUiState.Conflict.MODIFIED;
            next.saving = false;
            next.saveError = error;
            publish(next);
            return;
        }
        Tagged<MemoryApi.Detail> tagged = Objects.requireNonNull(result.getData());
        keepServerVersion(tagged);
        MemoryEditUiState.Builder next = current().toBuilder();
        next.conflict = MemoryEditUiState.Conflict.REVIEW;
        next.latest = tagged.value();
        next.saving = false;
        publish(next);
    }

    private void onSaved(ApiResult<MemoryApi.Detail> result) {
        if (result.isSuccess() && result.getData() != null) {
            // The answer carries no ETag; the screen closes and the detail reads the new version.
            base = draft(current());
            MemoryEditUiState.Builder next = current().toBuilder();
            next.saving = false;
            publish(next);
            saved.setValue(true);
            return;
        }
        ApiError error = result.isSuccess() ? ApiError.parse()
                : result.getError() != null ? result.getError() : ApiError.unexpected();
        if (endedBy(error)) return;
        MemoryEditUiState.Builder next = current().toBuilder();
        next.saving = false;
        if (error.getHttpStatus() == PRECONDITION_FAILED) {
            next.conflict = MemoryEditUiState.Conflict.MODIFIED;
        } else {
            next.saveError = error;
        }
        publish(next);
    }

    /** The answer's error; a success without a valid detail is a PARSE error. Null for a usable detail. */
    @Nullable
    private static ApiError failure(ApiResult<Tagged<MemoryApi.Detail>> result) {
        if (!result.isSuccess()) return result.getError() != null ? result.getError() : ApiError.unexpected();
        Tagged<MemoryApi.Detail> tagged = result.getData();
        return tagged == null || MemoryDetailViewModel.validDate(tagged.value()) == null ? ApiError.parse() : null;
    }

    /** 404 and 401 end the editing for good; true if one of them was handled. */
    private boolean endedBy(ApiError error) {
        if (error.getHttpStatus() == UNAUTHORIZED) {
            signOut();
            return true;
        }
        if (error.getHttpStatus() == NOT_FOUND) {
            state.setValue(MemoryEditUiState.of(MemoryEditUiState.Status.NOT_FOUND));
            return true;
        }
        return false;
    }

    private void signOut() {
        cancel();
        session = null;
        state.setValue(MemoryEditUiState.of(MemoryEditUiState.Status.SIGNED_OUT));
    }

    /** The tag for the next PUT and the values this screen cannot change come from the server. */
    private void keepServerVersion(Tagged<MemoryApi.Detail> tagged) {
        MemoryApi.Detail memory = tagged.value();
        etag = tagged.etag();
        emotion = blankToNull(memory.emotion());
        location = memory.location();
        base = serverDraft(memory);
    }

    private void fillForm(MemoryEditUiState.Builder next, MemoryApi.Detail memory) {
        title = memory.title();
        description = memory.description() == null ? "" : memory.description();
        next.date = MemoryDetailViewModel.validDate(memory);
        next.category = categoryOf(memory);
        next.titleError = MemoryCreateUiState.TitleError.NONE;
        next.descriptionError = MemoryCreateUiState.DescriptionError.NONE;
        next.dateError = MemoryCreateUiState.DateError.NONE;
        next.focus = MemoryCreateUiState.Focus.NONE;
        Integer version = formVersion.getValue();
        formVersion.setValue(version == null ? 1 : version + 1);
    }

    private MemoryApi.Draft draft(MemoryEditUiState current) {
        LocalDate date = current.getDate();
        return new MemoryApi.Draft(MemoryFormRules.strip(title), description,
                date == null ? null : date.toString(), current.getCategory(), emotion, location);
    }

    private MemoryApi.Draft serverDraft(MemoryApi.Detail memory) {
        return new MemoryApi.Draft(memory.title(), memory.description() == null ? "" : memory.description(),
                memory.memoryDate(), categoryOf(memory), emotion, location);
    }

    private static String categoryOf(MemoryApi.Detail memory) {
        String code = memory.category();
        return MemoryLabels.contains(MemoryLabels.CATEGORIES, code) ? code : MemoryLabels.DEFAULT_CATEGORY;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    /** Any edit drops focus requests and the last save error; they were about the old draft. */
    private MemoryEditUiState.Builder edited() {
        MemoryEditUiState.Builder next = current().toBuilder();
        next.focus = MemoryCreateUiState.Focus.NONE;
        next.saveError = null;
        return next;
    }

    private MemoryEditUiState current() { return state.getValue(); }

    private void publish(MemoryEditUiState.Builder next) { state.setValue(next.build()); }

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
