package com.memorylane.feature.calendar.ui;

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
import com.memorylane.feature.memories.data.MemoryListRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Memory calendar on the profile tab. Reads every memory of the signed-in user through the same
 * {@link MemoryListRepository} as the memory list, page after page, and keeps them by date; the
 * month and the picked day live here so they survive rotation.
 *
 * <p>Only one load runs at a time and a generation number drops answers that arrive after the
 * session changed. A 401 turns the calendar to SIGNED_OUT like the other screens; the session
 * layer (AuthInterceptor, SessionNavigator) ends the session and leaves the screen.
 */
public final class MemoryCalendarViewModel extends ViewModel {
    /**
     * Safety stop: 50 pages of 20 (1000 memories) is far more than a personal notebook holds in
     * this release. Past it the calendar shows a "truncated" note, like the map; a server query
     * for one month would remove the limit and needs a contract change first.
     */
    static final int MAX_PAGES = 50;
    private static final int UNAUTHORIZED = 401;

    private final MemoryListRepository repository;
    private final Clock clock;
    private final MutableLiveData<MemoryCalendarUiState> state;

    @Nullable private String session;
    /** What the calendar shows; replaced only when a complete load arrives. */
    private final Map<LocalDate, List<CalendarNote>> notesByDate = new HashMap<>();
    /** Pages of the load in progress. */
    private final Map<LocalDate, List<CalendarNote>> incoming = new HashMap<>();
    private final Set<String> seen = new HashSet<>();
    /** True once a load completed for this session; a refresh then keeps month and day. */
    private boolean loadedOnce;
    /** The last load stopped at {@link #MAX_PAGES} with more memories left on the server. */
    private boolean truncated;
    private YearMonth month;
    @Nullable private LocalDate selected;
    private int generation;
    @Nullable private Cancellable request;

    public MemoryCalendarViewModel(@NonNull MemoryListRepository repository, @NonNull Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.month = YearMonth.now(clock);
        this.state = new MutableLiveData<>(snapshot(MemoryCalendarUiState.Status.SIGNED_OUT, null));
    }

    public static ViewModelProvider.Factory factory(MemoryListRepository repository) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(MemoryCalendarViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
                }
                return modelClass.cast(new MemoryCalendarViewModel(repository, Clock.systemDefaultZone()));
            }
        };
    }

    public LiveData<MemoryCalendarUiState> getState() { return state; }

    /**
     * New session: everything restarts. Same session: memories are read again in the background
     * (one may have been added on the memories tab) while the current month stays on screen.
     * Null: SIGNED_OUT.
     */
    public void setSession(@Nullable String authorization) {
        if (!Objects.equals(session, authorization)) {
            session = authorization;
            notesByDate.clear();
            loadedOnce = false;
            truncated = false;
        }
        reload();
    }

    /** Loads every memory again; shows the loading state only when nothing is on screen yet. */
    public void reload() {
        cancel();
        incoming.clear();
        seen.clear();
        if (session == null) {
            publish(MemoryCalendarUiState.Status.SIGNED_OUT, null);
            return;
        }
        if (!loadedOnce) publish(MemoryCalendarUiState.Status.LOADING, null);
        load(++generation, 0);
    }

    public void previousMonth() { showMonth(month.minusMonths(1)); }

    public void nextMonth() { showMonth(month.plusMonths(1)); }

    /** Picks a day of the visible month; days of the neighbouring months open their month. */
    public void select(@NonNull LocalDate day) {
        YearMonth dayMonth = YearMonth.from(day);
        if (!dayMonth.equals(month)) month = dayMonth;
        selected = day;
        republish();
    }

    private void showMonth(YearMonth next) {
        month = next;
        selected = null;
        republish();
    }

    private void load(int id, int page) {
        request = repository.list(session, page, result -> {
            if (id != generation) return;
            request = null;
            onPage(id, page, result);
        });
    }

    private void onPage(int id, int page, ApiResult<MemoryApi.Page> result) {
        if (!result.isSuccess()) {
            ApiError error = result.getError() != null ? result.getError() : ApiError.unexpected();
            if (error.getHttpStatus() == UNAUTHORIZED) {
                // Same as the other screens: a rejected session is "signed out", not an error,
                // and nothing of this account stays on screen.
                session = null;
                notesByDate.clear();
                loadedOnce = false;
                truncated = false;
                publish(MemoryCalendarUiState.Status.SIGNED_OUT, null);
                return;
            }
            publish(MemoryCalendarUiState.Status.ERROR, error);
            return;
        }
        MemoryApi.Page body = result.getData();
        if (body == null || body.content() == null) {
            publish(MemoryCalendarUiState.Status.ERROR, ApiError.parse());
            return;
        }
        try {
            for (MemoryApi.Memory memory : body.content()) add(memory);
        } catch (IllegalArgumentException contractViolation) {
            publish(MemoryCalendarUiState.Status.ERROR, ApiError.parse());
            return;
        }
        if (body.hasNext() && page + 1 < MAX_PAGES) {
            load(id, page + 1);
            return;
        }
        // More pages remain after the safety stop: the calendar says so instead of silently
        // showing part of the notebook as if it were all of it.
        truncated = body.hasNext();
        notesByDate.clear();
        notesByDate.putAll(incoming);
        incoming.clear();
        if (!loadedOnce) openToday();
        loadedOnce = true;
        publish(MemoryCalendarUiState.Status.READY, null);
    }

    private void add(MemoryApi.Memory memory) {
        if (memory == null || isBlank(memory.id()) || isBlank(memory.title()) || memory.memoryDate() == null) {
            throw new IllegalArgumentException("Memory violates the list contract");
        }
        // Offset paging can repeat a memory when one is added meanwhile.
        if (!seen.add(memory.id())) return;
        LocalDate date;
        try {
            date = LocalDate.parse(memory.memoryDate());
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Memory date is not YYYY-MM-DD", error);
        }
        String description = memory.description() == null ? "" : memory.description();
        incoming.computeIfAbsent(date, day -> new ArrayList<>())
                .add(new CalendarNote(memory.id(), memory.title(), description, date));
    }

    /** Opens on today, like a paper calendar; "on this day" points to past years' memories. */
    private void openToday() {
        LocalDate today = LocalDate.now(clock);
        month = YearMonth.from(today);
        selected = today;
    }

    private void republish() {
        MemoryCalendarUiState current = state.getValue();
        publish(current != null ? current.getStatus() : MemoryCalendarUiState.Status.SIGNED_OUT,
                current != null ? current.getError() : null);
    }

    private void publish(MemoryCalendarUiState.Status status, @Nullable ApiError error) {
        state.setValue(snapshot(status, error));
    }

    private MemoryCalendarUiState snapshot(MemoryCalendarUiState.Status status, @Nullable ApiError error) {
        Map<LocalDate, List<CalendarNote>> copy = new HashMap<>();
        notesByDate.forEach((date, notes) -> copy.put(date, List.copyOf(notes)));
        return new MemoryCalendarUiState(status, copy, month, selected, LocalDate.now(clock), truncated, error);
    }

    private void cancel() {
        generation++;
        if (request != null) request.cancel();
        request = null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    protected void onCleared() {
        cancel();
    }
}
