package com.memorylane.feature.calendar.ui;

import androidx.annotation.NonNull;

import java.time.LocalDate;

/** A memory as the calendar shows it: a small note on its day. */
public record CalendarNote(@NonNull String id, @NonNull String title, @NonNull String description,
                           @NonNull LocalDate date) {

    @NonNull
    @Override
    public String toString() {
        // Titles are user content; keep them out of logs and test failure output.
        return "CalendarNote{" + id + "}";
    }
}
