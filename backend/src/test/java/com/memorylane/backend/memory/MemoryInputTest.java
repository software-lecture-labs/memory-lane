package com.memorylane.backend.memory;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ML-08 product rules for the memory create body, checked without a database. */
class MemoryInputTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    @Test
    void titleIsRequiredAndLimitedTo100Characters() {
        assertRejected(body(null), "Başlık zorunludur.");
        assertRejected(body("   "), "Başlık zorunludur.");
        assertEquals(EMOJI.repeat(100), MemoryInput.from(body(EMOJI.repeat(100)), CLOCK).title());
        assertEquals("Sahil", MemoryInput.from(body("  Sahil  "), CLOCK).title());
        assertRejected(body("a".repeat(101)), "Başlık en fazla 100 karakter olabilir.");
    }

    @Test
    void descriptionIsOptionalAndLimitedTo3000Characters() {
        assertEquals("", MemoryInput.from(body("Sahil"), CLOCK).description());
        var body = body("Sahil");
        body.put("description", EMOJI.repeat(3000));
        assertEquals(3000, MemoryInput.from(body, CLOCK).description().codePointCount(0, 6000));
        body.put("description", "a".repeat(3001));
        assertRejected(body, "Açıklama en fazla 3000 karakter olabilir.");
    }

    @Test
    void dateIsRequiredAndMayBeTodayOrPastOnly() {
        var body = body("Sahil");
        body.remove("memoryDate");
        assertRejected(body, "Anı tarihi YYYY-MM-DD biçiminde olmalıdır.");
        body.put("memoryDate", TODAY.toString());
        assertEquals(TODAY, MemoryInput.from(body, CLOCK).memoryDate());
        body.put("memoryDate", "2000-02-29");
        assertEquals(LocalDate.of(2000, 2, 29), MemoryInput.from(body, CLOCK).memoryDate());
        body.put("memoryDate", TODAY.plusDays(1).toString());
        assertRejected(body, "Anı tarihi gelecekte olamaz.");
    }

    @Test
    void categoryDefaultsToOtherAndRejectsUnknownCodes() {
        assertEquals("OTHER", MemoryInput.from(body("Sahil"), CLOCK).category());
        var body = body("Sahil");
        body.put("category", null);
        assertEquals("OTHER", MemoryInput.from(body, CLOCK).category());
        body.put("category", "");
        assertEquals("OTHER", MemoryInput.from(body, CLOCK).category());
        body.put("category", "TRAVEL");
        assertEquals("TRAVEL", MemoryInput.from(body, CLOCK).category());
        body.put("category", "Gezi");
        assertRejected(body, "Geçersiz kategori.");
    }

    @Test
    void emotionAndLocationAreOptionalButValidated() {
        var plain = MemoryInput.from(body("Sahil"), CLOCK);
        assertNull(plain.emotion());
        assertNull(plain.location());

        var body = body("Sahil");
        body.put("emotion", "HAPPY");
        body.put("location", Map.of("latitude", 41.0082, "longitude", 28.9784));
        var full = MemoryInput.from(body, CLOCK);
        assertEquals("HAPPY", full.emotion());
        assertEquals(new MemoryInput.Location(41.0082, 28.9784), full.location());

        body.put("emotion", "");
        assertNull(MemoryInput.from(body, CLOCK).emotion());
        body.put("emotion", "ANGRY");
        assertRejected(body, "Geçersiz duygu.");
        body.put("emotion", null);
        body.put("location", Map.of("latitude", 41.0));
        assertRejected(body, "Enlem ve boylam birlikte gönderilmelidir.");
        body.put("location", Map.of("latitude", 91, "longitude", 0));
        assertRejected(body, "Geçersiz koordinat.");
        body.put("location", Map.of("latitude", 0, "longitude", -181));
        assertRejected(body, "Geçersiz koordinat.");
    }

    private static Map<String, Object> body(String title) {
        var body = new HashMap<String, Object>();
        body.put("title", title);
        body.put("memoryDate", "2026-01-01");
        return body;
    }

    private static void assertRejected(Map<String, Object> body, String message) {
        var error = assertThrows(IllegalArgumentException.class, () -> MemoryInput.from(body, CLOCK));
        assertEquals(message, error.getMessage());
    }
}
