package com.memorylane.feature.calendar.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What the calendar shows: the visible month, the picked day, today and the notes of every
 * loaded memory by date. {@code firstMemory} and {@code total} feed the profile summary.
 */
public final class MemoryCalendarUiState {
    public enum Status { SIGNED_OUT, LOADING, READY, ERROR }

    private final Status status;
    private final Map<LocalDate, List<CalendarNote>> notesByDate;
    private final YearMonth month;
    @Nullable private final LocalDate selected;
    private final LocalDate today;
    private final boolean truncated;
    @Nullable private final ApiError error;

    MemoryCalendarUiState(Status status, Map<LocalDate, List<CalendarNote>> notesByDate, YearMonth month,
                          @Nullable LocalDate selected, @NonNull LocalDate today, boolean truncated,
                          @Nullable ApiError error) {
        this.status = status;
        this.notesByDate = Collections.unmodifiableMap(notesByDate);
        this.month = month;
        this.selected = selected;
        this.today = today;
        this.truncated = truncated;
        this.error = error;
    }

    /** True when the server has more memories than the calendar reads; the view says so. */
    public boolean isTruncated() { return truncated; }

    public Status getStatus() { return status; }

    @NonNull public YearMonth getMonth() { return month; }

    @Nullable public LocalDate getSelected() { return selected; }

    /** The day the calendar treats as today (from the ViewModel's clock, so tests can fix it). */
    @NonNull public LocalDate getToday() { return today; }

    @Nullable public ApiError getError() { return error; }

    @NonNull
    public List<CalendarDay> getDays() {
        return CalendarGrid.build(month, notesByDate);
    }

    /** Notes of the picked day, oldest entry first as the server listed them. */
    @NonNull
    public List<CalendarNote> getSelectedNotes() {
        List<CalendarNote> notes = selected == null ? null : notesByDate.get(selected);
        return notes == null ? Collections.emptyList() : notes;
    }

    /** Number of memories in the visible month. */
    public int getMonthCount() {
        int count = 0;
        for (Map.Entry<LocalDate, List<CalendarNote>> day : notesByDate.entrySet()) {
            if (YearMonth.from(day.getKey()).equals(month)) count += day.getValue().size();
        }
        return count;
    }

    public int getTotal() {
        int total = 0;
        for (List<CalendarNote> notes : notesByDate.values()) total += notes.size();
        return total;
    }

    @Nullable
    public LocalDate getFirstMemory() {
        LocalDate first = null;
        for (LocalDate date : notesByDate.keySet()) {
            if (first == null || date.isBefore(first)) first = date;
        }
        return first;
    }
}
