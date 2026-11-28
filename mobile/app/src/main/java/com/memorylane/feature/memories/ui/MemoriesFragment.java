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
import com.memorylane.databinding.FragmentMemoriesBinding;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Locale;

/** Memory list: draws {@link MemoryListUiState} in {@link #render} and forwards taps to the ViewModel. */
public class MemoriesFragment extends Fragment {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private FragmentMemoriesBinding binding;
    private AppContainer app;
    private MemoriesViewModel viewModel;
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

        // Covers are decoded for the card's width on this screen and the cover's fixed height.
        int coverWidth = getResources().getDisplayMetrics().widthPixels;
        int coverHeight = getResources().getDimensionPixelSize(R.dimen.memory_cover_height);
        listAdapter = new MemoryListAdapter(DateTimeFormatter.ofPattern(
                getString(R.string.memory_list_date_pattern), TURKISH), app.getPhotoImageLoader(),
                coverWidth, coverHeight, this::openDetail);
        footerAdapter = new MemoryListFooterAdapter(viewModel::loadMore, viewModel::retry);
        binding.memoryList.setAdapter(new ConcatAdapter(listAdapter, footerAdapter));
        binding.memoryList.addItemDecoration(new MemoryTimelineDecoration(requireContext(), listAdapter));
        listAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                // LinearLayoutManager keeps the first visible row in place, so a memory added
                // at the top (e.g. just created) would land above the viewport. Follow it only
                // when the reader was already at the top; never jump while they scroll.
                if (positionStart == 0 && binding != null && !binding.memoryList.canScrollVertically(-1)) {
                    binding.memoryList.scrollToPosition(0);
                }
            }
        });
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

        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        observeSavedMemories();
        viewModel.sessionExpired().observe(getViewLifecycleOwner(), expired -> {
            if (!Boolean.TRUE.equals(expired)) return;
            viewModel.consumeSessionExpired();
            app.clearSession();
            Snackbar.make(binding.getRoot(), R.string.memory_list_session_expired, Snackbar.LENGTH_LONG).show();
        });
        // Each time the view comes back (e.g. from the profile tab after signing out) the list
        // follows the current session: a different or missing token resets it.
        viewModel.setSession(app.authorization());
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

        MemoryListFooterAdapter.Mode footer = footerMode(state);
        String footerMessage = content && state.getError() != null ? describe(state.getError()) : null;
        // ListAdapter applies rows after a background diff. Updating the footer first would make
        // it the only row for a moment; RecyclerView then keeps it anchored on screen and the
        // new rows end up above the viewport. So the footer follows once the rows are in.
        listAdapter.submitList(content ? state.getItems() : Collections.emptyList(),
                () -> footerAdapter.submit(footer, footerMessage));
    }

    private static MemoryListFooterAdapter.Mode footerMode(MemoryListUiState state) {
        if (state.getStatus() != MemoryListUiState.Status.CONTENT) return MemoryListFooterAdapter.Mode.NONE;
        if (state.isLoadingMore()) return MemoryListFooterAdapter.Mode.LOADING;
        if (state.getError() != null) return MemoryListFooterAdapter.Mode.ERROR;
        if (state.canLoadMore()) return MemoryListFooterAdapter.Mode.LOAD_MORE;
        return MemoryListFooterAdapter.Mode.END;
    }

    private String describe(@Nullable ApiError error) {
        return ErrorMessages.describe(requireContext(), error != null ? error : ApiError.unexpected());
    }

    private void openCreate() {
        navigateFromList(R.id.action_memoriesFragment_to_memoryCreateFragment, null);
    }

    private void openDetail(@NonNull String memoryId) {
        navigateFromList(R.id.action_memoriesFragment_to_memoryDetailFragment,
                MemoryDetailFragment.arguments(memoryId));
    }

    private void openLogin() {
        navigateFromList(R.id.loginFragment, null);
    }

    /** A fast double tap delivers a second click after the first navigation; it is ignored. */
    private void navigateFromList(int destinationOrAction, @Nullable Bundle arguments) {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        if (current != null && current.getId() == R.id.memoriesFragment) {
            navController.navigate(destinationOrAction, arguments);
        }
    }

    /**
     * The create screen leaves {@link MemoryCreateFragment#RESULT_CREATED} and the edit screen
     * {@link MemoryEditFragment#RESULT_UPDATED} on this entry after a save; refresh once and
     * remove the flag so a later view does not refresh again. A created memory is announced here;
     * an edit was already announced on the detail screen, so the rows just follow it.
     *
     * <p>On a cold start without a session, SessionNavigator replaces this destination with the
     * login screen before this view is created; the entry is then already gone and there is no
     * result to wait for.
     */
    private void observeSavedMemories() {
        SavedStateHandle results;
        try {
            results = NavHostFragment.findNavController(this)
                    .getBackStackEntry(R.id.memoriesFragment).getSavedStateHandle();
        } catch (IllegalArgumentException replacedByLogin) {
            return;
        }
        results.<Boolean>getLiveData(MemoryCreateFragment.RESULT_CREATED, false)
                .observe(getViewLifecycleOwner(), created -> {
                    if (!Boolean.TRUE.equals(created)) return;
                    results.set(MemoryCreateFragment.RESULT_CREATED, false);
                    viewModel.refresh();
                    Snackbar.make(binding.getRoot(), R.string.memory_create_saved, Snackbar.LENGTH_SHORT)
                            .setAnchorView(binding.memoryAdd)
                            .show();
                });
        results.<Boolean>getLiveData(MemoryEditFragment.RESULT_UPDATED, false)
                .observe(getViewLifecycleOwner(), updated -> {
                    if (!Boolean.TRUE.equals(updated)) return;
                    results.set(MemoryEditFragment.RESULT_UPDATED, false);
                    viewModel.refresh();
                });
        // ML-13: the detail screen deleted a memory and came back here.
        results.<Boolean>getLiveData(MemoryDeleteSheet.RESULT_DELETED, false)
                .observe(getViewLifecycleOwner(), deleted -> {
                    if (!Boolean.TRUE.equals(deleted)) return;
                    results.set(MemoryDeleteSheet.RESULT_DELETED, false);
                    viewModel.refresh();
                    Snackbar.make(binding.getRoot(), R.string.memory_deleted, Snackbar.LENGTH_SHORT)
                            .setAnchorView(binding.memoryAdd)
                            .show();
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
