package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.feature.memories.data.MemoryApi;

import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * Field rules shared by the create and edit forms. They match the server's MemoryInput
 * (docs/api-contract.md, "Anı alanları"): title required, at most 100 Unicode characters after
 * trimming; description at most 3000; date required and not in the future; an optional location
 * needs both latitude (-90..90) and longitude (-180..180). The app only gives early feedback; the
 * server stays the authority.
 */
final class MemoryFormRules {
    static final int TITLE_MAX_LENGTH = 100;
    static final int DESCRIPTION_MAX_LENGTH = 3000;
    static final int LATITUDE_LIMIT = 90;
    static final int LONGITUDE_LIMIT = 180;

    /** Plain decimal digits only: no exponent, hex, "NaN" or "Infinity". */
    private static final Pattern COORDINATE = Pattern.compile("[+-]?(\\d{1,3}(\\.\\d*)?|\\.\\d+)");

    private MemoryFormRules() {
    }

    static MemoryCreateUiState.TitleError titleError(@NonNull String title) {
        String trimmed = strip(title);
        if (trimmed.isEmpty()) return MemoryCreateUiState.TitleError.REQUIRED;
        return trimmed.codePointCount(0, trimmed.length()) > TITLE_MAX_LENGTH
                ? MemoryCreateUiState.TitleError.TOO_LONG : MemoryCreateUiState.TitleError.NONE;
    }

    static MemoryCreateUiState.DescriptionError descriptionError(@NonNull String description) {
        return description.codePointCount(0, description.length()) > DESCRIPTION_MAX_LENGTH
                ? MemoryCreateUiState.DescriptionError.TOO_LONG : MemoryCreateUiState.DescriptionError.NONE;
    }

    static MemoryCreateUiState.DateError dateError(@Nullable LocalDate date, @NonNull LocalDate today) {
        if (date == null) return MemoryCreateUiState.DateError.REQUIRED;
        return date.isAfter(today) ? MemoryCreateUiState.DateError.FUTURE : MemoryCreateUiState.DateError.NONE;
    }

    /**
     * Latitude and longitude as typed: both empty is no location; only one of them is
     * {@code INCOMPLETE}; a value that is not a coordinate in range is {@code INVALID}.
     */
    static MemoryCreateUiState.LocationError locationError(@NonNull String latitude, @NonNull String longitude) {
        String lat = strip(latitude);
        String lng = strip(longitude);
        if (lat.isEmpty() && lng.isEmpty()) return MemoryCreateUiState.LocationError.NONE;
        if (lat.isEmpty() || lng.isEmpty()) return MemoryCreateUiState.LocationError.INCOMPLETE;
        return coordinate(lat, LATITUDE_LIMIT) == null || coordinate(lng, LONGITUDE_LIMIT) == null
                ? MemoryCreateUiState.LocationError.INVALID : MemoryCreateUiState.LocationError.NONE;
    }

    /**
     * The location to send for the typed coordinates, or null when there is none. Meant to be
     * called once {@link #locationError} found no error; invalid input also gives null.
     */
    @Nullable
    static MemoryApi.Location location(@NonNull String latitude, @NonNull String longitude) {
        Double lat = coordinate(strip(latitude), LATITUDE_LIMIT);
        Double lng = coordinate(strip(longitude), LONGITUDE_LIMIT);
        return lat == null || lng == null ? null : new MemoryApi.Location(lat, lng);
    }

    /**
     * A finite number within the limit either way, else null. A decimal comma is accepted as
     * well, since Turkish keyboards may offer one.
     *
     * @param text  the trimmed input
     * @param limit {@link #LATITUDE_LIMIT} or {@link #LONGITUDE_LIMIT}
     */
    @Nullable
    static Double coordinate(@NonNull String text, int limit) {
        String value = text.replace(',', '.');
        if (!COORDINATE.matcher(value).matches()) return null;
        double parsed = Double.parseDouble(value);
        return Math.abs(parsed) <= limit ? parsed : null;
    }

    /**
     * Removes leading and trailing whitespace as the server's {@code String.strip()} does,
     * Unicode spaces included; {@code strip()} itself needs API 33.
     */
    static String strip(@NonNull String value) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && Character.isWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }
}
