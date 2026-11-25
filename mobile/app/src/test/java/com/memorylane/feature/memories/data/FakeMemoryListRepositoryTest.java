package com.memorylane.feature.memories.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The fake must keep the MemoryListRepository contract, or ViewModel tests would prove nothing. */
public class FakeMemoryListRepositoryTest {
    private final FakeMemoryListRepository repository = new FakeMemoryListRepository();
    private final List<ApiResult<MemoryApi.Page>> results = new ArrayList<>();

    @Test
    public void recordsEachRequestUntilAnswered() {
        repository.list("Bearer a", 0, results::add);
        repository.list("Bearer a", 1, results::add);

        assertEquals(2, repository.requests.size());
        assertEquals("Bearer a", repository.last().authorization);
        assertEquals(1, repository.last().page);
        assertTrue(results.isEmpty());
    }

    @Test
    public void successDeliversThePage() {
        repository.list("Bearer a", 0, results::add);
        repository.last().succeed(FakeMemoryListRepository.sampleMemories(), true);

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals(3, results.get(0).getData().content().size());
        assertTrue(results.get(0).getData().hasNext());
    }

    @Test
    public void failureDeliversTheError() {
        ApiError failure = ApiError.network();
        repository.list("Bearer a", 0, results::add);
        repository.last().fail(failure);

        assertSame(failure, results.get(0).getError());
    }

    @Test
    public void cancelledRequestIsNeverAnswered() {
        repository.list("Bearer a", 0, results::add).cancel();
        repository.last().succeed(FakeMemoryListRepository.sampleMemories(), false);

        assertTrue(repository.last().isCancelled());
        assertTrue(results.isEmpty());
    }

    @Test
    public void aRequestIsAnsweredAtMostOnce() {
        repository.list("Bearer a", 0, results::add);
        repository.last().succeed(List.of(), false);

        assertThrows(AssertionError.class, () -> repository.last().fail(ApiError.network()));
        assertEquals(1, results.size());
    }

    @Test
    public void sampleMemoriesAreInServerOrder() {
        List<MemoryApi.Memory> sample = FakeMemoryListRepository.sampleMemories();

        for (int i = 1; i < sample.size(); i++) {
            assertTrue(sample.get(i - 1).memoryDate().compareTo(sample.get(i).memoryDate()) >= 0);
        }
    }
}
