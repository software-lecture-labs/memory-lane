package com.memorylane.feature.memories.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.core.ui.UiState;
import com.memorylane.databinding.FragmentMemoriesBinding;
import com.memorylane.feature.health.data.HealthResponse;
import com.memorylane.feature.health.ui.HealthViewModel;

/**
 * Start tab. The memory list arrives with its own card; for now the tab shows the server
 * health check so the connection setup can be verified on an emulator or phone.
 */
public class MemoriesFragment extends Fragment {
    private FragmentMemoriesBinding binding;
    private HealthViewModel healthViewModel;
    private String baseUrl;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMemoriesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        AppContainer appContainer = AppContainer.from(requireContext());
        baseUrl = appContainer.getBaseUrl();
        healthViewModel = new ViewModelProvider(this, HealthViewModel.factory(appContainer.getHealthRepository()))
                .get(HealthViewModel.class);
        healthViewModel.getState().observe(getViewLifecycleOwner(), this::renderHealth);
        healthViewModel.loadIfNeeded();
    }

    private void renderHealth(UiState<HealthResponse> state) {
        switch (state.getStatus()) {
            case LOADING:
                binding.healthCard.setVisibility(View.GONE);
                binding.healthState.showLoading(getString(R.string.health_checking));
                break;
            case SUCCESS:
                binding.healthState.hide();
                binding.healthCard.setVisibility(View.VISIBLE);
                HealthResponse health = state.getData();
                String database = health == null || health.getDatabase() == null ? "-" : health.getDatabase();
                binding.healthDatabase.setText(getString(R.string.health_ok_detail, database));
                binding.healthAddress.setText(getString(R.string.health_server_address, baseUrl));
                break;
            case ERROR:
            default:
                // Health has no "empty" result; anything that is not a success is shown as an error.
                ApiError error = state.getError() != null ? state.getError() : ApiError.unexpected();
                binding.healthCard.setVisibility(View.GONE);
                binding.healthState.showError(
                        getString(R.string.error_title),
                        ErrorMessages.describe(requireContext(), error),
                        healthViewModel::refresh);
                break;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
