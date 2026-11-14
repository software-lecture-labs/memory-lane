package com.memorylane.backend.memory;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

record MemoryInput(String title, String description, LocalDate memoryDate, String category,
                   String emotion, Location location) {
    private static final Set<String> FIELDS = Set.of(
            "title", "description", "memoryDate", "category", "emotion", "location");
    private static final Set<String> CATEGORIES = Set.of(
            "TRAVEL", "FAMILY", "FRIENDS", "SCHOOL", "DAILY_LIFE", "OTHER");
    private static final Set<String> EMOTIONS = Set.of(
            "HAPPY", "PEACEFUL", "EXCITED", "NOSTALGIC", "SAD");

    record Location(double latitude, double longitude) {}

    static MemoryInput from(Map<String, Object> body, Clock clock) {
        if (!FIELDS.containsAll(body.keySet())) throw new IllegalArgumentException("Bilinmeyen anı alanı.");
        String title = body.get("title") instanceof String value ? value.strip() : "";
        if (title.isEmpty()) throw new IllegalArgumentException("Başlık zorunludur.");
        text(title, "", 100, "Başlık");
        String description = text(body.get("description"), "", 3000, "Açıklama");
        String day = text(body.get("memoryDate"), "", 10, "Anı tarihi");
        LocalDate date;
        try {
            date = LocalDate.parse(day);
            if (date.getYear() < 1) throw new DateTimeParseException("year", day, 0);
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Anı tarihi YYYY-MM-DD biçiminde olmalıdır.");
        }
        if (date.isAfter(LocalDate.now(clock))) throw new IllegalArgumentException("Anı tarihi gelecekte olamaz.");
        String category = text(body.get("category"), "", 20, "Kategori");
        category = category.isEmpty() ? "OTHER" : category(category);
        String emotion = text(body.get("emotion"), null, 20, "Duygu");
        if ("".equals(emotion)) emotion = null;
        if (emotion != null && !EMOTIONS.contains(emotion)) {
            throw new IllegalArgumentException("Geçersiz duygu.");
        }
        return new MemoryInput(title, description, date, category, emotion, location(body.get("location")));
    }

    static String category(String value) {
        if (!CATEGORIES.contains(value)) throw new IllegalArgumentException("Geçersiz kategori.");
        return value;
    }

    static String text(Object value, String fallback, int max, String label) {
        if (value == null) return fallback;
        if (!(value instanceof String text) || text.indexOf(0) >= 0
                || text.codePointCount(0, text.length()) > max) {
            throw new IllegalArgumentException(label + " en fazla " + max + " karakter olabilir.");
        }
        return text;
    }

    private static Location location(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> pair) || pair.size() != 2) {
            throw new IllegalArgumentException("Enlem ve boylam birlikte gönderilmelidir.");
        }
        return new Location(coordinate(pair.get("latitude"), 90), coordinate(pair.get("longitude"), 180));
    }

    private static double coordinate(Object value, int limit) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || Math.abs(number.doubleValue()) > limit) {
            throw new IllegalArgumentException("Geçersiz koordinat.");
        }
        return number.doubleValue() == 0 ? 0 : number.doubleValue();
    }
}
