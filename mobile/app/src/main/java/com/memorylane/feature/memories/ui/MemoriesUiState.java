package com.memorylane.feature.memories.ui;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.memories.data.MemoryApi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

record MemoriesUiState(boolean signedIn, String title, String description, LocalDate date,
                       String category, String emotion, String latitude, String longitude,
                       List<MemoryApi.Memory> rows, boolean saving, boolean loading,
                       boolean loaded, boolean hasMore, ApiError saveError, ApiError listError,
                       Set<Validation> errors, boolean sessionExpired) {
    enum Validation {
        TITLE_REQUIRED, TITLE_TOO_LONG, DESCRIPTION_TOO_LONG, DATE_REQUIRED, DATE_IN_FUTURE,
        LOCATION_INCOMPLETE, LOCATION_INVALID
    }

    MemoriesUiState {
        rows = Collections.unmodifiableList(new ArrayList<>(rows));
        EnumSet<Validation> copy = EnumSet.noneOf(Validation.class);
        copy.addAll(errors);
        errors = Collections.unmodifiableSet(copy);
    }

    boolean has(Validation error) { return errors.contains(error); }
}
