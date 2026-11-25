package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;

public class MemoryListItemTest {
    private static final LocalDate DATE = LocalDate.of(2024, 10, 28);

    @Test
    public void yearHeaderCarriesOnlyTheYear() {
        MemoryListItem header = MemoryListItem.yearHeader(2024);

        assertEquals(MemoryListItem.Type.YEAR_HEADER, header.getType());
        assertEquals(2024, header.getYear());
        assertNull(header.getId());
        assertNull(header.getTitle());
        assertNull(header.getDescription());
        assertNull(header.getDate());
    }

    @Test
    public void memoryTakesItsYearFromTheDate() {
        MemoryListItem memory = MemoryListItem.memory("m1", "Bursa'da bir gün", "Kestane şekeri", DATE);

        assertEquals(MemoryListItem.Type.MEMORY, memory.getType());
        assertEquals(2024, memory.getYear());
        assertEquals("m1", memory.getId());
        assertEquals("Bursa'da bir gün", memory.getTitle());
        assertEquals("Kestane şekeri", memory.getDescription());
        assertEquals(DATE, memory.getDate());
    }

    @Test
    public void missingDescriptionBecomesEmptyText() {
        assertEquals("", MemoryListItem.memory("m1", "Başlık", null, DATE).getDescription());
    }

    @Test
    public void memoryRejectsMissingRequiredFields() {
        assertThrows(NullPointerException.class, () -> MemoryListItem.memory(null, "Başlık", "", DATE));
        assertThrows(NullPointerException.class, () -> MemoryListItem.memory("m1", null, "", DATE));
        assertThrows(NullPointerException.class, () -> MemoryListItem.memory("m1", "Başlık", "", null));
    }

    @Test
    public void keySeparatesHeadersFromMemories() {
        // A memory whose id looks like a year must not share the header's DiffUtil identity.
        assertEquals("year:2024", MemoryListItem.yearHeader(2024).getKey());
        assertEquals("memory:2024", MemoryListItem.memory("2024", "Başlık", "", DATE).getKey());
        assertNotEquals(MemoryListItem.yearHeader(2024).getKey(),
                MemoryListItem.memory("2024", "Başlık", "", DATE).getKey());
    }

    @Test
    public void keyStaysTheSameWhenContentChanges() {
        MemoryListItem before = MemoryListItem.memory("m1", "Eski başlık", "", DATE);
        MemoryListItem after = MemoryListItem.memory("m1", "Yeni başlık", "Açıklama", DATE.plusDays(1));

        assertEquals(before.getKey(), after.getKey());
        assertNotEquals(before, after);
    }

    @Test
    public void equalContentIsEqual() {
        MemoryListItem first = MemoryListItem.memory("m1", "Başlık", "Açıklama", DATE);
        MemoryListItem second = MemoryListItem.memory("m1", "Başlık", "Açıklama", DATE);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(MemoryListItem.yearHeader(2023), MemoryListItem.yearHeader(2023));
        assertNotEquals(MemoryListItem.yearHeader(2023), MemoryListItem.yearHeader(2024));
    }

    @Test
    public void toStringLeavesOutUserContent() {
        String text = MemoryListItem.memory("m1", "Gizli başlık", "Gizli açıklama", DATE).toString();

        assertTrue(text.contains("memory:m1"));
        assertFalse(text.contains("Gizli"));
    }
}
