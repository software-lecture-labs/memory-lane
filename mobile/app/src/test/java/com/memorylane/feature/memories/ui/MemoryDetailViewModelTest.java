package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.memories.data.FakeMemoryDetailRepository;
import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.Rule;
import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class MemoryDetailViewModelTest {
    private static final String SESSION = "Bearer token-a";
    private static final String MEMORY = "m1";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final FakeMemoryDetailRepository repository = new FakeMemoryDetailRepository();
    private final MemoryDetailViewModel viewModel = new MemoryDetailViewModel(repository, MEMORY);

    @Test
    public void startLoadsThisMemoryWithTheSession() {
        viewModel.start(SESSION);

        assertEquals(MemoryDetailUiState.Status.LOADING, state().getStatus());
        assertEquals(1, repository.requests.size());
        assertEquals(SESSION, repository.last().authorization);
        assertEquals(MEMORY, repository.last().memoryId);
        assertEquals(MEMORY, viewModel.memoryId());
    }

    @Test
    public void memoryWithPhotosAndLocationShowsGalleryAndLocation() {
        viewModel.start(SESSION);
        MemoryApi.Detail memory = FakeMemoryDetailRepository.fullMemory(MEMORY);

        repository.last().succeed(memory);

        MemoryDetailUiState state = state();
        assertEquals(MemoryDetailUiState.Status.CONTENT, state.getStatus());
        assertSame(memory, state.getMemory());
        assertEquals(LocalDate.of(2024, 10, 28), state.getDate());
        assertTrue(state.hasPhotos());
        assertTrue(state.hasLocation());
        assertEquals(List.of("p1", "p2"), List.of(state.getMemory().photos().get(0).id(),
                state.getMemory().photos().get(1).id()));
        assertNull(state.getError());
    }

    @Test
    public void galleryFollowsPhotoOrderWhateverOrderTheServerListsThemIn() {
        viewModel.start(SESSION);
        MemoryApi.Detail full = FakeMemoryDetailRepository.fullMemory(MEMORY);
        // Five photos, listed out of order; p3 and p4 share an order and keep the server's sequence.
        MemoryApi.Detail shuffled = new MemoryApi.Detail(full.id(), full.title(), full.description(),
                full.memoryDate(), full.category(), full.emotion(), full.location(), full.createdAt(),
                full.updatedAt(), 5, "p1", List.of(
                        FakeMemoryDetailRepository.photo(MEMORY, "p5", 4),
                        FakeMemoryDetailRepository.photo(MEMORY, "p3", 2),
                        FakeMemoryDetailRepository.photo(MEMORY, "p1", 0),
                        FakeMemoryDetailRepository.photo(MEMORY, "p4", 2),
                        FakeMemoryDetailRepository.photo(MEMORY, "p2", 1)));

        repository.last().succeed(shuffled);

        List<String> gallery = new ArrayList<>();
        for (MemoryApi.Photo photo : state().getPhotos()) gallery.add(photo.id());
        assertEquals(List.of("p1", "p2", "p3", "p4", "p5"), gallery);
    }

    @Test
    public void memoryWithoutPhotosOrLocationLeavesBothOut() {
        viewModel.start(SESSION);

        repository.last().succeed(FakeMemoryDetailRepository.plainMemory(MEMORY));

        MemoryDetailUiState state = state();
        assertEquals(MemoryDetailUiState.Status.CONTENT, state.getStatus());
        assertFalse(state.hasPhotos());
        assertFalse(state.hasLocation());
        assertEquals(LocalDate.of(2025, 1, 1), state.getDate());
    }

    @Test
    public void missingOrForeignMemoryIsNotFound() {
        viewModel.start(SESSION);

        repository.last().fail(ApiError.http(404, "MEMORY_NOT_FOUND", "Anı bulunamadı."));

        assertEquals(MemoryDetailUiState.Status.NOT_FOUND, state().getStatus());
        assertNull(state().getMemory());
    }

    @Test
    public void networkErrorOffersRetryThatLoadsAgain() {
        viewModel.start(SESSION);
        repository.last().fail(ApiError.network());

        assertEquals(MemoryDetailUiState.Status.ERROR, state().getStatus());
        assertNotNull(state().getError());
        assertEquals(ApiError.Kind.NETWORK, state().getError().getKind());

        viewModel.retry();

        assertEquals(MemoryDetailUiState.Status.LOADING, state().getStatus());
        assertEquals(2, repository.requests.size());
        repository.last().succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));
        assertEquals(MemoryDetailUiState.Status.CONTENT, state().getStatus());
    }

    @Test
    public void serverErrorIsAnErrorNotNotFound() {
        viewModel.start(SESSION);

        repository.last().fail(ApiError.http(503, "DATABASE_UNAVAILABLE", "Veritabanı kullanılamıyor."));

        assertEquals(MemoryDetailUiState.Status.ERROR, state().getStatus());
        assertEquals(503, state().getError().getHttpStatus());
    }

    @Test
    public void rejectedSessionIsSignedOutAndRetryDoesNothing() {
        viewModel.start(SESSION);

        repository.last().fail(ApiError.http(401, "INVALID_SESSION", "Geçerli oturum gereklidir."));
        viewModel.retry();

        assertEquals(MemoryDetailUiState.Status.SIGNED_OUT, state().getStatus());
        assertEquals(1, repository.requests.size());
    }

    @Test
    public void withoutSessionItIsSignedOutAndRequestsNothing() {
        viewModel.start(null);
        viewModel.retry();

        assertEquals(MemoryDetailUiState.Status.SIGNED_OUT, state().getStatus());
        assertTrue(repository.requests.isEmpty());
    }

    @Test
    public void sameSessionAgainKeepsTheShownMemory() {
        viewModel.start(SESSION);
        repository.last().succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));

        viewModel.start(SESSION); // a recreated view, e.g. after rotation

        assertEquals(1, repository.requests.size());
        assertEquals(MemoryDetailUiState.Status.CONTENT, state().getStatus());
    }

    @Test
    public void retryCancelsTheRunningRequestAndIgnoresItsLateAnswer() {
        viewModel.start(SESSION);
        FakeMemoryDetailRepository.Request first = repository.last();
        first.fail(ApiError.network());
        viewModel.retry();
        FakeMemoryDetailRepository.Request second = repository.last();
        viewModel.retry();

        assertTrue(second.isCancelled());
        second.succeed(FakeMemoryDetailRepository.plainMemory(MEMORY));
        assertEquals(MemoryDetailUiState.Status.LOADING, state().getStatus());
        repository.last().succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));
        assertTrue(state().hasPhotos());
    }

    @Test
    public void detailBreakingTheContractIsAParseError() {
        List<MemoryApi.Detail> broken = List.of(
                new MemoryApi.Detail(null, "Başlık", "", "2024-01-01", "OTHER", null, null, null, null, 0, null, List.of()),
                new MemoryApi.Detail(MEMORY, " ", "", "2024-01-01", "OTHER", null, null, null, null, 0, null, List.of()),
                new MemoryApi.Detail(MEMORY, "Başlık", "", "01.01.2024", "OTHER", null, null, null, null, 0, null, List.of()),
                new MemoryApi.Detail(MEMORY, "Başlık", "", "2024-01-01", "OTHER", null, null, null, null, 0, null, null),
                new MemoryApi.Detail(MEMORY, "Başlık", "", "2024-01-01", "OTHER", null, null, null, null, 1, "p",
                        List.of(new MemoryApi.Photo(null, MEMORY, 0, "image/jpeg", 1, null, null))));
        for (MemoryApi.Detail memory : broken) {
            viewModel.start(null);
            viewModel.start(SESSION);
            repository.last().succeed(memory);

            assertEquals(MemoryDetailUiState.Status.ERROR, state().getStatus());
            assertEquals(ApiError.Kind.PARSE, state().getError().getKind());
        }
    }

    @Test
    public void refreshAfterAnEditKeepsTheMemoryOnScreenUntilTheNewVersionArrives() {
        viewModel.start(SESSION);
        repository.last().succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));

        viewModel.refresh();

        assertEquals(2, repository.requests.size());
        assertEquals(MemoryDetailUiState.Status.CONTENT, state().getStatus());
        repository.last().succeed(FakeMemoryDetailRepository.plainMemory(MEMORY));
        assertEquals("Sade bir gün", state().getMemory().title());
    }

    @Test
    public void refreshWithoutContentShowsLoadingAndWithoutSessionDoesNothing() {
        viewModel.start(SESSION);
        repository.last().fail(ApiError.network());

        viewModel.refresh();
        assertEquals(MemoryDetailUiState.Status.LOADING, state().getStatus());

        MemoryDetailViewModel signedOut = new MemoryDetailViewModel(repository, MEMORY);
        signedOut.start(null);
        signedOut.refresh();
        assertEquals(2, repository.requests.size());
    }

    @Test
    public void clearingTheScreenCancelsItsRequest() {
        viewModel.start(SESSION);

        viewModel.onCleared();

        assertTrue(repository.last().isCancelled());
    }

    private MemoryDetailUiState state() {
        MemoryDetailUiState state = viewModel.getState().getValue();
        assertNotNull(state);
        return state;
    }
}
