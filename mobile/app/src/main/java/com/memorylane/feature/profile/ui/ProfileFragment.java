package com.memorylane.feature.profile.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.memorylane.BuildConfig;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.core.ui.Motion;
import com.memorylane.core.ui.UiState;
import com.memorylane.databinding.FragmentProfileBinding;
import com.memorylane.feature.health.data.HealthResponse;
import com.memorylane.feature.health.ui.HealthViewModel;

import java.util.Locale;

/**
 * Profile tab, laid out like a settings screen: the notebook owner, the account rows, the app
 * rows (server status and version) and signing out.
 *
 * <p>Signing out revokes the token on the server and forgets it in {@link AppContainer}. The
 * memory list follows the session when its view comes back and drops the loaded memories, so
 * nothing of the previous account stays on screen.
 */
public class ProfileFragment extends Fragment {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

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
        binding.profileVersion.setText(BuildConfig.VERSION_NAME);
        renderSession();

        healthViewModel = new ViewModelProvider(this, HealthViewModel.factory(app.getHealthRepository()))
                .get(HealthViewModel.class);
        healthViewModel.getState().observe(getViewLifecycleOwner(), this::renderHealth);
        healthViewModel.loadIfNeeded();

        if (savedInstanceState == null) {
            Motion.riseIn(binding.profileHeader, 0);
            Motion.riseIn(binding.profileOwner, 70);
            Motion.riseIn(binding.profileAccountSection, 140);
            Motion.riseIn(binding.profileAppTitle, 210);
            Motion.riseIn(binding.profileAppCard, 210);
        }
    }

    private void renderSession() {
        String email = app.getSessionStore().email();
        boolean signedIn = app.authorization() != null && email != null;
        String owner = signedIn ? email : getString(R.string.profile_guest);
        binding.profileEmail.setText(owner);
        binding.profileInitial.setText(initial(owner));
        binding.profileOwnerNote.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.profileSessionMessage.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.profileSignIn.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.profileAccountSection.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.profileEmailValue.setText(signedIn ? email : null);
        binding.profileSignOut.setVisibility(signedIn ? View.VISIBLE : View.GONE);
    }

    /** First letter, upper-cased the Turkish way ("i" becomes "İ"). */
    private static String initial(String text) {
        return new String(Character.toChars(text.codePointAt(0))).toUpperCase(TURKISH);
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
                binding.healthProgress.setVisibility(View.VISIBLE);
                binding.healthIcon.setVisibility(View.INVISIBLE);
                binding.healthStatus.setText(R.string.health_checking);
                binding.healthDetail.setVisibility(View.GONE);
                binding.healthAddress.setVisibility(View.GONE);
                setRetry(false);
                break;
            case SUCCESS:
                HealthResponse health = state.getData();
                String database = health == null || health.getDatabase() == null ? "-" : health.getDatabase();
                showHealth(R.drawable.ic_status_ok, getString(R.string.health_ok_title),
                        getString(R.string.health_ok_detail, database));
                binding.healthAddress.setText(getString(R.string.health_server_address, app.getBaseUrl()));
                binding.healthAddress.setVisibility(View.VISIBLE);
                setRetry(false);
                break;
            case ERROR:
            default:
                // Health has no "empty" result; anything that is not a success is shown as an error.
                // The row itself becomes the retry button, with the hint as its last line.
                ApiError error = state.getError() != null ? state.getError() : ApiError.unexpected();
                showHealth(R.drawable.ic_status_error, getString(R.string.error_title),
                        ErrorMessages.describe(requireContext(), error));
                binding.healthAddress.setText(R.string.profile_server_retry);
                binding.healthAddress.setVisibility(View.VISIBLE);
                setRetry(true);
                break;
        }
    }

    private void showHealth(@DrawableRes int icon, String status, String detail) {
        binding.healthProgress.setVisibility(View.GONE);
        binding.healthIcon.setImageResource(icon);
        binding.healthIcon.setVisibility(View.VISIBLE);
        binding.healthStatus.setText(status);
        binding.healthDetail.setText(detail);
        binding.healthDetail.setVisibility(View.VISIBLE);
    }

    private void setRetry(boolean enabled) {
        binding.healthRow.setOnClickListener(enabled ? row -> healthViewModel.refresh() : null);
        binding.healthRow.setClickable(enabled);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
