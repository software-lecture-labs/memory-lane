package com.memorylane.feature.memories.ui;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.memories.data.MemoryApi;

import java.time.LocalDate;
import java.util.List;

record MemoriesUiState(boolean signedIn, String title, String description, LocalDate date,
                       List<MemoryApi.Memory> rows, boolean saving, boolean loading,
                       boolean loaded, boolean hasMore, ApiError saveError, ApiError listError,
                       Validation validation, boolean sessionExpired) {
    enum Validation { NONE, TITLE_REQUIRED, DATE_REQUIRED }

    MemoriesUiState {
        rows = List.copyOf(rows);
    }
}
