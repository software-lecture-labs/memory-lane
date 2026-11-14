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
        model = new ViewModelProvider(this).get(MemoriesViewModel.class);
        model.attach(app.getMemoryRepository(), app.authorization());
        binding.memoryLogin.setOnClickListener(button ->
                NavHostFragment.findNavController(this).navigate(R.id.loginFragment));
        binding.memoryDate.setOnClickListener(button -> chooseDate());
        binding.memorySave.setOnClickListener(button -> save());
        binding.memoryRefresh.setOnClickListener(button -> model.load(0));
        binding.memoryMore.setOnClickListener(button -> model.more());
        binding.memoryLogout.setOnClickListener(button -> {
            app.endSession();
            model.reset();
            renderMemories();
        });
        binding.memoryTitle.addTextChangedListener(draftWatcher());
        binding.memoryDescription.addTextChangedListener(draftWatcher());
        model.changes.observe(getViewLifecycleOwner(), ignored -> renderMemories());
        baseUrl = appContainer.getBaseUrl();
        healthViewModel = new ViewModelProvider(this, HealthViewModel.factory(appContainer.getHealthRepository()))
                .get(HealthViewModel.class);
        healthViewModel.getState().observe(getViewLifecycleOwner(), this::renderHealth);
        healthViewModel.loadIfNeeded();
    }

    private void chooseDate() {
        LocalDate day = model.date == null ? LocalDate.now() : model.date;
        DatePickerDialog picker = new DatePickerDialog(requireContext(), (view, year, month, date) -> {
            model.date = LocalDate.of(year, month + 1, date);
            if (binding != null) renderDate();
        }, day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth());
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.show();
    }

    private void renderDate() {
        binding.memoryDate.setText(model.date == null ? getString(R.string.memory_date_select) : model.date.toString());
    }

    private void save() {
        captureDraft();
        if (model.title.trim().isEmpty()) {
            binding.memoryTitle.setError(getString(R.string.memory_title_missing));
        } else if (model.date == null) {
            binding.memorySaveStatus.setText(R.string.memory_date_missing);
        } else {
            model.save();
        }
    }

    private android.text.TextWatcher draftWatcher() {
        return new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable value) {
                if (!rendering) captureDraft();
            }
        };
    }

    private void captureDraft() {
        model.title = binding.memoryTitle.getText().toString();
        model.description = binding.memoryDescription.getText().toString();
    }

    private void renderMemories() {
        if (unauthorized(model.saveError) || unauthorized(model.listError)) {
            app.clearSession();
            model.reset();
        }
        boolean signedIn = model.session != null;
        binding.memoryLogin.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.memoryContent.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        binding.memoryTitle.setEnabled(!model.saving);
        binding.memoryDescription.setEnabled(!model.saving);
        binding.memoryDate.setEnabled(!model.saving);
        binding.memorySave.setEnabled(!model.saving);
        rendering = true;
        if (!binding.memoryTitle.getText().toString().equals(model.title)) binding.memoryTitle.setText(model.title);
        if (!binding.memoryDescription.getText().toString().equals(model.description)) {
            binding.memoryDescription.setText(model.description);
        }
        rendering = false;
        renderDate();
        binding.memorySaveStatus.setText(model.saving ? getString(R.string.memory_saving) : error(model.saveError));
        binding.memoryListStatus.setText(model.loading ? getString(R.string.memory_loading)
                : model.listError != null ? error(model.listError)
                : model.loaded && model.rows.isEmpty() ? getString(R.string.memory_empty) : "");
        binding.memoryRows.removeAllViews();
        for (var item : model.rows) {
            TextView row = new TextView(requireContext());
            row.setText(getString(R.string.memory_row, item.title(), item.memoryDate()));
            binding.memoryRows.addView(row);
        }
        binding.memoryMore.setEnabled(!model.loading);
        binding.memoryMore.setVisibility(model.hasMore ? View.VISIBLE : View.GONE);
    }

    private boolean unauthorized(ApiError error) {
        return error != null && error.getHttpStatus() == 401;
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
