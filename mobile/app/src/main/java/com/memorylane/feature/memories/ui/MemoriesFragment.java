package com.memorylane.feature.memories.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.core.ui.UiState;
import com.memorylane.databinding.FragmentMemoriesBinding;
import com.memorylane.feature.health.data.HealthResponse;
import com.memorylane.feature.health.ui.HealthViewModel;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;

/** Memory list: draws {@link MemoryListUiState} in {@link #render} and forwards taps to the ViewModel. */
public class MemoriesFragment extends Fragment {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private FragmentMemoriesBinding binding;
    private AppContainer app;
    private MemoriesViewModel viewModel;
    private HealthViewModel healthViewModel;
    private MemoryListAdapter listAdapter;
    private MemoryListFooterAdapter footerAdapter;
    @Nullable private MemoryListUiState.Status renderedStatus;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMemoriesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = AppContainer.from(requireContext());
        viewModel = new ViewModelProvider(this, MemoriesViewModel.factory(app.getMemoryRepository()))
                .get(MemoriesViewModel.class);

        listAdapter = new MemoryListAdapter(DateTimeFormatter.ofPattern(
                getString(R.string.memory_list_date_pattern), TURKISH));
        footerAdapter = new MemoryListFooterAdapter(viewModel::loadMore, viewModel::retry);
        binding.memoryList.setAdapter(new ConcatAdapter(listAdapter, footerAdapter));
        binding.memoryList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView list, int dx, int dy) {
                // Icon only while reading down the list, full label again when scrolling back.
                if (dy > 0) binding.memoryAdd.shrink();
                else if (dy < 0) binding.memoryAdd.extend();
            }
        });

        binding.memorySignIn.setOnClickListener(button -> openLogin());
        binding.memoryAdd.setOnClickListener(button -> openCreate());
        binding.memoryLogout.setOnClickListener(button -> {
            app.endSession();
            viewModel.setSession(null);
        });

        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        observeCreatedMemory();
        viewModel.sessionExpired().observe(getViewLifecycleOwner(), expired -> {
            if (!Boolean.TRUE.equals(expired)) return;
            viewModel.consumeSessionExpired();
            app.clearSession();
            Snackbar.make(binding.getRoot(), R.string.memory_list_session_expired, Snackbar.LENGTH_LONG).show();
        });
        viewModel.setSession(app.authorization());

        healthViewModel = new ViewModelProvider(this, HealthViewModel.factory(app.getHealthRepository()))
                .get(HealthViewModel.class);
        healthViewModel.getState().observe(getViewLifecycleOwner(), this::renderHealth);
        healthViewModel.loadIfNeeded();
    }

    private void render(MemoryListUiState state) {
        MemoryListUiState.Status status = state.getStatus();
        boolean signedIn = status != MemoryListUiState.Status.SIGNED_OUT;
        boolean content = status == MemoryListUiState.Status.CONTENT;

        binding.memoryList.setVisibility(content ? View.VISIBLE : View.GONE);
        binding.memoryStatus.setVisibility(content ? View.GONE : View.VISIBLE);
        if (!content && status != renderedStatus) {
            // A short message cannot scroll the header back in, so bring it back when the list
            // gives way to a message (e.g. the session expired while scrolled down).
            binding.memoryAppBar.setExpanded(true, true);
        }
        renderedStatus = status;
        binding.memorySignIn.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        binding.memoryLogout.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (signedIn) {
            binding.memoryAdd.show();
        } else {
            binding.memoryAdd.hide();
        }

        switch (status) {
            case SIGNED_OUT:
                binding.memoryState.showEmpty(getString(R.string.memory_list_signed_out_title),
                        getString(R.string.memory_list_signed_out_message));
                break;
            case LOADING:
                binding.memoryState.showLoading(getString(R.string.memory_list_loading));
                break;
            case EMPTY:
                binding.memoryState.showEmpty(getString(R.string.memory_list_empty_title),
                        getString(R.string.memory_list_empty_message));
                break;
            case ERROR:
                binding.memoryState.showError(getString(R.string.memory_list_error_title),
                        describe(state.getError()), viewModel::retry);
                break;
            case CONTENT:
            default:
                binding.memoryState.hide();
                break;
        }

        listAdapter.submitList(content ? state.getItems() : Collections.emptyList());
        footerAdapter.submit(footerMode(state), content && state.getError() != null
                ? describe(state.getError()) : null);
    }

    private static MemoryListFooterAdapter.Mode footerMode(MemoryListUiState state) {
        if (state.getStatus() != MemoryListUiState.Status.CONTENT) return MemoryListFooterAdapter.Mode.NONE;
        if (state.isLoadingMore()) return MemoryListFooterAdapter.Mode.LOADING;
        if (state.getError() != null) return MemoryListFooterAdapter.Mode.ERROR;
        if (state.canLoadMore()) return MemoryListFooterAdapter.Mode.LOAD_MORE;
        return MemoryListFooterAdapter.Mode.NONE;
    }

    private String describe(@Nullable ApiError error) {
        return ErrorMessages.describe(requireContext(), error != null ? error : ApiError.unexpected());
    }

    private void openLogin() {
        navigateFromList(R.id.loginFragment);
    }

    private void openCreate() {
        navigateFromList(R.id.action_memoriesFragment_to_memoryCreateFragment);
    }

    /** A fast double tap delivers a second click after the first navigation; it is ignored. */
    private void navigateFromList(int destinationOrAction) {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        if (current != null && current.getId() == R.id.memoriesFragment) {
            navController.navigate(destinationOrAction);
        }
    }

    /**
     * The create screen leaves {@link MemoryCreateFragment#RESULT_CREATED} on this entry after a
     * save; refresh once and remove it so a later view does not refresh again.
     */
    private void observeCreatedMemory() {
        SavedStateHandle results = NavHostFragment.findNavController(this)
                .getBackStackEntry(R.id.memoriesFragment).getSavedStateHandle();
        results.<Boolean>getLiveData(MemoryCreateFragment.RESULT_CREATED, false)
                .observe(getViewLifecycleOwner(), created -> {
                    if (!Boolean.TRUE.equals(created)) return;
                    results.set(MemoryCreateFragment.RESULT_CREATED, false);
                    viewModel.refresh();
                    Snackbar.make(binding.getRoot(), R.string.memory_create_saved, Snackbar.LENGTH_SHORT)
                            .setAnchorView(binding.memoryAdd)
                            .show();
                });
    }

    // --- Temporary server health card; moves to the profile tab in ML-07 commit 6. ---

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
                binding.healthCard.setVisibility(View.GONE);
                binding.healthState.showError(getString(R.string.error_title),
                        describe(state.getError()), healthViewModel::refresh);
                break;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
