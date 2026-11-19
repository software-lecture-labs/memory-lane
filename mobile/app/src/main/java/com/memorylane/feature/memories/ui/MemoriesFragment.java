package com.memorylane.feature.memories.ui;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.widget.TextView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.core.ui.UiState;
import com.memorylane.databinding.FragmentMemoriesBinding;
import com.memorylane.feature.health.data.HealthResponse;
import com.memorylane.feature.health.ui.HealthViewModel;

import java.time.LocalDate;

public class MemoriesFragment extends Fragment {
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
        binding.memorySave.setOnClickListener(button -> model.save());
        binding.memoryRefresh.setOnClickListener(button -> model.load(0));
        binding.memoryMore.setOnClickListener(button -> model.more());
        binding.memoryLogout.setOnClickListener(button -> {
            app.endSession();
            model.onLogout();
        });
        binding.memoryTitle.addTextChangedListener(draftWatcher(model::onTitleChanged));
        binding.memoryDescription.addTextChangedListener(draftWatcher(model::onDescriptionChanged));
        model.state().observe(getViewLifecycleOwner(), this::renderMemories);
        baseUrl = appContainer.getBaseUrl();
        healthViewModel = new ViewModelProvider(this, HealthViewModel.factory(appContainer.getHealthRepository()))
                .get(HealthViewModel.class);
        healthViewModel.getState().observe(getViewLifecycleOwner(), this::renderHealth);
        healthViewModel.loadIfNeeded();
    }

    private void chooseDate() {
        LocalDate selected = model.state().getValue().date();
        LocalDate day = selected == null ? LocalDate.now() : selected;
        DatePickerDialog picker = new DatePickerDialog(requireContext(), (view, year, month, date) -> {
            model.onDateSelected(LocalDate.of(year, month + 1, date));
        }, day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth());
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.show();
    }

    private android.text.TextWatcher draftWatcher(java.util.function.Consumer<String> event) {
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
        binding.memoryTitle.setEnabled(!state.saving());
        binding.memoryDescription.setEnabled(!state.saving());
        binding.memoryDate.setEnabled(!state.saving());
        binding.memorySave.setEnabled(!state.saving());
        rendering = true;
        if (!binding.memoryTitle.getText().toString().equals(state.title())) binding.memoryTitle.setText(state.title());
        if (!binding.memoryDescription.getText().toString().equals(state.description())) {
            binding.memoryDescription.setText(state.description());
        }
        rendering = false;
        binding.memoryDate.setText(state.date() == null
                ? getString(R.string.memory_date_select) : state.date().toString());
        binding.memoryTitle.setError(state.validation() == MemoriesUiState.Validation.TITLE_REQUIRED
                ? getString(R.string.memory_title_missing) : null);
        binding.memorySaveStatus.setText(state.saving() ? getString(R.string.memory_saving) : state.validation() == MemoriesUiState.Validation.DATE_REQUIRED
                        ? getString(R.string.memory_date_missing) : error(state.saveError()));
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
