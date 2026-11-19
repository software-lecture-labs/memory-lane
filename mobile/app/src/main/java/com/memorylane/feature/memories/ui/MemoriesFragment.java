package com.memorylane.feature.memories.ui;

import android.os.Bundle;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.textfield.TextInputLayout;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.core.ui.UiState;
import com.memorylane.databinding.FragmentMemoriesBinding;
import com.memorylane.feature.health.data.HealthResponse;
import com.memorylane.feature.health.ui.HealthViewModel;
import com.memorylane.feature.memories.ui.MemoriesUiState.Validation;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

public class MemoriesFragment extends Fragment {
    private static final String DATE_PICKER = "memory_date_picker";

    private FragmentMemoriesBinding binding;
    private HealthViewModel healthViewModel;
    private String baseUrl;
    private AppContainer app;
    private MemoriesViewModel model;
    private boolean rendering;

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
        app = appContainer;
        model = new ViewModelProvider(this, MemoriesViewModel.factory(app.getMemoryRepository()))
                .get(MemoriesViewModel.class);
        model.attach(app.authorization());
        binding.memoryLogin.setOnClickListener(button ->
                NavHostFragment.findNavController(this).navigate(R.id.loginFragment));
        binding.memoryDate.setOnClickListener(button -> chooseDate());
        binding.memoryToday.setOnClickListener(button -> model.onTodaySelected());
        listenToDatePicker();
        addChoices(binding.memoryCategories, MemoryChoices.CATEGORIES, model::onCategorySelected);
        addChoices(binding.memoryEmotions, MemoryChoices.EMOTIONS, model::onEmotionSelected);
        countCodePoints(binding.memoryTitleLayout);
        countCodePoints(binding.memoryDescriptionLayout);
        binding.memorySave.setOnClickListener(button -> model.save());
        binding.memoryRefresh.setOnClickListener(button -> model.load(0));
        binding.memoryMore.setOnClickListener(button -> model.more());
        binding.memoryLogout.setOnClickListener(button -> {
            app.endSession();
            model.onLogout();
        });
        binding.memoryTitle.addTextChangedListener(draftWatcher(model::onTitleChanged));
        binding.memoryDescription.addTextChangedListener(draftWatcher(model::onDescriptionChanged));
        binding.memoryLatitude.addTextChangedListener(draftWatcher(model::onLatitudeChanged));
        binding.memoryLongitude.addTextChangedListener(draftWatcher(model::onLongitudeChanged));
        model.state().observe(getViewLifecycleOwner(), this::renderMemories);
        baseUrl = appContainer.getBaseUrl();
        healthViewModel = new ViewModelProvider(this, HealthViewModel.factory(appContainer.getHealthRepository()))
                .get(HealthViewModel.class);
        healthViewModel.getState().observe(getViewLifecycleOwner(), this::renderHealth);
        healthViewModel.loadIfNeeded();
    }

    /** MaterialDatePicker is a DialogFragment, so it survives rotation; only today and past days are selectable. */
    private void chooseDate() {
        if (getChildFragmentManager().findFragmentByTag(DATE_PICKER) != null) return;
        LocalDate selected = model.state().getValue().date();
        long today = utcMillis(model.today());
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.memory_date_select)
                .setSelection(selected == null ? today : utcMillis(selected))
                .setCalendarConstraints(new CalendarConstraints.Builder()
                        .setEnd(today)
                        .setValidator(DateValidatorPointBackward.before(today))
                        .build())
                .build();
        picker.show(getChildFragmentManager(), DATE_PICKER);
        listenTo(picker);
    }

    /** A picker restored after rotation has lost its listener, so it is attached again here. */
    @SuppressWarnings("unchecked")
    private void listenToDatePicker() {
        Fragment picker = getChildFragmentManager().findFragmentByTag(DATE_PICKER);
        if (picker instanceof MaterialDatePicker) listenTo((MaterialDatePicker<Long>) picker);
    }

    private void listenTo(MaterialDatePicker<Long> picker) {
        picker.clearOnPositiveButtonClickListeners();
        picker.addOnPositiveButtonClickListener(selection -> model.onDateSelected(
                Instant.ofEpochMilli(selection).atZone(ZoneOffset.UTC).toLocalDate()));
    }

    /** MaterialDatePicker works with UTC midnight of the selected day. */
    private static long utcMillis(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private void addChoices(ChipGroup group, List<MemoryChoices.Choice> choices, Consumer<String> event) {
        for (MemoryChoices.Choice choice : choices) {
            Chip chip = (Chip) getLayoutInflater().inflate(R.layout.item_choice_chip, group, false);
            chip.setId(View.generateViewId());
            chip.setTag(choice.code());
            chip.setText(choice.label());
            group.addView(chip);
        }
        group.setOnCheckedStateChangeListener((chips, checked) -> {
            if (rendering) return;
            event.accept(checked.isEmpty() ? null : (String) chips.findViewById(checked.get(0)).getTag());
        });
    }

    /** The server counts Unicode characters, so an emoji counts as one character here too. */
    private static void countCodePoints(TextInputLayout layout) {
        layout.setLengthCounter(text -> text == null ? 0 : Character.codePointCount(text, 0, text.length()));
    }

    private android.text.TextWatcher draftWatcher(Consumer<String> event) {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable value) {
                if (!rendering) event.accept(value.toString());
            }
        };
    }

    private void renderMemories(MemoriesUiState state) {
        if (state.sessionExpired()) app.clearSession();
        boolean signedIn = state.signedIn();
        binding.memoryLogin.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.memoryContent.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        boolean editable = !state.saving();
        binding.memoryTitle.setEnabled(editable);
        binding.memoryDescription.setEnabled(editable);
        binding.memoryDate.setEnabled(editable);
        binding.memoryToday.setEnabled(editable);
        binding.memoryLatitude.setEnabled(editable);
        binding.memoryLongitude.setEnabled(editable);
        binding.memorySave.setEnabled(editable);
        rendering = true;
        showText(binding.memoryTitle, state.title());
        showText(binding.memoryDescription, state.description());
        showText(binding.memoryLatitude, state.latitude());
        showText(binding.memoryLongitude, state.longitude());
        showChoice(binding.memoryCategories, state.category(), editable);
        showChoice(binding.memoryEmotions, state.emotion(), editable);
        rendering = false;
        binding.memoryDate.setText(state.date() == null
                ? getString(R.string.memory_date_select) : state.date().toString());
        showError(binding.memoryTitleLayout, state.has(Validation.TITLE_REQUIRED) ? R.string.memory_title_missing
                : state.has(Validation.TITLE_TOO_LONG) ? R.string.memory_title_too_long : 0);
        showError(binding.memoryDescriptionLayout,
                state.has(Validation.DESCRIPTION_TOO_LONG) ? R.string.memory_description_too_long : 0);
        showError(binding.memoryLatitudeLayout,
                state.has(Validation.LOCATION_INCOMPLETE) ? R.string.memory_location_incomplete
                        : state.has(Validation.LOCATION_INVALID) ? R.string.memory_location_invalid : 0);
        int dateError = state.has(Validation.DATE_REQUIRED) ? R.string.memory_date_missing
                : state.has(Validation.DATE_IN_FUTURE) ? R.string.memory_date_future : 0;
        binding.memoryDateError.setVisibility(dateError == 0 ? View.GONE : View.VISIBLE);
        binding.memoryDateError.setText(dateError == 0 ? "" : getString(dateError));
        binding.memorySaveStatus.setText(state.saving() ? getString(R.string.memory_saving) : error(state.saveError()));
        binding.memoryListStatus.setText(state.loading() ? getString(R.string.memory_loading)
                : state.listError() != null ? error(state.listError())
                : state.loaded() && state.rows().isEmpty() ? getString(R.string.memory_empty) : "");
        binding.memoryRows.removeAllViews();
        for (var item : state.rows()) {
            TextView row = new TextView(requireContext());
            row.setText(getString(R.string.memory_row, item.title(), item.memoryDate()));
            binding.memoryRows.addView(row);
        }
        binding.memoryMore.setEnabled(!state.loading());
        binding.memoryMore.setVisibility(state.hasMore() ? View.VISIBLE : View.GONE);
    }

    private static void showText(TextView field, String value) {
        if (!field.getText().toString().equals(value)) field.setText(value);
    }

    private static void showChoice(ChipGroup group, String code, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++) {
            Chip chip = (Chip) group.getChildAt(i);
            chip.setChecked(chip.getTag().equals(code));
            chip.setEnabled(enabled);
        }
    }

    private void showError(TextInputLayout layout, @StringRes int message) {
        layout.setError(message == 0 ? null : getString(message));
    }

    private String error(ApiError error) {
        return error == null ? "" : ErrorMessages.describe(requireContext(), error);
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
