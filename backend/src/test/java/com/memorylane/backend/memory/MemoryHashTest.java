package com.memorylane.backend.memory;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryHashTest {
    @Test
    void hashesCanonicalUtf8Json() {
        var input = new MemoryInput("Anı", "", LocalDate.of(2026, 1, 1), "OTHER",
                null, new MemoryInput.Location(-0.0, 12.50));
        assertEquals("29c552c053a4d546a535bf94b6ed59ccde702dd74ca0d9487a5b473b58a3c486", MemoryService.hash(input));
    }
}
