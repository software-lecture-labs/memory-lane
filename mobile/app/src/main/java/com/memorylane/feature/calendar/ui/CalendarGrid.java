package com.memorylane.feature.calendar.ui;

import androidx.annotation.NonNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Builds the cells of one month as a Turkish calendar shows it: weeks start on Monday and the
 * grid always has six weeks, so its height does not jump from month to month.
 */
public final class CalendarGrid {
    public static final int DAYS_PER_WEEK = 7;
    public static final int CELLS = 6 * DAYS_PER_WEEK;

    private CalendarGrid() {
    }

    @NonNull
    public static List<CalendarDay> build(@NonNull YearMonth month,
                                          @NonNull Map<LocalDate, List<CalendarNote>> notesByDate) {
        LocalDate first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<CalendarDay> days = new ArrayList<>(CELLS);
        for (int i = 0; i < CELLS; i++) {
            LocalDate date = first.plusDays(i);
            boolean inMonth = YearMonth.from(date).equals(month);
            List<CalendarNote> notes = inMonth ? notesByDate.get(date) : null;
            days.add(new CalendarDay(date, inMonth, notes == null ? Collections.emptyList() : notes));
        }
        return days;
    }
}
