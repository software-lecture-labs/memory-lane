package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.memorylane.R;
import com.memorylane.feature.memories.data.MemoryApi;

import java.util.List;
import java.util.Locale;

/**
 * The single source of the fixed category and emotion codes (docs/api-contract.md, "Anı
 * alanları") and their Turkish labels: the API keeps the codes, the app shows the labels, e.g.
 * TRAVEL → Gezi, HAPPY → Mutlu, OTHER → Diğer. The create and edit forms offer
 * {@link #CATEGORIES} and {@link #EMOTIONS}; the list and detail screens look labels up with
 * {@link #category} and {@link #emotion}. An unknown code from a newer server is not shown
 * rather than shown as a raw code.
 */
final class MemoryLabels {
    /** One code the API accepts and the label the app shows for it. */
    record Choice(@NonNull String code, @StringRes int label) {}

    /** No label: the badge is hidden. */
    static final int NONE = 0;

    /** Category of a memory that has none, as the server applies it. */
    static final String DEFAULT_CATEGORY = "OTHER";

    /** The contract's category codes, in the order the forms offer them. */
    static final List<Choice> CATEGORIES = List.of(
            new Choice("TRAVEL", R.string.memory_category_travel),
            new Choice("FAMILY", R.string.memory_category_family),
            new Choice("FRIENDS", R.string.memory_category_friends),
            new Choice("SCHOOL", R.string.memory_category_school),
            new Choice("DAILY_LIFE", R.string.memory_category_daily_life),
            new Choice(DEFAULT_CATEGORY, R.string.memory_category_other));

    /** The contract's emotion codes, in the order the forms offer them. */
    static final List<Choice> EMOTIONS = List.of(
            new Choice("HAPPY", R.string.memory_emotion_happy),
            new Choice("PEACEFUL", R.string.memory_emotion_peaceful),
            new Choice("EXCITED", R.string.memory_emotion_excited),
            new Choice("NOSTALGIC", R.string.memory_emotion_nostalgic),
            new Choice("SAD", R.string.memory_emotion_sad));

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private MemoryLabels() {
    }

    /** True when {@code code} is one of {@code choices}; null is never a choice. */
    static boolean contains(@NonNull List<Choice> choices, @Nullable String code) {
        return label(choices, code) != NONE;
    }

    /** Label of a category code; {@link #NONE} for a missing or unknown code. */
    @StringRes
    static int category(@Nullable String code) {
        return label(CATEGORIES, code);
    }

    /** Label of an emotion code; {@link #NONE} for a missing or unknown code. */
    @StringRes
    static int emotion(@Nullable String code) {
        return label(EMOTIONS, code);
    }

    @StringRes
    private static int label(@NonNull List<Choice> choices, @Nullable String code) {
        if (code == null) return NONE;
        for (Choice choice : choices) {
            if (choice.code().equals(code)) return choice.label();
        }
        return NONE;
    }

    /**
     * Coordinates as "41,0082° K, 28,9784° D" (Turkish decimal comma). The app does no address
     * lookup, so the stored point is what can be shown.
     *
     * @param directions the letters for north, south, east and west, in that order
     */
    @NonNull
    static String coordinates(@NonNull MemoryApi.Location location, @NonNull String... directions) {
        if (directions.length != 4) throw new IllegalArgumentException("Four direction letters are needed");
        double latitude = location.latitude();
        double longitude = location.longitude();
        return String.format(TURKISH, "%.4f° %s, %.4f° %s",
                Math.abs(latitude), latitude < 0 ? directions[1] : directions[0],
                Math.abs(longitude), longitude < 0 ? directions[3] : directions[2]);
    }
}
