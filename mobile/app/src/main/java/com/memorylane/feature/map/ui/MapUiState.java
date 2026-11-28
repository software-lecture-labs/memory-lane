package com.memorylane.feature.map.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.map.data.MapApi;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable state of the map tab; the fragment draws it in one render call. */
public final class MapUiState {
    public enum Status {
        /** No session; the session layer is already taking the user to the login screen. */
        SIGNED_OUT,
        LOADING,
        /** At least one pin. */
        CONTENT,
        /** The request worked and no memory has a location; not the same as a failed load. */
        EMPTY,
        /** Connection, server or unreadable answer; the screen offers "Tekrar dene". */
        ERROR
    }

    private final Status status;
    private final List<MapApi.Point> points;
    private final boolean truncated;
    @Nullable private final MapApi.Point selected;
    @Nullable private final ApiError error;

    private MapUiState(Status status, List<MapApi.Point> points, boolean truncated,
                       @Nullable MapApi.Point selected, @Nullable ApiError error) {
        this.status = status;
        this.points = points;
        this.truncated = truncated;
        this.selected = selected;
        this.error = error;
    }

    static MapUiState signedOut() { return new MapUiState(Status.SIGNED_OUT, List.of(), false, null, null); }

    static MapUiState loading() { return new MapUiState(Status.LOADING, List.of(), false, null, null); }

    static MapUiState empty() { return new MapUiState(Status.EMPTY, List.of(), false, null, null); }

    static MapUiState error(@NonNull ApiError error) {
        return new MapUiState(Status.ERROR, List.of(), false, null, Objects.requireNonNull(error, "error"));
    }

    /** @param points non-empty, already checked against the contract */
    static MapUiState content(@NonNull List<MapApi.Point> points, boolean truncated, @Nullable MapApi.Point selected) {
        return new MapUiState(Status.CONTENT, Collections.unmodifiableList(points), truncated, selected, null);
    }

    MapUiState withSelected(@Nullable MapApi.Point point) {
        return new MapUiState(status, points, truncated, point, error);
    }

    public Status getStatus() { return status; }

    /** Pins, newest memory first; empty outside CONTENT. */
    @NonNull public List<MapApi.Point> getPoints() { return points; }

    /** More memories matched than the server returns; the screen says the map is not complete. */
    public boolean isTruncated() { return truncated; }

    /** The pin whose preview card is open; null when none is. */
    @Nullable public MapApi.Point getSelected() { return selected; }

    /** Why loading failed; non-null exactly in ERROR. */
    @Nullable public ApiError getError() { return error; }
}
