package com.memorylane.feature.memories.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.snackbar.Snackbar;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.databinding.FragmentMemoryCreateBinding;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * New memory. On success it hands {@link #RESULT_CREATED} to the memory list's back stack entry
 * and returns there; the list then refreshes. A 401 clears the session and returns as well.
 */
public final class MemoryCreateFragment extends Fragment {
    /** SavedStateHandle key on the memory list entry: true after a memory was created. */
    public static final String RESULT_CREATED = "memory_created";

    private static final String DATE_PICKER = "memory_date_picker";
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private FragmentMemoryCreateBinding binding;
    private MemoryCreateViewModel viewModel;
    private AppContainer app;
    private DateTimeFormatter dateFormat;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMemoryCreateBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = AppContainer.from(requireContext());
        viewModel = new ViewModelProvider(this, MemoryCreateViewModel.factory(app.getMemoryRepository()))
                .get(MemoryCreateViewModel.class);
        dateFormat = DateTimeFormatter.ofPattern(getString(R.string.memory_list_date_pattern), TURKISH);
        ViewCompat.setAccessibilityHeading(binding.createHeading, true);

        binding.createBack.setOnClickListener(button -> goBack());
        binding.createDateCard.setOnClickListener(card -> openDatePicker());
        binding.createSave.setOnClickListener(button -> viewModel.save(app.authorization()));

        // Restored before the watchers are attached, so restoring is not taken for a user edit.
        restore(binding.createTitleInput, viewModel.title());
        restore(binding.createDescriptionInput, viewModel.description());
        onEdit(binding.createTitleInput, viewModel::onTitleChanged);
        onEdit(binding.createDescriptionInput, viewModel::onDescriptionChanged);
        // A picker left open across rotation lost its listener with the old view.
        Fragment openPicker = getChildFragmentManager().findFragmentByTag(DATE_PICKER);
        if (openPicker instanceof MaterialDatePicker) {
            listenTo(castPicker(openPicker));
        }

        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        viewModel.saved().observe(getViewLifecycleOwner(), saved -> {
            if (!Boolean.TRUE.equals(saved)) return;
            viewModel.consumeSaved();
            NavBackStackEntry list = NavHostFragment.findNavController(this).getPreviousBackStackEntry();
            if (list != null) list.getSavedStateHandle().set(RESULT_CREATED, true);
            goBack();
        });
        viewModel.sessionExpired().observe(getViewLifecycleOwner(), expired -> {
            if (!Boolean.TRUE.equals(expired)) return;
            viewModel.consumeSessionExpired();
            app.clearSession();
            // Attached to the activity so the message stays visible on the list screen.
            Snackbar.make(requireActivity().findViewById(android.R.id.content),
                    R.string.memory_list_session_expired, Snackbar.LENGTH_LONG).show();
            goBack();
        });
    }

    private void render(MemoryCreateUiState state) {
        boolean saving = state.isSaving();
        binding.createTitleLayout.setError(message(titleMessage(state.getTitleError())));
        binding.createDescriptionLayout.setError(message(descriptionMessage(state.getDescriptionError())));
        String dateError = message(dateMessage(state.getDateError()));
        binding.createDateError.setText(dateError);
        binding.createDateError.setVisibility(dateError == null ? View.GONE : View.VISIBLE);
        binding.createDateValue.setText(state.getDate() == null
                ? getString(R.string.memory_create_date_empty) : dateFormat.format(state.getDate()));

        binding.createError.setText(state.getServerError() == null
                ? null : ErrorMessages.describe(requireContext(), state.getServerError()));
        binding.createError.setVisibility(state.getServerError() == null ? View.GONE : View.VISIBLE);

        binding.createProgress.setVisibility(saving ? View.VISIBLE : View.GONE);
        binding.createSave.setEnabled(!saving);
        binding.createTitleInput.setEnabled(!saving);
        binding.createDescriptionInput.setEnabled(!saving);
        binding.createDateCard.setEnabled(!saving);
        binding.createBack.setEnabled(!saving);

        switch (state.getFocus()) {
            case TITLE:
                binding.createTitleInput.requestFocus();
                break;
            case DATE:
                binding.createDateCard.requestFocus();
                break;
            case DESCRIPTION:
                binding.createDescriptionInput.requestFocus();
                break;
            default:
                break;
        }
    }

    private void openDatePicker() {
        // A fast double tap must not stack two pickers.
        if (getChildFragmentManager().findFragmentByTag(DATE_PICKER) != null) return;
        LocalDate shown = viewModel.getState().getValue() != null
                && viewModel.getState().getValue().getDate() != null
                ? viewModel.getState().getValue().getDate() : viewModel.today();
        CalendarConstraints constraints = new CalendarConstraints.Builder()
                .setEnd(MaterialDatePicker.todayInUtcMilliseconds())
                .setValidator(DateValidatorPointBackward.now())
                .build();
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.memory_create_date_picker_title)
                // The picker works on UTC midnights; the local day is converted at that midnight.
                .setSelection(shown.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                .setCalendarConstraints(constraints)
                .build();
        listenTo(picker);
        picker.show(getChildFragmentManager(), DATE_PICKER);
    }

    private void listenTo(MaterialDatePicker<Long> picker) {
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection != null) {
                viewModel.onDateSelected(Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static MaterialDatePicker<Long> castPicker(Fragment fragment) {
        // Only this screen creates the tagged picker, and always as MaterialDatePicker<Long>.
        return (MaterialDatePicker<Long>) fragment;
    }

    private void goBack() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        // A fast double tap delivers a second click after the first pop; popping again would
        // also close the memory list underneath.
        if (current != null && current.getId() == R.id.memoryCreateFragment) {
            navController.popBackStack();
        }
    }

    @Nullable
    private String message(@StringRes int text) {
        return text == 0 ? null : getString(text);
    }

    @StringRes
    private static int titleMessage(MemoryCreateUiState.TitleError error) {
        switch (error) {
            case REQUIRED: return R.string.memory_create_title_required;
            case TOO_LONG: return R.string.memory_create_title_too_long;
            default: return 0;
        }
    }

    @StringRes
    private static int descriptionMessage(MemoryCreateUiState.DescriptionError error) {
        return error == MemoryCreateUiState.DescriptionError.TOO_LONG
                ? R.string.memory_create_description_too_long : 0;
    }

    @StringRes
    private static int dateMessage(MemoryCreateUiState.DateError error) {
        switch (error) {
            case REQUIRED: return R.string.memory_create_date_required;
            case FUTURE: return R.string.memory_create_date_future;
            default: return 0;
        }
    }

    private static void restore(EditText input, String value) {
        input.setText(value);
        input.setSelection(value.length());
    }

    private static void onEdit(EditText input, Consumer<String> onChanged) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                onChanged.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable value) { }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
