package com.memorylane.feature.profile.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.core.ui.UiState;
import com.memorylane.databinding.FragmentProfileBinding;
import com.memorylane.feature.health.data.HealthResponse;
import com.memorylane.feature.health.ui.HealthViewModel;

/**
 * Profile tab: signs in or out and shows the server health check.
 *
 * <p>Signing out revokes the token on the server and forgets it in {@link AppContainer}. The
 * memory list follows the session when its view comes back and drops the loaded memories, so
 * nothing of the previous account stays on screen.
 */
public class ProfileFragment extends Fragment {
    private FragmentProfileBinding binding;
    private AppContainer app;
    private HealthViewModel healthViewModel;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = AppContainer.from(requireContext());

        binding.profileSignIn.setOnClickListener(button -> openLogin());
        binding.profileSignOut.setOnClickListener(button -> {
            app.endSession();
            renderSession();
            Snackbar.make(binding.getRoot(), R.string.profile_signed_out_done, Snackbar.LENGTH_SHORT).show();
        });
        renderSession();

        healthViewModel = new ViewModelProvider(this, HealthViewModel.factory(app.getHealthRepository()))
                .get(HealthViewModel.class);
        healthViewModel.getState().observe(getViewLifecycleOwner(), this::renderHealth);
        healthViewModel.loadIfNeeded();
    }

    private void renderSession() {
        boolean signedIn = app.authorization() != null;
        binding.profileSessionMessage.setText(signedIn ? R.string.profile_signed_in : R.string.profile_signed_out);
        binding.profileSignIn.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.profileSignOut.setVisibility(signedIn ? View.VISIBLE : View.GONE);
    }

    private void openLogin() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        // A fast double tap must not navigate twice.
        if (current != null && current.getId() == R.id.profileFragment) {
            navController.navigate(R.id.action_profileFragment_to_loginFragment);
        }
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
                binding.healthAddress.setText(getString(R.string.health_server_address, app.getBaseUrl()));
                break;
            case ERROR:
            default:
                // Health has no "empty" result; anything that is not a success is shown as an error.
                ApiError error = state.getError() != null ? state.getError() : ApiError.unexpected();
                binding.healthCard.setVisibility(View.GONE);
                binding.healthState.showError(getString(R.string.error_title),
                        ErrorMessages.describe(requireContext(), error), healthViewModel::refresh);
                break;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
