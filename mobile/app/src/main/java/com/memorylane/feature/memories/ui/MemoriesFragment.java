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
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.core.ui.UiState;
import com.memorylane.databinding.FragmentMemoriesBinding;
import com.memorylane.feature.health.data.HealthResponse;
import com.memorylane.feature.health.ui.HealthViewModel;
import com.memorylane.feature.memories.data.MemoryApi;

import java.time.LocalDate;
import java.util.UUID;

public class MemoriesFragment extends Fragment {
    private FragmentMemoriesBinding binding;
    private HealthViewModel healthViewModel;
    private String baseUrl;
    private AppContainer app;
    private Cancellable saving;
    private Cancellable listing;
    private Form form;
    private int nextPage;

    public static final class Form extends ViewModel {
        String session;
        LocalDate date;
        MemoryApi.Draft attempted;
        String key;

        void clear() {
            date = null;
            attempted = null;
            key = null;
        }
    }

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
        form = new ViewModelProvider(this).get(Form.class);
        if (!java.util.Objects.equals(form.session, app.authorization())) form.clear();
        form.session = app.authorization();
        if (form.attempted != null) {
            binding.memoryTitle.setText(form.attempted.title());
            binding.memoryDescription.setText(form.attempted.description());
        }
        renderDate();
        binding.memoryLogin.setOnClickListener(button ->
                NavHostFragment.findNavController(this).navigate(R.id.loginFragment));
        binding.memoryDate.setOnClickListener(button -> chooseDate());
        binding.memorySave.setOnClickListener(button -> save());
        binding.memoryRefresh.setOnClickListener(button -> load(0));
        binding.memoryMore.setOnClickListener(button -> load(nextPage));
        binding.memoryLogout.setOnClickListener(button -> {
            app.endSession();
            signedOut();
        });
        if (form.session == null) signedOut();
        else {
            binding.memoryLogin.setVisibility(View.GONE);
            binding.memoryContent.setVisibility(View.VISIBLE);
            load(0);
        }
        baseUrl = appContainer.getBaseUrl();
        healthViewModel = new ViewModelProvider(this, HealthViewModel.factory(appContainer.getHealthRepository()))
                .get(HealthViewModel.class);
        healthViewModel.getState().observe(getViewLifecycleOwner(), this::renderHealth);
        healthViewModel.loadIfNeeded();
    }

    private void chooseDate() {
        LocalDate day = form.date == null ? LocalDate.now() : form.date;
        DatePickerDialog picker = new DatePickerDialog(requireContext(), (view, year, month, date) -> {
            form.date = LocalDate.of(year, month + 1, date);
            if (binding != null) renderDate();
        }, day.getYear(), day.getMonthValue() - 1, day.getDayOfMonth());
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.show();
    }

    private void renderDate() {
        binding.memoryDate.setText(form.date == null ? getString(R.string.memory_date_select) : form.date.toString());
    }

    private void save() {
        String title = binding.memoryTitle.getText().toString().trim();
        if (title.isEmpty()) {
            binding.memoryTitle.setError(getString(R.string.memory_title_missing));
            return;
        }
        if (form.date == null) {
            binding.memorySaveStatus.setText(R.string.memory_date_missing);
            return;
        }
        var draft = new MemoryApi.Draft(title, binding.memoryDescription.getText().toString(), form.date.toString());
        if (!draft.equals(form.attempted)) {
            form.attempted = draft;
            form.key = UUID.randomUUID().toString();
        }
        setSaving(true);
        binding.memorySaveStatus.setText(R.string.memory_saving);
        saving = app.getCaller().enqueue(app.getMemoryApi().create(form.session, form.key, draft), result -> {
            saving = null;
            setSaving(false);
            if (!result.isSuccess()) {
                showError(binding.memorySaveStatus, result.getError());
                return;
            }
            form.clear();
            binding.memoryTitle.setText("");
            binding.memoryDescription.setText("");
            binding.memorySaveStatus.setText("");
            renderDate();
            load(0);
        });
    }

    private void setSaving(boolean busy) {
        binding.memorySave.setEnabled(!busy);
        binding.memoryTitle.setEnabled(!busy);
        binding.memoryDescription.setEnabled(!busy);
        binding.memoryDate.setEnabled(!busy);
    }

    private void load(int page) {
        if (listing != null) listing.cancel();
        binding.memoryMore.setEnabled(false);
        binding.memoryListStatus.setText(R.string.memory_loading);
        listing = app.getCaller().enqueue(app.getMemoryApi().list(form.session, page), result -> {
            listing = null;
            binding.memoryMore.setEnabled(true);
            if (!result.isSuccess()) {
                showError(binding.memoryListStatus, result.getError());
                return;
            }
            MemoryApi.Page response = result.getData();
            if (response == null || response.content() == null) {
                showError(binding.memoryListStatus, ApiError.parse());
                return;
            }
            if (page == 0) binding.memoryRows.removeAllViews();
            for (MemoryApi.Memory item : response.content()) {
                TextView row = new TextView(requireContext());
                row.setText(getString(R.string.memory_row, item.title(), item.memoryDate()));
                binding.memoryRows.addView(row);
            }
            binding.memoryListStatus.setText(page == 0 && response.content().isEmpty()
                    ? getString(R.string.memory_empty) : "");
            binding.memoryMore.setVisibility(response.hasNext() ? View.VISIBLE : View.GONE);
            nextPage = page + 1;
        });
    }

    private void showError(TextView label, ApiError error) {
        if (error.getHttpStatus() == 401) {
            app.clearSession();
            signedOut();
        } else {
            label.setText(ErrorMessages.describe(requireContext(), error));
        }
    }

    private void signedOut() {
        cancelRequests();
        form.session = null;
        form.clear();
        binding.memoryRows.removeAllViews();
        binding.memoryTitle.setText("");
        binding.memoryDescription.setText("");
        binding.memorySaveStatus.setText("");
        binding.memoryListStatus.setText("");
        setSaving(false);
        renderDate();
        binding.memoryContent.setVisibility(View.GONE);
        binding.memoryLogin.setVisibility(View.VISIBLE);
    }

    private void cancelRequests() {
        if (saving != null) saving.cancel();
        if (listing != null) listing.cancel();
        saving = null;
        listing = null;
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
        cancelRequests();
        super.onDestroyView();
        binding = null;
    }
}
