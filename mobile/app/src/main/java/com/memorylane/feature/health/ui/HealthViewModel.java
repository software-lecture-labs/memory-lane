package com.memorylane.feature.health.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.core.network.Cancellable;
import com.memorylane.core.ui.UiState;
import com.memorylane.feature.health.data.HealthRepository;
import com.memorylane.feature.health.data.HealthResponse;

public class HealthViewModel extends ViewModel {
    private final HealthRepository repository;
    private final MutableLiveData<UiState<HealthResponse>> state = new MutableLiveData<>();
    @Nullable private Cancellable pending;

    public HealthViewModel(HealthRepository repository) {
        this.repository = repository;
    }

    public LiveData<UiState<HealthResponse>> getState() {
        return state;
    }

    /** Called once per ViewModel; rotation keeps the last result instead of re-requesting. */
    public void loadIfNeeded() {
        if (state.getValue() == null) {
            refresh();
        }
    }

    public void refresh() {
        // A slow earlier answer must not overwrite the result of a newer retry.
        cancelPending();
        state.setValue(UiState.loading());
        pending = repository.checkHealth(result -> {
            pending = null;
            state.setValue(UiState.from(result));
        });
    }

    @Override
    protected void onCleared() {
        cancelPending();
    }

    private void cancelPending() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    public static ViewModelProvider.Factory factory(HealthRepository repository) {
        return new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                if (!modelClass.isAssignableFrom(HealthViewModel.class)) {
                    throw new IllegalArgumentException("Unsupported ViewModel: " + modelClass.getName());
                }
                return modelClass.cast(new HealthViewModel(repository));
            }
        };
    }
}
