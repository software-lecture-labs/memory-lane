package com.memorylane.backend.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** If-Match rules of the memory PUT (docs/api-contract.md), checked without a database. */
class MemoryPreconditionTest {
    private static final UUID ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String CURRENT = "\"memory-22222222-2222-4222-8222-222222222222-r7\"";

    @Test
    void tagIsStrongQuotedAndNamesTheMemoryAndVersion() {
        assertEquals(CURRENT, MemoryPrecondition.tag(ID, 7));
    }

    @Test
    void currentTagMatchesAlsoWithSurroundingSpaces() {
        assertDoesNotThrow(() -> MemoryPrecondition.check(CURRENT, ID, 7));
        assertDoesNotThrow(() -> MemoryPrecondition.check("  " + CURRENT + " ", ID, 7));
    }

    @Test
    void missingOrBlankHeaderIsRequired() {
        for (String header : new String[] {null, "", "   "}) {
            assertThrows(MemoryPrecondition.Required.class, () -> MemoryPrecondition.check(header, ID, 7));
        }
    }

    @Test
    void wildcardListsAndForeignOrMalformedTagsAreInvalid() {
        UUID other = UUID.fromString("33333333-3333-4333-8333-333333333333");
        for (String header : List.of(
                "*",
                CURRENT + ", " + MemoryPrecondition.tag(ID, 8),
                "memory-22222222-2222-4222-8222-222222222222-r7",
                "\"memory-22222222-2222-4222-8222-222222222222-r7",
                "\"memory-22222222-2222-4222-8222-222222222222-r0\"",
                "\"memory-22222222-2222-4222-8222-222222222222-rx\"",
                "\"MEMORY-22222222-2222-4222-8222-222222222222-r7\"",
                "\"memory-22222222-2222-4222-8222-222222222222-r7\"x",
                "\"anything\"",
                "W/anything",
                MemoryPrecondition.tag(other, 7))) {
            assertThrows(MemoryPrecondition.Invalid.class, () -> MemoryPrecondition.check(header, ID, 7), header);
        }
    }

    @Test
    void olderNewerAndWeakTagsOfThisMemoryAreModified() {
        for (String header : List.of(MemoryPrecondition.tag(ID, 6), MemoryPrecondition.tag(ID, 8), "W/" + CURRENT)) {
            assertThrows(MemoryPrecondition.Modified.class, () -> MemoryPrecondition.check(header, ID, 7), header);
        }
    }
}
