package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.memories.data.FakeMemoryDetailRepository;
import com.memorylane.feature.memories.data.FakeMemoryEditRepository;
import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.Rule;
import org.junit.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** ML-15 acceptance on the edit form: adding, changing and removing a location reaches the PUT. */
public class MemoryEditLocationTest {
    private static final String SESSION = "Bearer token-a";
    private static final String MEMORY = "m1";
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 11, 26).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final MemoryApi.Location BURSA = new MemoryApi.Location(40.1885, 29.061);
    private static final MemoryApi.Location IZMIR = new MemoryApi.Location(38.4237, 27.1428);

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final FakeMemoryDetailRepository details = new FakeMemoryDetailRepository();
    private final FakeMemoryEditRepository editor = new FakeMemoryEditRepository();
    private final MemoryEditViewModel viewModel = new MemoryEditViewModel(details, editor, MEMORY, CLOCK);

    @Test
    public void formShowsTheMemorysLocation() {
        open(FakeMemoryDetailRepository.fullMemory(MEMORY));

        assertEquals(BURSA, state().getLocation());
        assertFalse(viewModel.hasUnsavedChanges());
    }

    @Test
    public void changedLocationIsAnUnsavedChangeAndIsSent() {
        open(FakeMemoryDetailRepository.fullMemory(MEMORY));

        viewModel.onLocationPicked(IZMIR);
        assertTrue(viewModel.hasUnsavedChanges());
        viewModel.save();

        assertEquals(IZMIR, editor.last().draft.location());
        assertEquals("emotion is still sent back unchanged", "PEACEFUL", editor.last().draft.emotion());
    }

    @Test
    public void removedLocationIsSentAsNone() {
        open(FakeMemoryDetailRepository.fullMemory(MEMORY));

        viewModel.onLocationPicked(null);
        viewModel.save();

        assertNull(state().getLocation());
        assertNull(editor.last().draft.location());
    }

    @Test
    public void locationCanBeAddedToAMemoryWithoutOne() {
        open(FakeMemoryDetailRepository.plainMemory(MEMORY));

        viewModel.onLocationPicked(IZMIR);
        viewModel.save();

        assertEquals(IZMIR, editor.last().draft.location());
    }

    @Test
    public void pickingTheSameLocationAgainIsNoChange() {
        open(FakeMemoryDetailRepository.fullMemory(MEMORY));

        viewModel.onLocationPicked(new MemoryApi.Location(40.1885, 29.061));

        assertFalse(viewModel.hasUnsavedChanges());
    }

    @Test
    public void unusableLocationIsIgnored() {
        open(FakeMemoryDetailRepository.fullMemory(MEMORY));

        viewModel.onLocationPicked(new MemoryApi.Location(120, 29));

        assertEquals(BURSA, state().getLocation());
    }

    @Test
    public void afterAConflictTheDraftKeepsItsLocationOrTakesTheLatestOne() {
        open(FakeMemoryDetailRepository.fullMemory(MEMORY));
        viewModel.onLocationPicked(IZMIR);
        viewModel.save();
        editor.last().fail(ApiError.http(412, "MEMORY_MODIFIED", "Anı başka bir işlemle değişti."));
        viewModel.reloadLatest();
        MemoryApi.Detail full = FakeMemoryDetailRepository.fullMemory(MEMORY);
        MemoryApi.Location elsewhere = new MemoryApi.Location(41.0082, 28.9784);
        details.last().succeed(new MemoryApi.Detail(MEMORY, full.title(), full.description(), full.memoryDate(),
                full.category(), full.emotion(), elsewhere, null, null, 2, "p1", full.photos()), "\"memory-m1-r2\"");

        assertEquals("the draft is not overwritten by the reload", IZMIR, state().getLocation());
        viewModel.useLatest();
        assertEquals(elsewhere, state().getLocation());
        assertFalse(viewModel.hasUnsavedChanges());
    }

    @Test
    public void noChangeWhileTheFormIsNotReady() {
        viewModel.start(SESSION);

        viewModel.onLocationPicked(IZMIR);

        assertEquals(MemoryEditUiState.Status.LOADING, state().getStatus());
        assertNull(state().getLocation());
    }

    private void open(MemoryApi.Detail memory) {
        viewModel.start(SESSION);
        details.last().succeed(memory);
    }

    private MemoryEditUiState state() {
        return viewModel.getState().getValue();
    }
}
