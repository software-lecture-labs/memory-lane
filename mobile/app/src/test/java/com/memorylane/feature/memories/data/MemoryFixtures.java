package com.memorylane.feature.memories.data;

/**
 * Short ways to build memory DTOs in tests that only care about the fields the list and the
 * create form show. Every other field has the value the server sends for a plain memory.
 */
public final class MemoryFixtures {
    private MemoryFixtures() {
    }

    /** A list row without photos, category OTHER, no emotion or location. */
    public static MemoryApi.Memory memory(String id, String title, String description, String memoryDate) {
        return new MemoryApi.Memory(id, title, description, memoryDate, "OTHER", null, null,
                "2026-01-01T09:00:00Z", "2026-01-01T09:00:00Z", 0, null);
    }

    /** The create form's draft: no category, emotion or location (server defaults). */
    public static MemoryApi.Draft draft(String title, String description, String memoryDate) {
        return new MemoryApi.Draft(title, description, memoryDate, null, null, null);
    }
}
