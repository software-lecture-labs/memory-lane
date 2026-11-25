package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.memories.data.MemoryApi;

import java.time.LocalDate;
import java.util.Objects;

/**
 * What the edit screen draws. Field texts stay in the ViewModel (as on the create screen); this
 * holds the screen status, the chosen date and category, field errors, the save state and the
 * conflict review after a 412.
 */
public final class MemoryEditUiState {
    public enum Status {
        /** No session; the session layer is already taking the user to the login screen. */
        SIGNED_OUT,
        LOADING,
        /** The form is filled from the server and can be edited. */
        READY,
        /** 404 on load or save: the memory is gone or belongs to another account. */
        NOT_FOUND,
        /** The memory could not be loaded; the screen offers "Tekrar dene". */
        LOAD_ERROR
    }

    /** After a 412 the draft is kept and the user decides; nothing is resent automatically. */
    public enum Conflict {
        NONE,
        /** The save was refused because the memory changed elsewhere; offer "Son Halini Yükle". */
        MODIFIED,
        /** The latest version is being read. */
        RELOADING,
        /** The latest version is shown next to the draft: use it or keep the draft. */
        REVIEW
    }

    private final Status status;
    @Nullable private final ApiError loadError;
    @Nullable private final LocalDate date;
    @NonNull private final String category;
    private final MemoryCreateUiState.TitleError titleError;
    private final MemoryCreateUiState.DescriptionError descriptionError;
    private final MemoryCreateUiState.DateError dateError;
    private final MemoryCreateUiState.Focus focus;
    private final boolean saving;
    @Nullable private final ApiError saveError;
    private final Conflict conflict;
    @Nullable private final MemoryApi.Detail latest;

    private MemoryEditUiState(Builder builder) {
        status = builder.status;
        loadError = builder.loadError;
        date = builder.date;
        category = builder.category;
        titleError = builder.titleError;
        descriptionError = builder.descriptionError;
        dateError = builder.dateError;
        focus = builder.focus;
        saving = builder.saving;
        saveError = builder.saveError;
        conflict = builder.conflict;
        latest = builder.latest;
    }

    /** A state with no form yet: LOADING, NOT_FOUND or SIGNED_OUT. */
    static MemoryEditUiState of(Status status) {
        return new Builder(status).build();
    }

    /** LOAD_ERROR with the reason, shown next to "Tekrar dene". */
    static MemoryEditUiState loadError(@NonNull ApiError error) {
        Builder builder = new Builder(Status.LOAD_ERROR);
        builder.loadError = Objects.requireNonNull(error, "error");
        return builder.build();
    }

    /** A copy to change a few fields; the state itself never changes once published. */
    Builder toBuilder() {
        Builder builder = new Builder(status);
        builder.loadError = loadError;
        builder.date = date;
        builder.category = category;
        builder.titleError = titleError;
        builder.descriptionError = descriptionError;
        builder.dateError = dateError;
        builder.focus = focus;
        builder.saving = saving;
        builder.saveError = saveError;
        builder.conflict = conflict;
        builder.latest = latest;
        return builder;
    }

    public Status getStatus() { return status; }

    /** Why loading failed; non-null exactly in LOAD_ERROR. */
    @Nullable public ApiError getLoadError() { return loadError; }

    @Nullable public LocalDate getDate() { return date; }

    /** API category code of the draft, e.g. {@code TRAVEL}; OTHER when the memory had none. */
    @NonNull public String getCategory() { return category; }

    public MemoryCreateUiState.TitleError getTitleError() { return titleError; }

    public MemoryCreateUiState.DescriptionError getDescriptionError() { return descriptionError; }

    public MemoryCreateUiState.DateError getDateError() { return dateError; }

    /** The first invalid field to move focus to after a refused save; NONE after any edit. */
    public MemoryCreateUiState.Focus getFocus() { return focus; }

    /** A save or a reload of the latest version is running; the form and buttons are locked. */
    public boolean isSaving() { return saving; }

    /** A refused save that is not a field error, a conflict or a missing memory (400, 428, network…). */
    @Nullable public ApiError getSaveError() { return saveError; }

    public Conflict getConflict() { return conflict; }

    /** The server's latest version during REVIEW; null otherwise. */
    @Nullable public MemoryApi.Detail getLatest() { return latest; }

    /** Mutable draft of the next state; defaults are a fresh form without errors or conflict. */
    static final class Builder {
        Status status;
        @Nullable ApiError loadError;
        @Nullable LocalDate date;
        @NonNull String category = MemoryEditViewModel.DEFAULT_CATEGORY;
        MemoryCreateUiState.TitleError titleError = MemoryCreateUiState.TitleError.NONE;
        MemoryCreateUiState.DescriptionError descriptionError = MemoryCreateUiState.DescriptionError.NONE;
        MemoryCreateUiState.DateError dateError = MemoryCreateUiState.DateError.NONE;
        MemoryCreateUiState.Focus focus = MemoryCreateUiState.Focus.NONE;
        boolean saving;
        @Nullable ApiError saveError;
        Conflict conflict = Conflict.NONE;
        @Nullable MemoryApi.Detail latest;

        Builder(Status status) {
            this.status = status;
        }

        MemoryEditUiState build() {
            return new MemoryEditUiState(this);
        }
    }
}
