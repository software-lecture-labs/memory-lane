package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.Rule;
import org.junit.Test;

public class MemoryLocationPickerViewModelTest {
    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final MemoryLocationPickerViewModel viewModel = new MemoryLocationPickerViewModel();

    @Test
    public void opensOnTheFormsLocationAndOffersRemovingIt() {
        MemoryApi.Location current = new MemoryApi.Location(40.1885, 29.061);

        viewModel.start(current);

        assertEquals(current, viewModel.picked().getValue());
        assertTrue(viewModel.hadLocation());
    }

    @Test
    public void formWithoutLocationStartsWithNoPointAndNothingToRemove() {
        viewModel.start(null);

        assertNull(viewModel.picked().getValue());
        assertFalse(viewModel.hadLocation());
    }

    @Test
    public void tapPlacesThePointAndARecreatedViewKeepsIt() {
        viewModel.start(null);

        viewModel.pick(38.4237, 27.1428);
        viewModel.start(new MemoryApi.Location(1, 1));

        assertEquals(new MemoryApi.Location(38.4237, 27.1428), viewModel.picked().getValue());
    }

    @Test
    public void unusableTapKeepsThePreviousPoint() {
        viewModel.start(new MemoryApi.Location(41.0, 29.0));

        viewModel.pick(Double.NaN, 29.0);

        assertEquals(new MemoryApi.Location(41.0, 29.0), viewModel.picked().getValue());
    }
}
