package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.memories.data.FakeMemoryDeleteRepository;

import org.junit.Rule;
import org.junit.Test;

public class MemoryDeleteViewModelTest {
    private static final String SESSION = "Bearer token-a";
    private static final String MEMORY = "m1";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final FakeMemoryDeleteRepository repository = new FakeMemoryDeleteRepository();
    private final MemoryDeleteViewModel viewModel = new MemoryDeleteViewModel(repository, MEMORY);

    @Test
    public void nothingIsDeletedBeforeTheUserConfirms() {
        assertEquals(MemoryDeleteViewModel.Status.READY, status());
        assertEquals(0, repository.requests.size());
    }

    @Test
    public void confirmingDeletesThisMemoryWithTheSession() {
        viewModel.delete(SESSION);

        assertEquals(MemoryDeleteViewModel.Status.DELETING, status());
        assertEquals(SESSION, repository.last().authorization);
        assertEquals(MEMORY, repository.last().memoryId);
        repository.last().succeed();
        assertEquals(MemoryDeleteViewModel.Status.DELETED, status());
    }

    @Test
    public void secondTapWhileDeletingSendsNoSecondRequest() {
        viewModel.delete(SESSION);
        viewModel.delete(SESSION);

        assertEquals(1, repository.requests.size());
        repository.last().succeed();
        viewModel.delete(SESSION);
        assertEquals("nothing left to delete", 1, repository.requests.size());
    }

    @Test
    public void notFoundMeansAlreadyDeletedNotAFailure() {
        viewModel.delete(SESSION);

        repository.last().fail(ApiError.http(404, "MEMORY_NOT_FOUND", "Anı bulunamadı."));

        assertEquals(MemoryDeleteViewModel.Status.DELETED, status());
    }

    @Test
    public void failureKeepsThePanelOpenWithTheErrorAndCanBeRetried() {
        viewModel.delete(SESSION);
        ApiError offline = ApiError.network();

        repository.last().fail(offline);

        assertEquals(MemoryDeleteViewModel.Status.FAILED, status());
        assertSame(offline, viewModel.getError());
        viewModel.delete(SESSION);
        assertEquals(MemoryDeleteViewModel.Status.DELETING, status());
        assertNull("the old error is cleared on retry", viewModel.getError());
        assertEquals(2, repository.requests.size());
        repository.last().succeed();
        assertEquals(MemoryDeleteViewModel.Status.DELETED, status());
    }

    @Test
    public void serverErrorIsAFailureNotADelete() {
        viewModel.delete(SESSION);

        repository.last().fail(ApiError.http(503, "DATABASE_UNAVAILABLE", "Veritabanı kullanılamıyor."));

        assertEquals(MemoryDeleteViewModel.Status.FAILED, status());
    }

    @Test
    public void rejectedSessionEndsInSignedOut() {
        viewModel.delete(SESSION);

        repository.last().fail(ApiError.http(401, "INVALID_SESSION", "Oturum geçersiz."));

        assertEquals(MemoryDeleteViewModel.Status.SIGNED_OUT, status());
    }

    @Test
    public void withoutSessionNoRequestIsSent() {
        viewModel.delete(null);

        assertEquals(MemoryDeleteViewModel.Status.SIGNED_OUT, status());
        assertEquals(0, repository.requests.size());
    }

    @Test
    public void clearingTheScreenCancelsARunningDelete() {
        viewModel.delete(SESSION);

        viewModel.onCleared();

        assertTrue(repository.last().isCancelled());
    }

    private MemoryDeleteViewModel.Status status() {
        return viewModel.getStatus().getValue();
    }
}
