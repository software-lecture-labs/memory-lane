package com.memorylane.feature.memories.ui;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryRepository;

import org.junit.Rule;
import org.junit.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.*;

public class MemoriesViewModelTest {
    @Rule public InstantTaskExecutorRule executor = new InstantTaskExecutorRule();

    private final FakeRepository repository = new FakeRepository();
    private final MemoriesViewModel model = new MemoriesViewModel(repository);

    @Test
    public void failedSaveKeepsDraftAndRetryKeyThenRefreshesList() {
        var memory = new MemoryApi.Memory("1", "Sahil", "Akşam", "2026-01-01");
        model.attach("Bearer test");
        repository.list.onResult(ApiResult.success(new MemoryApi.Page(List.of(), false)));
        model.onTitleChanged(memory.title());
        model.onDescriptionChanged(memory.description());
        model.onDateSelected(LocalDate.parse(memory.memoryDate()));
        model.save();
        model.save();
        assertEquals(1, repository.saves);
        var key = repository.key;

        repository.save.onResult(ApiResult.failure(ApiError.network()));
        var failure = model.state().getValue();
        assertEquals("Sahil", failure.title());
        assertEquals("Akşam", failure.description());
        assertEquals(LocalDate.parse(memory.memoryDate()), failure.date());
        assertFalse(failure.saving());
        assertNotNull(failure.saveError());

        model.save();
        assertEquals(key, repository.key);
        repository.save.onResult(ApiResult.success(memory));
        assertEquals(2, repository.loads);
        repository.list.onResult(ApiResult.success(new MemoryApi.Page(List.of(memory), false)));
        assertEquals(List.of(memory), model.state().getValue().rows());
        assertTrue(failure.rows().isEmpty());
        assertEquals("", model.state().getValue().title());
    }

    @Test
    public void unauthorizedListClearsScreenAndExpiresSession() {
        model.attach("Bearer test");
        model.onTitleChanged("Private draft");
        repository.list.onResult(ApiResult.failure(ApiError.http(401, "INVALID_SESSION", null)));
        var state = model.state().getValue();
        assertTrue(state.sessionExpired());
        assertFalse(state.signedIn());
        assertTrue(state.rows().isEmpty());
        assertEquals("", state.title());
    }

    private static class FakeRepository implements MemoryRepository {
        ResultCallback<MemoryApi.Memory> save;
        ResultCallback<MemoryApi.Page> list;
        int saves;
        int loads;
        String key;

        @Override
        public Cancellable save(String session, String key, MemoryApi.Draft draft,
                                ResultCallback<MemoryApi.Memory> callback) {
            saves++;
            this.key = key;
            save = callback;
            return () -> {};
        }

        @Override
        public Cancellable list(String session, int page, ResultCallback<MemoryApi.Page> callback) {
            loads++;
            list = callback;
            return () -> {};
        }
    }
}
