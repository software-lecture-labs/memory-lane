package com.memorylane.feature.calendar.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What the calendar shows: the visible month, the picked day, today and the notes of every
 * loaded memory by date. {@code firstMemory} and {@code total} feed the profile summary;
 * {@code onThisDay} the "on this day in earlier years" reminder.
 */
public final class MemoryCalendarUiState {
    public enum Status { SIGNED_OUT, LOADING, READY, ERROR }

    private final Status status;
    private final Map<LocalDate, List<CalendarNote>> notesByDate;
    private final YearMonth month;
    @Nullable private final LocalDate selected;
    private final LocalDate today;
    @Nullable private final ApiError error;

    MemoryCalendarUiState(Status status, Map<LocalDate, List<CalendarNote>> notesByDate, YearMonth month,
                          @Nullable LocalDate selected, @NonNull LocalDate today, @Nullable ApiError error) {
        this.status = status;
        this.notesByDate = Collections.unmodifiableMap(notesByDate);
        this.month = month;
        this.selected = selected;
        this.today = today;
        this.error = error;
    }

    public Status getStatus() { return status; }

    @NonNull public YearMonth getMonth() { return month; }

    @Nullable public LocalDate getSelected() { return selected; }

    /** The day the calendar treats as today (from the ViewModel's clock, so tests can fix it). */
    @NonNull public LocalDate getToday() { return today; }

    /**
     * Memories written on today's day and month in earlier years, the most recent year first:
     * "1 yıl önce bugün". A memory of 29 February is remembered on 28 February in other years,
     * so it is not skipped for three years out of four.
     */
    @NonNull
    public List<CalendarNote> getOnThisDay() {
        List<CalendarNote> notes = new ArrayList<>();
        for (Map.Entry<LocalDate, List<CalendarNote>> day : notesByDate.entrySet()) {
            LocalDate date = day.getKey();
            if (date.getYear() < today.getYear() && sameDayOfYear(date)) notes.addAll(day.getValue());
        }
        notes.sort(Comparator.comparing(CalendarNote::date).reversed());
        return notes;
    }

    private boolean sameDayOfYear(LocalDate date) {
        if (date.getMonth() != today.getMonth()) return false;
        if (date.getDayOfMonth() == today.getDayOfMonth()) return true;
        boolean leapDayInNonLeapYear = date.getMonthValue() == 2 && date.getDayOfMonth() == 29
                && !today.isLeapYear() && today.getDayOfMonth() == 28;
        return leapDayInNonLeapYear;
    }

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
