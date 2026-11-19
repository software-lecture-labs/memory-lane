package com.memorylane.feature.memories.ui;

import androidx.annotation.StringRes;

import com.memorylane.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** API codes for category and emotion with their Turkish labels; codes are sent, labels are shown. */
final class MemoryChoices {
    record Choice(String code, @StringRes int label) {}

    static final String DEFAULT_CATEGORY = "OTHER";

    static final List<Choice> CATEGORIES = Collections.unmodifiableList(Arrays.asList(
            new Choice("TRAVEL", R.string.memory_category_travel),
            new Choice("FAMILY", R.string.memory_category_family),
            new Choice("FRIENDS", R.string.memory_category_friends),
            new Choice("SCHOOL", R.string.memory_category_school),
            new Choice("DAILY_LIFE", R.string.memory_category_daily_life),
            new Choice(DEFAULT_CATEGORY, R.string.memory_category_other)));

    static final List<Choice> EMOTIONS = Collections.unmodifiableList(Arrays.asList(
            new Choice("HAPPY", R.string.memory_emotion_happy),
            new Choice("PEACEFUL", R.string.memory_emotion_peaceful),
            new Choice("EXCITED", R.string.memory_emotion_excited),
            new Choice("NOSTALGIC", R.string.memory_emotion_nostalgic),
            new Choice("SAD", R.string.memory_emotion_sad)));

    private MemoryChoices() {
    }

    static boolean contains(List<Choice> choices, String code) {
        for (Choice choice : choices) {
            if (choice.code().equals(code)) return true;
        }
        return false;
    }
}
