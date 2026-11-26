package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The viewer opens at the tapped photo; a position outside the list never breaks it. */
public class MemoryPhotoViewerFragmentTest {
    @Test
    public void tappedPhotoIsTheFirstPage() {
        assertEquals(0, MemoryPhotoViewerFragment.startPosition(0, 4));
        assertEquals(2, MemoryPhotoViewerFragment.startPosition(2, 4));
        assertEquals(3, MemoryPhotoViewerFragment.startPosition(3, 4));
    }

    @Test
    public void positionOutsideTheListIsKeptInside() {
        assertEquals(3, MemoryPhotoViewerFragment.startPosition(9, 4));
        assertEquals(0, MemoryPhotoViewerFragment.startPosition(-1, 4));
        assertEquals(0, MemoryPhotoViewerFragment.startPosition(5, 1));
        assertEquals(0, MemoryPhotoViewerFragment.startPosition(2, 0));
    }
}
