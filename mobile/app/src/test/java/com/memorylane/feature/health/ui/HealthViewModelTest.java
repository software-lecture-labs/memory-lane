package com.memorylane.feature.health.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.core.ui.UiState;
import com.memorylane.feature.health.data.HealthRepository;
import com.memorylane.feature.health.data.HealthResponse;

import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HealthViewModelTest {
    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final FakeHealthRepository repository = new FakeHealthRepository();
    private final HealthViewModel viewModel = new HealthViewModel(repository);

    @Test
    public void showsLoadingUntilServerAnswers() {
        viewModel.loadIfNeeded();

        assertEquals(UiState.Status.LOADING, viewModel.getState().getValue().getStatus());
    }

    @Test
    public void successfulCheckShowsDatabaseState() {
        viewModel.loadIfNeeded();
        repository.answer(0, ApiResult.success(new HealthResponse("UP", "UP")));

        UiState<HealthResponse> state = viewModel.getState().getValue();
        assertEquals(UiState.Status.SUCCESS, state.getStatus());
        assertEquals("UP", state.getData().getDatabase());
    }

    @Test
    public void connectionFailureIsErrorNotEmpty() {
        viewModel.loadIfNeeded();
        repository.answer(0, ApiResult.failure(ApiError.network()));

        UiState<HealthResponse> state = viewModel.getState().getValue();
        assertEquals(UiState.Status.ERROR, state.getStatus());
        assertEquals(ApiError.Kind.NETWORK, state.getError().getKind());
    }

    @Test
    public void loadIfNeededDoesNotRepeatAfterResult() {
        viewModel.loadIfNeeded();
        repository.answer(0, ApiResult.success(new HealthResponse("UP", "UP")));

        viewModel.loadIfNeeded();

        assertEquals(1, repository.callbacks.size());
    }

    @Test
    public void retryCancelsEarlierRequest() {
        viewModel.loadIfNeeded();
        viewModel.refresh();

        assertTrue(repository.cancelled.get(0));
        assertEquals(UiState.Status.LOADING, viewModel.getState().getValue().getStatus());
    }

    /** Records callbacks so each test decides when and how the "server" answers. */
    private static final class FakeHealthRepository implements HealthRepository {
        final List<ResultCallback<HealthResponse>> callbacks = new ArrayList<>();
        final List<Boolean> cancelled = new ArrayList<>();

        @Override
        public Cancellable checkHealth(ResultCallback<HealthResponse> callback) {
            int index = callbacks.size();
            callbacks.add(callback);
            cancelled.add(false);
            return () -> cancelled.set(index, true);
        }

        void answer(int index, ApiResult<HealthResponse> result) {
            callbacks.get(index).onResult(result);
        }
    }
}
