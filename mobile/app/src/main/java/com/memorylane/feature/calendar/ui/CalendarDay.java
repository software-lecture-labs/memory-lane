package com.memorylane.feature.calendar.ui;

import androidx.annotation.NonNull;

import java.time.LocalDate;
import java.util.List;

/**
 * One cell of the month grid. Days of the neighbouring months fill the first and last week;
 * they are drawn faded and carry no notes.
 */
public record CalendarDay(@NonNull LocalDate date, boolean inMonth, @NonNull List<CalendarNote> notes) {

    public boolean hasNotes() {
        return !notes.isEmpty();
    }
}
