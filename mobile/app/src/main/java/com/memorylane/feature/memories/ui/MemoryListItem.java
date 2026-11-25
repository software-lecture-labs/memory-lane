package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.feature.memories.data.MemoryApi;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One row of the memory list: either a year header or a memory card. The date stays a
 * {@link LocalDate}; the adapter formats it, so this model needs no Context or locale.
 *
 * <p>A card shows a cover photo and a photo count badge only when {@link #getCoverPhotoId()} is
 * set; otherwise it is the text-only variant. Category, emotion and location badges are shown for
 * the values the memory has.
 */
public final class MemoryListItem {
    public enum Type { YEAR_HEADER, MEMORY }

    private final Type type;
    private final int year;
    @Nullable private final String id;
    @Nullable private final String title;
    @Nullable private final String description;
    @Nullable private final LocalDate date;
    @Nullable private final String category;
    @Nullable private final String emotion;
    @Nullable private final MemoryApi.Location location;
    private final int photoCount;
    @Nullable private final String coverPhotoId;

    private MemoryListItem(Type type, int year, @Nullable String id, @Nullable String title,
                           @Nullable String description, @Nullable LocalDate date, @Nullable String category,
                           @Nullable String emotion, @Nullable MemoryApi.Location location, int photoCount,
                           @Nullable String coverPhotoId) {
        this.type = type;
        this.year = year;
        this.id = id;
        this.title = title;
        this.description = description;
        this.date = date;
        this.category = category;
        this.emotion = emotion;
        this.location = location;
        this.photoCount = photoCount;
        this.coverPhotoId = coverPhotoId;
    }

    public static MemoryListItem yearHeader(int year) {
        return new MemoryListItem(Type.YEAR_HEADER, year, null, null, null, null, null, null, null, 0, null);
    }

    /** A missing description becomes an empty text, as the API returns it. No badges, no cover. */
    public static MemoryListItem memory(@NonNull String id, @NonNull String title,
                                        @Nullable String description, @NonNull LocalDate date) {
        return new MemoryListItem(Type.MEMORY, date.getYear(),
                Objects.requireNonNull(id, "id"), Objects.requireNonNull(title, "title"),
                description == null ? "" : description, Objects.requireNonNull(date, "date"),
                null, null, null, 0, null);
    }

    /**
     * The same card with its badges and cover. Blank codes count as missing; a cover without
     * photos is impossible in the API, so the count is at least 1 when a cover is set.
     */
    public MemoryListItem withDetails(@Nullable String category, @Nullable String emotion,
                                      @Nullable MemoryApi.Location location, int photoCount,
                                      @Nullable String coverPhotoId) {
        if (type != Type.MEMORY) throw new IllegalStateException("A year header has no details");
        String cover = blankToNull(coverPhotoId);
        int count = Math.max(photoCount, cover == null ? 0 : 1);
        return new MemoryListItem(type, year, id, title, description, date, blankToNull(category),
                blankToNull(emotion), location, count, cover);
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

    /** API category code such as {@code TRAVEL}; null when not set. */
    @Nullable public String getCategory() { return category; }

    /** API emotion code such as {@code HAPPY}; null when not set. */
    @Nullable public String getEmotion() { return emotion; }

    @Nullable public MemoryApi.Location getLocation() { return location; }

    public int getPhotoCount() { return photoCount; }

    /** Cover photo id; null for the text-only card. */
    @Nullable public String getCoverPhotoId() { return coverPhotoId; }

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
        return type == item.type && year == item.year && photoCount == item.photoCount
                && Objects.equals(id, item.id) && Objects.equals(title, item.title)
                && Objects.equals(description, item.description) && Objects.equals(date, item.date)
                && Objects.equals(category, item.category) && Objects.equals(emotion, item.emotion)
                && Objects.equals(location, item.location) && Objects.equals(coverPhotoId, item.coverPhotoId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, year, id, title, description, date, category, emotion, location, photoCount,
                coverPhotoId);
    }

    @NonNull
    @Override
    public String toString() {
        // Titles are user content; keep them out of logs and test failure output.
        return "MemoryListItem{" + getKey() + "}";
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
