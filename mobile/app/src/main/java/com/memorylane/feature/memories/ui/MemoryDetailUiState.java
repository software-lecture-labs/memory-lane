package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.memories.data.MemoryApi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable state of the memory detail screen; the fragment draws it in one render call. */
public final class MemoryDetailUiState {
    public enum Status {
        /** No session; the session layer is already taking the user to the login screen. */
        SIGNED_OUT,
        LOADING,
        CONTENT,
        /** 404: the memory does not exist or belongs to another account (the API does not say which). */
        NOT_FOUND,
        /** Connection, server or unreadable answer; the screen offers "Tekrar dene". */
        ERROR
    }

    private final Status status;
    @Nullable private final MemoryApi.Detail memory;
    @Nullable private final LocalDate date;
    @Nullable private final ApiError error;

    private MemoryDetailUiState(Status status, @Nullable MemoryApi.Detail memory, @Nullable LocalDate date,
                                @Nullable ApiError error) {
        this.status = status;
        this.memory = memory;
        this.date = date;
        this.error = error;
    }

    static MemoryDetailUiState signedOut() { return new MemoryDetailUiState(Status.SIGNED_OUT, null, null, null); }

    static MemoryDetailUiState loading() { return new MemoryDetailUiState(Status.LOADING, null, null, null); }

    static MemoryDetailUiState notFound() { return new MemoryDetailUiState(Status.NOT_FOUND, null, null, null); }

    static MemoryDetailUiState error(@NonNull ApiError error) {
        return new MemoryDetailUiState(Status.ERROR, null, null, Objects.requireNonNull(error, "error"));
    }

    /** @param date the memory's {@code memoryDate}, already checked to be a valid YYYY-MM-DD day */
    static MemoryDetailUiState content(@NonNull MemoryApi.Detail memory, @NonNull LocalDate date) {
        return new MemoryDetailUiState(Status.CONTENT, Objects.requireNonNull(memory, "memory"),
                Objects.requireNonNull(date, "date"), null);
    }

    public Status getStatus() { return status; }

    /** The memory; non-null exactly in CONTENT. */
    @Nullable public MemoryApi.Detail getMemory() { return memory; }

    @Nullable public LocalDate getDate() { return date; }

    /** Why loading failed; non-null exactly in ERROR. */
    @Nullable public ApiError getError() { return error; }

    /** CONTENT with at least one photo: the gallery is shown, otherwise it is left out entirely. */
    public boolean hasPhotos() { return memory != null && !memory.photos().isEmpty(); }

    /**
     * Photos in gallery order, {@code order} ascending, so the gallery, its counter and the
     * full-screen viewer always agree even if the server lists them differently. Photos with the
     * same {@code order} keep the server's sequence (the sort is stable). Empty outside CONTENT.
     */
    @NonNull
    public List<MemoryApi.Photo> getPhotos() {
        if (memory == null) return Collections.emptyList();
        List<MemoryApi.Photo> photos = new ArrayList<>(memory.photos());
        photos.sort(Comparator.comparingInt(MemoryApi.Photo::order));
        return photos;
    }

    /** CONTENT with a location: the location card is shown, otherwise it is left out entirely. */
    public boolean hasLocation() { return memory != null && memory.location() != null; }
}
