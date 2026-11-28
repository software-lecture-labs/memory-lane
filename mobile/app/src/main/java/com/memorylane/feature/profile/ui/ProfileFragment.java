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
import com.memorylane.feature.calendar.ui.MemoryCalendarUiState;
import com.memorylane.feature.calendar.ui.MemoryCalendarView;
import com.memorylane.feature.calendar.ui.MemoryCalendarViewModel;
import com.memorylane.feature.health.data.HealthResponse;
import com.memorylane.feature.health.ui.HealthViewModel;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Profile tab, laid out like a settings screen: the notebook owner, the memory calendar, the
 * account rows, the app rows (server status and version) and signing out. The calendar follows
 * the session like the memory list does.
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
    private MemoryCalendarViewModel calendarViewModel;

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
            calendarViewModel.setSession(null);
            Snackbar.make(binding.getRoot(), R.string.profile_signed_out_done, Snackbar.LENGTH_SHORT).show();
        });
        binding.profileVersion.setText(BuildConfig.VERSION_NAME);

        calendarViewModel = new ViewModelProvider(this,
                MemoryCalendarViewModel.factory(app.getMemoryRepository())).get(MemoryCalendarViewModel.class);
        binding.profileCalendar.setListener(new MemoryCalendarView.Listener() {
            @Override
            public void onPreviousMonth() { calendarViewModel.previousMonth(); }

            @Override
            public void onNextMonth() { calendarViewModel.nextMonth(); }

            @Override
            public void onDayPicked(@NonNull LocalDate day) { calendarViewModel.select(day); }
        });
        calendarViewModel.getState().observe(getViewLifecycleOwner(), this::renderCalendar);

        renderSession();
        // Each time the view comes back (e.g. after a new memory) the calendar follows the session.
        calendarViewModel.setSession(app.authorization());

        healthViewModel = new ViewModelProvider(this, HealthViewModel.factory(app.getHealthRepository()))
                .get(HealthViewModel.class);
        healthViewModel.getState().observe(getViewLifecycleOwner(), this::renderHealth);
        healthViewModel.loadIfNeeded();

        if (savedInstanceState == null) {
            Motion.riseIn(binding.profileHeader, 0);
            Motion.riseIn(binding.profileOwner, 70);
            Motion.riseIn(binding.profileCalendarSection, 140);
            Motion.riseIn(binding.profileAccountSection, 210);
            Motion.riseIn(binding.profileAppTitle, 280);
            Motion.riseIn(binding.profileAppCard, 280);
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
        binding.profileCalendarSection.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.profileEmailValue.setText(signedIn ? email : null);
        binding.profileSignOut.setVisibility(signedIn ? View.VISIBLE : View.GONE);
    }

    private void renderCalendar(MemoryCalendarUiState state) {
        boolean ready = state.getStatus() == MemoryCalendarUiState.Status.READY;
        switch (state.getStatus()) {
            case LOADING:
                binding.profileCalendarState.setVisibility(View.VISIBLE);
                binding.profileCalendarState.showLoading(getString(R.string.calendar_loading));
                break;
            case ERROR:
                ApiError error = state.getError() != null ? state.getError() : ApiError.unexpected();
                binding.profileCalendarState.setVisibility(View.VISIBLE);
                binding.profileCalendarState.showError(getString(R.string.calendar_error_title),
                        ErrorMessages.describe(requireContext(), error), calendarViewModel::reload);
                break;
            case READY:
            case SIGNED_OUT:
            default:
                binding.profileCalendarState.hide();
                binding.profileCalendarState.setVisibility(View.GONE);
                break;
        }
        // The empty grid is never shown under a loading or error message.
        binding.profileCalendar.setVisibility(ready ? View.VISIBLE : View.GONE);
        binding.profileCalendar.render(state);
        // Once the memories are in, the owner card says how many and since when.
        binding.profileOwnerNote.setText(ready ? summary(state) : getString(R.string.profile_owner_note));
    }

    private String summary(MemoryCalendarUiState state) {
        LocalDate first = state.getFirstMemory();
        if (first == null) return getString(R.string.profile_summary_empty);
        String date = DateTimeFormatter.ofPattern(getString(R.string.profile_summary_date_pattern), TURKISH)
                .format(first);
        return getResources().getQuantityString(R.plurals.profile_summary, state.getTotal(), state.getTotal(), date);
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
