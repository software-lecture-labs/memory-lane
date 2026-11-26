package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.memorylane.R;
import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MemoryLabelsTest {
    @Test
    public void everyContractCategoryHasItsOwnLabel() {
        Set<Integer> labels = new HashSet<>();
        for (String code : List.of("TRAVEL", "FAMILY", "FRIENDS", "SCHOOL", "DAILY_LIFE", "OTHER")) {
            int label = MemoryLabels.category(code);
            assertNotEquals(code, MemoryLabels.NONE, label);
            labels.add(label);
        }
        assertEquals(6, labels.size());
        // The contract names these two pairs explicitly.
        assertEquals(R.string.memory_category_travel, MemoryLabels.category("TRAVEL"));
        assertEquals(R.string.memory_category_other, MemoryLabels.category("OTHER"));
    }

    @Test
    public void everyContractEmotionHasItsOwnLabel() {
        Set<Integer> labels = new HashSet<>();
        for (String code : List.of("HAPPY", "PEACEFUL", "EXCITED", "NOSTALGIC", "SAD")) {
            int label = MemoryLabels.emotion(code);
            assertNotEquals(code, MemoryLabels.NONE, label);
            labels.add(label);
        }
        assertEquals(5, labels.size());
        assertEquals(R.string.memory_emotion_happy, MemoryLabels.emotion("HAPPY"));
    }

    @Test
    public void formsOfferExactlyTheContractCodesInOrder() {
        assertEquals(List.of("TRAVEL", "FAMILY", "FRIENDS", "SCHOOL", "DAILY_LIFE", "OTHER"),
                codes(MemoryLabels.CATEGORIES));
        assertEquals(List.of("HAPPY", "PEACEFUL", "EXCITED", "NOSTALGIC", "SAD"), codes(MemoryLabels.EMOTIONS));
        assertEquals("OTHER", MemoryLabels.DEFAULT_CATEGORY);
        // Each choice carries the same label the badges show.
        for (MemoryLabels.Choice choice : MemoryLabels.CATEGORIES) {
            assertEquals(choice.code(), choice.label(), MemoryLabels.category(choice.code()));
        }
    }

    @Test
    public void containsAcceptsOnlyCodesOfThatList() {
        assertTrue(MemoryLabels.contains(MemoryLabels.CATEGORIES, "DAILY_LIFE"));
        assertFalse(MemoryLabels.contains(MemoryLabels.CATEGORIES, "HAPPY"));
        assertTrue(MemoryLabels.contains(MemoryLabels.EMOTIONS, "NOSTALGIC"));
        assertFalse(MemoryLabels.contains(MemoryLabels.EMOTIONS, null));
        assertFalse(MemoryLabels.contains(MemoryLabels.CATEGORIES, "travel"));
    }

    @Test
    public void missingOrUnknownCodesShowNoBadge() {
        assertEquals(MemoryLabels.NONE, MemoryLabels.category(null));
        assertEquals(MemoryLabels.NONE, MemoryLabels.category("travel"));
        assertEquals(MemoryLabels.NONE, MemoryLabels.category("Gezi"));
        assertEquals(MemoryLabels.NONE, MemoryLabels.emotion(null));
        assertEquals(MemoryLabels.NONE, MemoryLabels.emotion("ANGRY"));
    }

    @Test
    public void coordinatesUseTurkishDecimalsAndCompassLetters() {
        assertEquals("41,0082° K, 28,9784° D",
                MemoryLabels.coordinates(new MemoryApi.Location(41.0082, 28.9784), "K", "G", "D", "B"));
        assertEquals("33,8688° G, 151,2093° D",
                MemoryLabels.coordinates(new MemoryApi.Location(-33.8688, 151.2093), "K", "G", "D", "B"));
        assertEquals("0,0000° K, 74,0060° B",
                MemoryLabels.coordinates(new MemoryApi.Location(0, -74.006), "K", "G", "D", "B"));
        assertThrows(IllegalArgumentException.class,
                () -> MemoryLabels.coordinates(new MemoryApi.Location(0, 0), "K", "G"));
    }

    private static List<String> codes(List<MemoryLabels.Choice> choices) {
        List<String> codes = new ArrayList<>();
        for (MemoryLabels.Choice choice : choices) codes.add(choice.code());
        return codes;
    }
}
