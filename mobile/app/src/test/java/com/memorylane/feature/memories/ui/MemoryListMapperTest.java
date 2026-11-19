package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.memorylane.feature.memories.data.FakeMemoryListRepository;
import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MemoryListMapperTest {

    @Test
    public void noMemoriesGiveNoRows() {
        assertTrue(MemoryListMapper.toItems(Collections.emptyList()).isEmpty());
    }

    @Test
    public void eachYearGetsOneHeaderBeforeItsMemories() {
        List<MemoryListItem> rows = MemoryListMapper.toItems(FakeMemoryListRepository.sampleMemories());

        assertEquals(List.of("year:2024", "memory:m3", "memory:m2", "year:2023", "memory:m1"), keys(rows));
        assertEquals(MemoryListItem.memory("m3", "Bursa'da bir sonbahar günü",
                "Tophane yamaçlarında kestane şekeri molası.", LocalDate.of(2024, 10, 28)), rows.get(1));
    }

    @Test
    public void rowsAreNewestFirstWhateverTheInputOrder() {
        List<MemoryApi.Memory> shuffled = List.of(
                memory("a", "2022-01-05"), memory("b", "2024-03-01"), memory("c", "2023-07-10"),
                memory("d", "2024-12-31"));

        assertEquals(List.of("year:2024", "memory:d", "memory:b", "year:2023", "memory:c",
                "year:2022", "memory:a"), keys(MemoryListMapper.toItems(shuffled)));
    }

    @Test
    public void sameDayMemoriesKeepTheServerOrder() {
        // The server breaks date ties with id DESC; a stable sort must not reorder them.
        List<MemoryApi.Memory> sameDay = List.of(
                memory("z", "2024-05-01"), memory("m", "2024-05-01"), memory("a", "2024-05-01"));

        assertEquals(List.of("year:2024", "memory:z", "memory:m", "memory:a"),
                keys(MemoryListMapper.toItems(sameDay)));
    }

    @Test
    public void yearContinuingOnTheNextPageGetsNoSecondHeader() {
        List<MemoryApi.Memory> loaded = new ArrayList<>();
        loaded.add(memory("p1a", "2024-06-01"));
        loaded.add(memory("p1b", "2024-02-01"));
        // Page 2 starts in the same year.
        loaded.add(memory("p2a", "2024-01-15"));
        loaded.add(memory("p2b", "2023-11-30"));

        assertEquals(List.of("year:2024", "memory:p1a", "memory:p1b", "memory:p2a",
                "year:2023", "memory:p2b"), keys(MemoryListMapper.toItems(loaded)));
    }

    @Test
    public void memoryRepeatedOnTheNextPageIsShownOnce() {
        List<MemoryApi.Memory> loaded = List.of(
                memory("a", "2024-06-01"), memory("b", "2024-05-01"),
                // Offset paging after an insert repeats the boundary memory on page 2.
                memory("b", "2024-05-01"), memory("c", "2024-04-01"));

        assertEquals(List.of("year:2024", "memory:a", "memory:b", "memory:c"),
                keys(MemoryListMapper.toItems(loaded)));
    }

    @Test
    public void missingDescriptionIsEmptyText() {
        MemoryListItem card = MemoryListMapper.toItems(
                List.of(new MemoryApi.Memory("a", "Başlık", null, "2024-06-01"))).get(1);

        assertEquals("", card.getDescription());
    }

    @Test
    public void contractViolationsFailInsteadOfHidingMemories() {
        List<MemoryApi.Memory> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(new MemoryApi.Memory(null, "Başlık", "", "2024-06-01"));
        invalid.add(new MemoryApi.Memory(" ", "Başlık", "", "2024-06-01"));
        invalid.add(new MemoryApi.Memory("a", null, "", "2024-06-01"));
        invalid.add(new MemoryApi.Memory("a", "   ", "", "2024-06-01"));
        invalid.add(new MemoryApi.Memory("a", "Başlık", "", null));
        for (String date : new String[] {"", "2024-6-1", "01.06.2024", "2024-02-30", "2024-06-01T10:00"}) {
            invalid.add(new MemoryApi.Memory("a", "Başlık", "", date));
        }

        for (MemoryApi.Memory memory : invalid) {
            List<MemoryApi.Memory> page = new ArrayList<>();
            page.add(memory("ok", "2024-07-01"));
            page.add(memory);
            assertThrows(String.valueOf(memory), IllegalArgumentException.class,
                    () -> MemoryListMapper.toItems(page));
        }
    }

    private static MemoryApi.Memory memory(String id, String date) {
        return new MemoryApi.Memory(id, "Anı " + id, "", date);
    }

    private static List<String> keys(List<MemoryListItem> rows) {
        List<String> keys = new ArrayList<>();
        for (MemoryListItem row : rows) keys.add(row.getKey());
        return keys;
    }
}
