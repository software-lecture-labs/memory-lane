package com.memorylane.feature.memories.ui;

import androidx.annotation.NonNull;

import com.memorylane.feature.memories.data.MemoryApi;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the memories loaded so far into list rows: newest first, with a year header before the
 * first memory of each year.
 *
 * <p>The caller passes every loaded page in order and maps them together, so a year that
 * continues on the next page gets no second header. Rules:
 * <ul>
 *   <li>Rows are sorted by date, newest first. The sort is stable, so memories on the same day
 *       keep the server's order (memory_date DESC, id DESC).</li>
 *   <li>A memory that appears twice keeps its first position. Offset paging can return the same
 *       memory on two pages when one is added meanwhile; a repeated DiffUtil key would break
 *       the list.</li>
 *   <li>A memory without id, title or a valid YYYY-MM-DD date violates the API contract and
 *       makes the whole mapping fail with {@link IllegalArgumentException}; the caller shows
 *       it as a parse error instead of silently hiding memories.</li>
 * </ul>
 */
public final class MemoryListMapper {
    private MemoryListMapper() {
    }

    @NonNull
    public static List<MemoryListItem> toItems(@NonNull List<MemoryApi.Memory> memories) {
        List<MemoryListItem> cards = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MemoryApi.Memory memory : memories) {
            MemoryListItem card = toCard(memory);
            if (seen.add(card.getId())) cards.add(card);
        }
        cards.sort(Comparator.comparing(MemoryListItem::getDate).reversed());

        List<MemoryListItem> rows = new ArrayList<>(cards.size() + 4);
        Integer currentYear = null;
        for (MemoryListItem card : cards) {
            if (currentYear == null || currentYear != card.getYear()) {
                currentYear = card.getYear();
                rows.add(MemoryListItem.yearHeader(currentYear));
            }
            rows.add(card);
        }
        return rows;
    }

    private static MemoryListItem toCard(MemoryApi.Memory memory) {
        if (memory == null) throw new IllegalArgumentException("Memory is missing");
        if (isBlank(memory.id())) throw new IllegalArgumentException("Memory id is missing");
        if (isBlank(memory.title())) throw new IllegalArgumentException("Memory title is missing");
        return MemoryListItem.memory(memory.id(), memory.title(), memory.description(),
                        date(memory.memoryDate()))
                .withDetails(memory.category(), memory.emotion(), memory.location(), memory.photoCount(),
                        memory.coverPhotoId());
    }

    private static LocalDate date(String value) {
        if (value == null) throw new IllegalArgumentException("Memory date is missing");
        try {
            // ISO_LOCAL_DATE: exactly the YYYY-MM-DD form the API contract specifies.
            return LocalDate.parse(value);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Memory date is not YYYY-MM-DD", error);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
