package com.memorylane.feature.calendar.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.memories.data.FakeMemoryListRepository;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryFixtures;

import org.junit.Rule;
import org.junit.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class MemoryCalendarViewModelTest {
    private static final String SESSION = "Bearer token-a";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final FakeMemoryListRepository repository = new FakeMemoryListRepository();
    private final MemoryCalendarViewModel viewModel = new MemoryCalendarViewModel(repository,
            Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    @Test
    public void readsEveryPageThenOpensOnTodayWithTheMemoriesByDate() {
        viewModel.setSession(SESSION);
        assertEquals(MemoryCalendarUiState.Status.LOADING, state().getStatus());

        repository.last().succeed(List.of(memory("m2", "2026-11-08")), true);
        assertEquals(1, repository.last().page);
        repository.last().succeed(List.of(memory("m1", "2025-08-17")), false);

        MemoryCalendarUiState state = state();
        assertEquals(MemoryCalendarUiState.Status.READY, state.getStatus());
        assertEquals(2, repository.requests.size());
        assertEquals(YearMonth.of(2026, 11), state.getMonth());
        assertEquals(TODAY, state.getSelected());
        assertEquals(2, state.getTotal());
        assertEquals(1, state.getMonthCount());
        assertEquals(LocalDate.of(2025, 8, 17), state.getFirstMemory());
        assertFalse(state.isTruncated());
    }

    @Test
    public void aMemoryRepeatedOnTheNextPageIsCountedOnce() {
        viewModel.setSession(SESSION);

        // Offset paging repeats a memory when another one was added between the two requests.
        repository.last().succeed(List.of(memory("m2", "2026-11-08")), true);
        repository.last().succeed(List.of(memory("m2", "2026-11-08"), memory("m1", "2026-11-01")), false);

        assertEquals(2, state().getTotal());
    }

    @Test
    public void anAnswerForAnEarlierSessionIsDropped() {
        viewModel.setSession(SESSION);
        FakeMemoryListRepository.Request oldSession = repository.last();

        viewModel.setSession("Bearer token-b");
        assertTrue(oldSession.isCancelled());
        oldSession.succeed(List.of(memory("old", "2026-11-08")), false);
        assertEquals(MemoryCalendarUiState.Status.LOADING, state().getStatus());

        repository.last().succeed(List.of(memory("new", "2026-11-02")), false);

        MemoryCalendarUiState state = state();
        assertEquals(MemoryCalendarUiState.Status.READY, state.getStatus());
        assertEquals(1, state.getTotal());
        assertEquals(LocalDate.of(2026, 11, 2), state.getFirstMemory());
    }

    @Test
    public void rejectedSessionTurnsTheCalendarSignedOutAndDropsTheMemories() {
        viewModel.setSession(SESSION);
        repository.last().succeed(List.of(memory("m1", "2026-11-08")), false);

        viewModel.reload();
        repository.last().fail(ApiError.http(401, "UNAUTHORIZED", "Oturum geçersiz."));

        MemoryCalendarUiState state = state();
        assertEquals(MemoryCalendarUiState.Status.SIGNED_OUT, state.getStatus());
        assertEquals(0, state.getTotal());
        assertNull(state.getError());
    }

    @Test
    public void otherFailuresShowTheError() {
        viewModel.setSession(SESSION);

        repository.last().fail(ApiError.network());

        assertEquals(MemoryCalendarUiState.Status.ERROR, state().getStatus());
    }

    @Test
    public void stopsAtTheSafetyLimitAndSaysTheCalendarIsTruncated() {
        viewModel.setSession(SESSION);

        for (int page = 0; page < MemoryCalendarViewModel.MAX_PAGES; page++) {
            assertEquals(page, repository.last().page);
            repository.last().succeed(List.of(memory("m" + page, "2026-11-08")), true);
        }

        MemoryCalendarUiState state = state();
        assertEquals(MemoryCalendarViewModel.MAX_PAGES, repository.requests.size());
        assertEquals(MemoryCalendarUiState.Status.READY, state.getStatus());
        assertTrue(state.isTruncated());
    }

    @Test
    public void aRefreshKeepsTheMonthTheUserWasLookingAt() {
        viewModel.setSession(SESSION);
        repository.last().succeed(List.of(memory("m1", "2026-11-08")), false);
        viewModel.previousMonth();

        viewModel.setSession(SESSION); // the tab came back: same session, read again quietly
        repository.last().succeed(new ArrayList<>(List.of(memory("m1", "2026-11-08"))), false);

        assertEquals(YearMonth.of(2026, 10), state().getMonth());
    }

    private static MemoryApi.Memory memory(String id, String date) {
        return MemoryFixtures.memory(id, "Anı " + id, "", date);
    }

    private MemoryCalendarUiState state() {
        return viewModel.getState().getValue();
    }
}
