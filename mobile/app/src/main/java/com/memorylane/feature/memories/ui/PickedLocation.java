package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.feature.memories.data.MemoryApi;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A point chosen on the map picker (ML-15) and how it travels back to a form.
 *
 * <p>A map can be panned around the globe, so a tapped longitude may lie outside ±180; it is
 * wrapped into range. Latitude is never wrapped: a value outside ±90 is refused. Coordinates are
 * rounded to six decimals (about 10 cm), finer than a finger can choose, and written so that
 * {@link MemoryFormRules#coordinate} reads them back unchanged.
 */
final class PickedLocation {
    /** SavedStateHandle key on the form's back stack entry. */
    static final String RESULT = "picked_location";

    private static final int DECIMALS = 6;

    private PickedLocation() {}

    /** The location for a tapped point, or null if it is not a usable coordinate. */
    @Nullable
    static MemoryApi.Location fromMap(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || Math.abs(latitude) > MemoryFormRules.LATITUDE_LIMIT) {
            return null;
        }
        double wrapped = ((longitude + 180) % 360 + 360) % 360 - 180;
        return new MemoryApi.Location(round(latitude), round(wrapped));
    }

    /** Text for a coordinate field, e.g. {@code 41.0082} or {@code -73.985428}. */
    static String text(double coordinate) {
        return BigDecimal.valueOf(coordinate).setScale(DECIMALS, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** Result value for a chosen point. */
    static double[] chosen(@NonNull MemoryApi.Location location) {
        return new double[] {location.latitude(), location.longitude()};
    }

    /** Result value for "Konumu kaldır". */
    static double[] removed() {
        return new double[0];
    }

    /** The location a result carries; null for {@link #removed()} or anything unreadable. */
    @Nullable
    static MemoryApi.Location read(@Nullable double[] result) {
        return result == null || result.length != 2 ? null : fromMap(result[0], result[1]);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(DECIMALS, RoundingMode.HALF_UP).doubleValue();
    }
}
