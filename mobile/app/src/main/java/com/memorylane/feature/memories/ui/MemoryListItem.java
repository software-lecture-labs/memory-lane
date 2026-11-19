package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One row of the memory list: either a year header or a memory card. The date stays a
 * {@link LocalDate}; the adapter formats it, so this model needs no Context or locale.
 */
public final class MemoryListItem {
    public enum Type { YEAR_HEADER, MEMORY }

    private final Type type;
    private final int year;
    @Nullable private final String id;
    @Nullable private final String title;
    @Nullable private final String description;
    @Nullable private final LocalDate date;

    private MemoryListItem(Type type, int year, @Nullable String id, @Nullable String title,
                           @Nullable String description, @Nullable LocalDate date) {
        this.type = type;
        this.year = year;
        this.id = id;
        this.title = title;
        this.description = description;
        this.date = date;
    }

    public static MemoryListItem yearHeader(int year) {
        return new MemoryListItem(Type.YEAR_HEADER, year, null, null, null, null);
    }

    /** A missing description becomes an empty text, as the API returns it. */
    public static MemoryListItem memory(@NonNull String id, @NonNull String title,
                                        @Nullable String description, @NonNull LocalDate date) {
        return new MemoryListItem(Type.MEMORY, date.getYear(),
                Objects.requireNonNull(id, "id"), Objects.requireNonNull(title, "title"),
                description == null ? "" : description, Objects.requireNonNull(date, "date"));
    }

    public Type getType() { return type; }

    /** The header's year, or the year of the memory's date. */
    public int getYear() { return year; }

    /** Memory id; null for a year header. */
    @Nullable public String getId() { return id; }

    @Nullable public String getTitle() { return title; }

    /** Empty text when the memory has no description; null for a year header. */
    @Nullable public String getDescription() { return description; }

    @Nullable public LocalDate getDate() { return date; }

    /**
     * Stable identity for DiffUtil's areItemsTheSame. Headers and memories use separate
     * prefixes, so a memory id can never collide with a year.
     */
    @NonNull
    public String getKey() {
        return type == Type.YEAR_HEADER ? "year:" + year : "memory:" + id;
    }

    /** Full value equality, used by DiffUtil's areContentsTheSame. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MemoryListItem)) return false;
        MemoryListItem item = (MemoryListItem) other;
        return type == item.type && year == item.year && Objects.equals(id, item.id)
                && Objects.equals(title, item.title) && Objects.equals(description, item.description)
                && Objects.equals(date, item.date);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, year, id, title, description, date);
    }

    @NonNull
    @Override
    public String toString() {
        // Titles are user content; keep them out of logs and test failure output.
        return "MemoryListItem{" + getKey() + "}";
    }
}
