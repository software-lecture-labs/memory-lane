package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.health.ui.HealthViewModel;
import com.memorylane.feature.memories.data.FakeMemoryListRepository;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryFixtures;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class MemoriesViewModelTest {
    private static final String SESSION_A = "Bearer token-a";
    private static final String SESSION_B = "Bearer token-b";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final FakeMemoryListRepository repository = new FakeMemoryListRepository();
    private final MemoriesViewModel viewModel = new MemoriesViewModel(repository);

    // --- Session ---

    @Test
    public void withoutSessionItIsSignedOutAndRequestsNothing() {
        viewModel.setSession(null);

        assertEquals(MemoryListUiState.Status.SIGNED_OUT, status());
        assertTrue(repository.requests.isEmpty());
        viewModel.refresh();
        viewModel.loadMore();
        assertTrue(repository.requests.isEmpty());
    }

    @Test
    public void sameSessionAgainDoesNotReload() {
        signIn();
        repository.last().succeed(FakeMemoryListRepository.sampleMemories(), false);

        // A recreated view binds again with the same token.
        viewModel.setSession(SESSION_A);

        assertEquals(1, repository.requests.size());
        assertEquals(MemoryListUiState.Status.CONTENT, status());
    }

    @Test
    public void newSessionCancelsTheOldRequestAndLoadsAgain() {
        signIn();
        FakeMemoryListRepository.Request old = repository.last();

        viewModel.setSession(SESSION_B);

        assertTrue(old.isCancelled());
        assertEquals(2, repository.requests.size());
        assertEquals(SESSION_B, repository.last().authorization);
        assertEquals(MemoryListUiState.Status.LOADING, status());
        old.succeed(FakeMemoryListRepository.sampleMemories(), false);
        assertEquals("The old account's answer must be dropped", MemoryListUiState.Status.LOADING, status());
    }

    @Test
    public void signingOutCancelsAndDropsTheList() {
        signIn();
        repository.last().succeed(FakeMemoryListRepository.sampleMemories(), true);
        viewModel.loadMore();
        FakeMemoryListRepository.Request pending = repository.last();

        viewModel.setSession(null);

        assertTrue(pending.isCancelled());
        assertEquals(MemoryListUiState.Status.SIGNED_OUT, status());
        assertTrue(state().getItems().isEmpty());
    }

    // --- First load ---

    @Test
    public void signingInShowsLoadingAndRequestsTheFirstPage() {
        signIn();

        assertEquals(MemoryListUiState.Status.LOADING, status());
        assertEquals(SESSION_A, repository.last().authorization);
        assertEquals(0, repository.last().page);
    }

    @Test
    public void firstPageBecomesRowsWithYearHeaders() {
        signIn();
        repository.last().succeed(FakeMemoryListRepository.sampleMemories(), true);

        assertEquals(MemoryListUiState.Status.CONTENT, status());
        assertEquals(List.of("year:2024", "memory:m3", "memory:m2", "year:2023", "memory:m1"),
                keys(state().getItems()));
        assertTrue(state().canLoadMore());
    }

    @Test
    public void noMemoriesIsEmpty() {
        signIn();
        repository.last().succeed(List.of(), false);

        assertEquals(MemoryListUiState.Status.EMPTY, status());
    }

    @Test
    public void firstPageFailureIsErrorNotEmpty() {
        ApiError failure = ApiError.network();
        signIn();
        repository.last().fail(failure);

        assertEquals(MemoryListUiState.Status.ERROR, status());
        assertSame(failure, state().getError());
    }

    @Test
    public void missingPageContentIsAParseError() {
        signIn();
        repository.last().succeed(null, false);

        assertEquals(MemoryListUiState.Status.ERROR, status());
        assertEquals(ApiError.Kind.PARSE, state().getError().getKind());
    }

    @Test
    public void memoryOutsideTheContractIsAParseError() {
        signIn();
        repository.last().succeed(List.of(MemoryFixtures.memory("m1", "Başlık", "", "28.10.2024")), false);

        assertEquals(MemoryListUiState.Status.ERROR, status());
        assertEquals(ApiError.Kind.PARSE, state().getError().getKind());
    }

    @Test
    public void retryAfterFirstPageFailureLoadsTheFirstPageAgain() {
        signIn();
        repository.last().fail(ApiError.network());

        viewModel.retry();

        assertEquals(MemoryListUiState.Status.LOADING, status());
        assertEquals(2, repository.requests.size());
        assertEquals(0, repository.last().page);
        repository.last().succeed(FakeMemoryListRepository.sampleMemories(), false);
        assertEquals(MemoryListUiState.Status.CONTENT, status());
    }

    // --- Load more ---

    @Test
    public void loadMoreAppendsTheNextPageUnderOneYearHeader() {
        signIn();
        repository.last().succeed(List.of(memory("p1", "2024-06-01")), true);

        viewModel.loadMore();
        assertTrue(state().isLoadingMore());
        assertEquals(1, repository.last().page);
        repository.last().succeed(List.of(memory("p2", "2024-01-15"), memory("p3", "2023-11-30")), false);

        assertEquals(List.of("year:2024", "memory:p1", "memory:p2", "year:2023", "memory:p3"),
                keys(state().getItems()));
        assertFalse(state().isLoadingMore());
        assertFalse(state().hasMore());
    }

    @Test
    public void loadMoreFailureKeepsTheRowsAndRetryAsksForTheSamePage() {
        signIn();
        repository.last().succeed(List.of(memory("p1", "2024-06-01")), true);
        List<MemoryListItem> shown = state().getItems();
        ApiError failure = ApiError.http(503, "DATABASE_UNAVAILABLE", null);

        viewModel.loadMore();
        repository.last().fail(failure);

        assertEquals(MemoryListUiState.Status.CONTENT, status());
        assertEquals(shown, state().getItems());
        assertSame(failure, state().getError());
        assertTrue(state().canLoadMore());

        viewModel.retry();
        assertEquals(1, repository.last().page);
        repository.last().succeed(List.of(memory("p2", "2024-01-15")), false);
        assertEquals(List.of("year:2024", "memory:p1", "memory:p2"), keys(state().getItems()));
        assertNull(state().getError());
    }

    @Test
    public void tappingLoadMoreTwiceSendsOneRequest() {
        signIn();
        repository.last().succeed(List.of(memory("p1", "2024-06-01")), true);

        viewModel.loadMore();
        viewModel.loadMore();

        assertEquals(2, repository.requests.size());
    }

    @Test
    public void lastPageOffersNoMore() {
        signIn();
        repository.last().succeed(List.of(memory("p1", "2024-06-01")), false);

        viewModel.loadMore();

        assertEquals(1, repository.requests.size());
    }

    @Test
    public void loadMoreIsIgnoredWhileTheFirstPageLoads() {
        signIn();

        viewModel.loadMore();

        assertEquals(1, repository.requests.size());
    }

    // --- Refresh ---

    @Test
    public void refreshKeepsRowsVisibleThenReplacesThemAndRestartsPaging() {
        signIn();
        repository.last().succeed(List.of(memory("p1", "2024-06-01")), true);
        viewModel.loadMore();
        repository.last().succeed(List.of(memory("p2", "2024-01-15")), true);

        viewModel.refresh();
        assertEquals(0, repository.last().page);
        assertEquals("Rows stay while refreshing", 3, state().getItems().size());

        repository.last().succeed(List.of(memory("new", "2024-07-01")), true);
        assertEquals(List.of("year:2024", "memory:new"), keys(state().getItems()));

        viewModel.loadMore();
        assertEquals("Paging starts again after a refresh", 1, repository.last().page);
    }

    /** After an edit the timeline refreshes; its rows must show the saved values, not the old ones. */
    @Test
    public void refreshAfterAnEditShowsTheSavedTitleCategoryCoverAndYear() {
        signIn();
        repository.last().succeed(List.of(
                new MemoryApi.Memory("m1", "Eski başlık", "", "2025-03-01", "OTHER", null, null,
                        null, null, 0, null),
                memory("m2", "2024-05-01")), false);
        assertEquals(List.of("year:2025", "memory:m1", "year:2024", "memory:m2"), keys(state().getItems()));

        viewModel.refresh();
        repository.last().succeed(List.of(
                memory("m2", "2024-05-01"),
                new MemoryApi.Memory("m1", "Yeni başlık", "Not", "2023-08-20", "TRAVEL", "HAPPY", null,
                        null, null, 2, "p1")), false);

        // The edited date moved the memory into another year.
        assertEquals(List.of("year:2024", "memory:m2", "year:2023", "memory:m1"), keys(state().getItems()));
        MemoryListItem edited = state().getItems().get(3);
        assertEquals("Yeni başlık", edited.getTitle());
        assertEquals("TRAVEL", edited.getCategory());
        assertEquals("HAPPY", edited.getEmotion());
        assertEquals("p1", edited.getCoverPhotoId());
        assertEquals(2, edited.getPhotoCount());
    }

    @Test
    public void refreshCancelsAPendingLoadMoreAndItsLateAnswerIsIgnored() {
        signIn();
        repository.last().succeed(List.of(memory("p1", "2024-06-01")), true);
        viewModel.loadMore();
        FakeMemoryListRepository.Request nextPage = repository.last();

        viewModel.refresh();
        assertFalse("The cancelled page's progress is gone", state().isLoadingMore());
        FakeMemoryListRepository.Request refresh = repository.last();
        nextPage.succeed(List.of(memory("stale", "2024-01-01")), false);
        refresh.succeed(List.of(memory("fresh", "2024-08-01")), false);

        assertTrue(nextPage.isCancelled());
        assertEquals(List.of("year:2024", "memory:fresh"), keys(state().getItems()));
        assertFalse(state().isLoadingMore());
    }

    @Test
    public void loadMoreIsIgnoredWhileRefreshing() {
        signIn();
        repository.last().succeed(List.of(memory("p1", "2024-06-01")), true);
        viewModel.refresh();

        viewModel.loadMore();

        assertEquals(2, repository.requests.size());
        assertEquals(0, repository.last().page);
    }

    @Test
    public void refreshFailureKeepsRowsAndRetryRefreshesAgain() {
        signIn();
        repository.last().succeed(List.of(memory("p1", "2024-06-01")), false);
        List<MemoryListItem> shown = state().getItems();

        viewModel.refresh();
        repository.last().fail(ApiError.network());

        assertEquals(MemoryListUiState.Status.CONTENT, status());
        assertEquals(shown, state().getItems());
        assertEquals(ApiError.Kind.NETWORK, state().getError().getKind());

        viewModel.retry();
        assertEquals(0, repository.last().page);
    }

    @Test
    public void refreshFromAnEmptyListShowsLoading() {
        signIn();
        repository.last().succeed(List.of(), false);

        viewModel.refresh();

        assertEquals(MemoryListUiState.Status.LOADING, status());
    }

    // --- Session expiry ---

    @Test
    public void unauthorizedFirstPageEndsTheSession() {
        signIn();
        repository.last().fail(unauthorized());

        assertSignedOutAfterExpiry();
    }

    @Test
    public void unauthorizedLoadMoreEndsTheSessionAndDropsTheRows() {
        signIn();
        repository.last().succeed(FakeMemoryListRepository.sampleMemories(), true);
        viewModel.loadMore();
        repository.last().fail(unauthorized());

        assertSignedOutAfterExpiry();
        assertTrue(state().getItems().isEmpty());
    }

    @Test
    public void unauthorizedRefreshEndsTheSession() {
        signIn();
        repository.last().succeed(FakeMemoryListRepository.sampleMemories(), false);
        viewModel.refresh();
        repository.last().fail(unauthorized());

        assertSignedOutAfterExpiry();
    }

    @Test
    public void afterExpiryNothingIsRequestedUntilANewSession() {
        signIn();
        repository.last().fail(unauthorized());

        viewModel.refresh();
        viewModel.retry();
        assertEquals(1, repository.requests.size());

        viewModel.consumeSessionExpired();
        assertEquals(Boolean.FALSE, viewModel.sessionExpired().getValue());
        viewModel.setSession(SESSION_B);
        assertEquals(2, repository.requests.size());
    }

    @Test
    public void forbiddenIsAnErrorNotASignOut() {
        signIn();
        repository.last().fail(ApiError.http(403, "FORBIDDEN", null));

        assertEquals(MemoryListUiState.Status.ERROR, status());
        assertEquals(Boolean.FALSE, viewModel.sessionExpired().getValue());
    }

    // --- Lifecycle ---

    @Test
    public void clearingTheViewModelCancelsThePendingRequest() {
        signIn();

        viewModel.onCleared();

        assertTrue(repository.last().isCancelled());
    }

    @Test
    public void expiredSessionAnswerAfterSignOutIsIgnored() {
        signIn();
        FakeMemoryListRepository.Request pending = repository.last();
        viewModel.setSession(null);

        pending.fail(unauthorized());

        assertEquals(Boolean.FALSE, viewModel.sessionExpired().getValue());
    }

    @Test
    public void factoryCreatesOnlyThisViewModel() {
        ViewModelProvider.Factory factory = MemoriesViewModel.factory(repository);

        assertNotNull(factory.create(MemoriesViewModel.class));
        assertThrows(IllegalArgumentException.class, () -> factory.create(HealthViewModel.class));
    }

    private void signIn() {
        viewModel.setSession(SESSION_A);
    }

    private void assertSignedOutAfterExpiry() {
        assertEquals(MemoryListUiState.Status.SIGNED_OUT, status());
        assertEquals(Boolean.TRUE, viewModel.sessionExpired().getValue());
        assertTrue(state().getItems().isEmpty());
    }

    private MemoryListUiState state() { return viewModel.getState().getValue(); }

    private MemoryListUiState.Status status() { return state().getStatus(); }

    private static ApiError unauthorized() {
        return ApiError.http(401, "INVALID_SESSION", "Geçerli oturum gereklidir.");
    }

    private static MemoryApi.Memory memory(String id, String date) {
        return MemoryFixtures.memory(id, "Anı " + id, "", date);
    }

    private static List<String> keys(List<MemoryListItem> rows) {
        List<String> keys = new ArrayList<>();
        for (MemoryListItem row : rows) keys.add(row.getKey());
        return keys;
    }
}
