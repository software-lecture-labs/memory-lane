package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.memorylane.R;
import com.memorylane.feature.memories.data.MemoryApi;

import java.util.Locale;

/**
 * Turkish labels for the fixed API codes (docs/api-contract.md, "Anı alanları": the API keeps
 * the codes, the app shows the labels, e.g. TRAVEL → Gezi, HAPPY → Mutlu, OTHER → Diğer).
 * An unknown code from a newer server is not shown rather than shown as a raw code.
 */
final class MemoryLabels {
    /** No label: the badge is hidden. */
    static final int NONE = 0;

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private MemoryLabels() {
    }

    /** Label of a category code; {@link #NONE} for a missing or unknown code. */
    @StringRes
    static int category(@Nullable String code) {
        if (code == null) return NONE;
        switch (code) {
            case "TRAVEL": return R.string.memory_category_travel;
            case "FAMILY": return R.string.memory_category_family;
            case "FRIENDS": return R.string.memory_category_friends;
            case "SCHOOL": return R.string.memory_category_school;
            case "DAILY_LIFE": return R.string.memory_category_daily_life;
            case "OTHER": return R.string.memory_category_other;
            default: return NONE;
        }
    }

    /** Label of an emotion code; {@link #NONE} for a missing or unknown code. */
    @StringRes
    static int emotion(@Nullable String code) {
        if (code == null) return NONE;
        switch (code) {
            case "HAPPY": return R.string.memory_emotion_happy;
            case "PEACEFUL": return R.string.memory_emotion_peaceful;
            case "EXCITED": return R.string.memory_emotion_excited;
            case "NOSTALGIC": return R.string.memory_emotion_nostalgic;
            case "SAD": return R.string.memory_emotion_sad;
            default: return NONE;
        }
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
