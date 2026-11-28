package com.memorylane.feature.map.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.feature.map.data.MapApi;
import com.memorylane.feature.map.data.MapRepository;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;

/**
 * State of the map tab, exposed as {@link #getState()}. Follows the same rules as the memory
 * detail screen: one request at a time, a superseded answer is dropped, 401 becomes SIGNED_OUT,
 * and an answer that breaks the contract is a PARSE error instead of a half-drawn map.
 */
public final class MapViewModel extends ViewModel {
    private static final int UNAUTHORIZED = 401;

    private final MapRepository repository;
    private final MutableLiveData<MapUiState> state = new MutableLiveData<>(MapUiState.loading());

    @Nullable private String session;
    private boolean started;
    private int generation;
    @Nullable private Cancellable request;

    public MapViewModel(@NonNull MapRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public static ViewModelProvider.Factory factory(MapRepository repository) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(MapViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
                }
                return modelClass.cast(new MapViewModel(repository));
            }
        };
    }

    public LiveData<MapUiState> getState() { return state; }

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
            state.setValue(MapUiState.signedOut());
        } else {
            request(true);
        }
    }

    /** Loads again after an error. */
    public void retry() {
        if (session != null) request(true);
    }

    /**
     * Reads the points again when the tab is shown again, so memories added, moved or deleted
     * meanwhile are on the map. The pins shown stay until the new answer arrives.
     */
    public void refresh() {
        if (session == null) return;
        request(current().getStatus() != MapUiState.Status.CONTENT);
    }

    /** Opens the preview card of the pin {@code memoryId}; an unknown id closes it. */
    public void select(@Nullable String memoryId) {
        MapUiState shown = current();
        if (shown.getStatus() != MapUiState.Status.CONTENT) return;
        state.setValue(shown.withSelected(find(shown.getPoints(), memoryId)));
    }

    public void clearSelection() {
        select(null);
    }

    private void request(boolean showLoading) {
        cancel();
        int id = ++generation;
        if (showLoading) state.setValue(MapUiState.loading());
        request = repository.points(session, result -> {
            if (id != generation) return;
            request = null;
            onResult(result);
        });
    }

    private void onResult(ApiResult<MapApi.Points> result) {
        if (!result.isSuccess()) {
            ApiError error = result.getError() != null ? result.getError() : ApiError.unexpected();
            if (error.getHttpStatus() == UNAUTHORIZED) {
                session = null;
                state.setValue(MapUiState.signedOut());
            } else {
                state.setValue(MapUiState.error(error));
            }
            return;
        }
        MapApi.Points answer = result.getData();
        if (!keepsContract(answer)) {
            state.setValue(MapUiState.error(ApiError.parse()));
        } else if (answer.points().isEmpty()) {
            state.setValue(MapUiState.empty());
        } else {
            // A refresh keeps the open card if that memory is still on the map.
            MapApi.Point selected = current().getSelected();
            state.setValue(MapUiState.content(answer.points(), answer.truncated(),
                    find(answer.points(), selected == null ? null : selected.id())));
        }
    }

    static boolean keepsContract(@Nullable MapApi.Points answer) {
        if (answer == null || answer.points() == null) return false;
        for (MapApi.Point point : answer.points()) {
            if (point == null || isBlank(point.id()) || isBlank(point.title()) || !isDay(point.memoryDate())
                    || !inRange(point.latitude(), 90) || !inRange(point.longitude(), 180)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static MapApi.Point find(List<MapApi.Point> points, @Nullable String memoryId) {
        if (memoryId == null) return null;
        for (MapApi.Point point : points) {
            if (memoryId.equals(point.id())) return point;
        }
        return null;
    }

    private static boolean inRange(@Nullable Double value, double limit) {
        return value != null && value >= -limit && value <= limit;
    }

    private static boolean isDay(@Nullable String value) {
        if (value == null) return false;
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException notADay) {
            return false;
        }
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private MapUiState current() {
        MapUiState shown = state.getValue();
        return shown != null ? shown : MapUiState.loading();
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
