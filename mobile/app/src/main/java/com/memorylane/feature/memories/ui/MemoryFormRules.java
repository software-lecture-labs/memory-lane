package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;

/**
 * Field rules shared by the create and edit forms. They match the server's MemoryInput
 * (docs/api-contract.md, "Anı alanları"): title required, at most 100 Unicode characters after
 * trimming; description at most 3000; date required and not in the future. The app only gives
 * early feedback; the server stays the authority.
 */
final class MemoryFormRules {
    static final int TITLE_MAX_LENGTH = 100;
    static final int DESCRIPTION_MAX_LENGTH = 3000;

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
