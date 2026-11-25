package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.memorylane.core.network.ApiError;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MemoryListUiStateTest {
    private static final List<MemoryListItem> ROWS = List.of(
            MemoryListItem.yearHeader(2024),
            MemoryListItem.memory("m1", "Bursa'da bir gün", "", LocalDate.of(2024, 10, 28)));

    @Test
    public void statesWithoutRowsHaveNoRowsOrPaging() {
        for (MemoryListUiState state : new MemoryListUiState[] {
                MemoryListUiState.signedOut(), MemoryListUiState.loading(), MemoryListUiState.empty()}) {
            assertTrue(state.getStatus().name(), state.getItems().isEmpty());
            assertFalse(state.hasMore());
            assertFalse(state.isLoadingMore());
            assertFalse(state.canLoadMore());
            assertNull(state.getError());
        }
        assertEquals(MemoryListUiState.Status.SIGNED_OUT, MemoryListUiState.signedOut().getStatus());
        assertEquals(MemoryListUiState.Status.LOADING, MemoryListUiState.loading().getStatus());
        assertEquals(MemoryListUiState.Status.EMPTY, MemoryListUiState.empty().getStatus());
    }

    @Test
    public void firstLoadErrorIsErrorAndNeverEmpty() {
        ApiError failure = ApiError.network();
        MemoryListUiState state = MemoryListUiState.error(failure);

        assertEquals(MemoryListUiState.Status.ERROR, state.getStatus());
        assertSame(failure, state.getError());
        assertTrue(state.getItems().isEmpty());
        assertFalse(state.canLoadMore());
    }

    @Test
    public void errorRequiresTheError() {
        assertThrows(NullPointerException.class, () -> MemoryListUiState.error(null));
    }

    @Test
    public void contentKeepsRowsAndPaging() {
        MemoryListUiState state = MemoryListUiState.content(ROWS, true);

        assertEquals(MemoryListUiState.Status.CONTENT, state.getStatus());
        assertEquals(ROWS, state.getItems());
        assertTrue(state.hasMore());
        assertFalse(state.isLoadingMore());
        assertTrue(state.canLoadMore());
        assertNull(state.getError());
    }

    @Test
    public void lastPageOffersNoLoadMore() {
        assertFalse(MemoryListUiState.content(ROWS, false).canLoadMore());
    }

    @Test
    public void contentWithoutRowsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MemoryListUiState.content(Collections.emptyList(), false));
    }

    @Test
    public void rowsAreACopyAndReadOnly() {
        List<MemoryListItem> source = new ArrayList<>(ROWS);
        MemoryListUiState state = MemoryListUiState.content(source, false);

        source.clear();
        assertEquals(2, state.getItems().size());
        assertThrows(UnsupportedOperationException.class,
                () -> state.getItems().add(MemoryListItem.yearHeader(2023)));
    }

    @Test
    public void loadingMoreKeepsRowsAndHidesTheButton() {
        MemoryListUiState state = MemoryListUiState.content(ROWS, true).withLoadingMore();

        assertEquals(MemoryListUiState.Status.CONTENT, state.getStatus());
        assertEquals(ROWS, state.getItems());
        assertTrue(state.isLoadingMore());
        assertTrue(state.hasMore());
        assertFalse(state.canLoadMore());
    }

    @Test
    public void loadMoreFailureKeepsRowsAndAllowsRetry() {
        ApiError failure = ApiError.http(503, "DATABASE_UNAVAILABLE", null);
        MemoryListUiState state = MemoryListUiState.content(ROWS, true)
                .withLoadingMore()
                .withLoadMoreError(failure);

        assertEquals(MemoryListUiState.Status.CONTENT, state.getStatus());
        assertEquals(ROWS, state.getItems());
        assertSame(failure, state.getError());
        assertFalse(state.isLoadingMore());
        assertTrue(state.canLoadMore());
    }

    @Test
    public void retryingLoadMoreClearsTheEarlierError() {
        MemoryListUiState state = MemoryListUiState.content(ROWS, true)
                .withLoadMoreError(ApiError.network())
                .withLoadingMore();

        assertNull(state.getError());
        assertTrue(state.isLoadingMore());
    }

    @Test
    public void onlyContentCanLoadMore() {
        for (MemoryListUiState state : new MemoryListUiState[] {
                MemoryListUiState.signedOut(), MemoryListUiState.loading(), MemoryListUiState.empty(),
                MemoryListUiState.error(ApiError.network())}) {
            assertThrows(state.getStatus().name(), IllegalStateException.class, state::withLoadingMore);
            assertThrows(state.getStatus().name(), IllegalStateException.class,
                    () -> state.withLoadMoreError(ApiError.network()));
        }
    }

    @Test
    public void transitionsDoNotChangeTheOriginalState() {
        MemoryListUiState original = MemoryListUiState.content(ROWS, true);
        original.withLoadingMore();

        assertFalse(original.isLoadingMore());
    }
}
