package com.memorylane.feature.memories.ui;

import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;

import java.time.LocalDate;

/** What the create screen draws. Holds no field text, only the chosen date and the errors. */
public final class MemoryCreateUiState {
    public enum TitleError { NONE, REQUIRED, TOO_LONG }
    public enum DescriptionError { NONE, TOO_LONG }
    public enum DateError { NONE, REQUIRED, FUTURE }
    /** The first invalid field, in screen order, to move focus to; NONE after any edit. */
    public enum Focus { NONE, TITLE, DATE, DESCRIPTION }

    private final TitleError titleError;
    private final DescriptionError descriptionError;
    private final DateError dateError;
    private final Focus focus;
    @Nullable private final LocalDate date;
    private final boolean saving;
    @Nullable private final ApiError serverError;

    public MemoryCreateUiState(TitleError titleError, DescriptionError descriptionError,
                               DateError dateError, Focus focus, @Nullable LocalDate date,
                               boolean saving, @Nullable ApiError serverError) {
        this.titleError = titleError;
        this.descriptionError = descriptionError;
        this.dateError = dateError;
        this.focus = focus;
        this.date = date;
        this.saving = saving;
        this.serverError = serverError;
    }

    static MemoryCreateUiState initial() {
        return new MemoryCreateUiState(TitleError.NONE, DescriptionError.NONE, DateError.NONE,
                Focus.NONE, null, false, null);
    }

    public TitleError getTitleError() { return titleError; }
    public DescriptionError getDescriptionError() { return descriptionError; }
    public DateError getDateError() { return dateError; }
    public Focus getFocus() { return focus; }

    /** The chosen memory date; null until one is picked. */
    @Nullable public LocalDate getDate() { return date; }

    public boolean isSaving() { return saving; }

    /** A failed save that belongs to no single field (network, 503, server-side validation). */
    @Nullable public ApiError getServerError() { return serverError; }

    public boolean hasFieldErrors() {
        return titleError != TitleError.NONE || descriptionError != DescriptionError.NONE
                || dateError != DateError.NONE;
    }
}
