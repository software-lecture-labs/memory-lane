package com.memorylane.feature.memories.ui;

import android.content.res.Resources;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.databinding.FragmentMemoryDetailBinding;
import com.memorylane.feature.memories.data.MemoryApi;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Memory detail: draws {@link MemoryDetailUiState} in {@link #render} and forwards taps. */
public class MemoryDetailFragment extends Fragment {
    /** Navigation argument: id of the memory to show (nav_graph.xml, memoryDetailFragment). */
    static final String ARG_MEMORY_ID = "memoryId";

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private FragmentMemoryDetailBinding binding;
    private AppContainer app;
    private MemoryDetailViewModel viewModel;
    private final PagerSnapHelper pager = new PagerSnapHelper();
    /** The memory the gallery was built for; a re-delivered state keeps the page the user is on. */
    @Nullable private MemoryApi.Detail shownMemory;
    /** The gallery's photos in gallery order; the full-screen viewer opens with the same list. */
    private List<MemoryApi.Photo> shownPhotos = new ArrayList<>();
    private int photoCount;
    private int currentPhoto;

    /** Arguments for opening the detail of {@code memoryId}. */
    static Bundle arguments(@NonNull String memoryId) {
        Bundle arguments = new Bundle();
        arguments.putString(ARG_MEMORY_ID, Objects.requireNonNull(memoryId, "memoryId"));
        return arguments;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMemoryDetailBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = AppContainer.from(requireContext());
        String memoryId = Objects.requireNonNull(requireArguments().getString(ARG_MEMORY_ID), ARG_MEMORY_ID);
        viewModel = new ViewModelProvider(this, MemoryDetailViewModel.factory(app.getMemoryRepository(), memoryId))
                .get(MemoryDetailViewModel.class);
        shownMemory = null;

        ViewCompat.setAccessibilityHeading(binding.detailScreenTitle, true);
        binding.detailBack.setOnClickListener(button -> close());
        binding.detailBackToList.setOnClickListener(button -> close());
        binding.detailEdit.setOnClickListener(button -> openEditor());
        binding.detailDelete.setOnClickListener(button -> openDeletePanel());
        getChildFragmentManager().setFragmentResultListener(MemoryDeleteSheet.RESULT_DELETED,
                getViewLifecycleOwner(), (key, result) -> onDeleted());
        binding.detailZoomHint.setOnClickListener(hint -> openPhotoViewer(currentPhoto));
        pager.attachToRecyclerView(binding.detailPhotos);
        binding.detailPhotos.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView list, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updateCurrentPhoto();
            }
        });

        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        observeUpdatedMemory();
        viewModel.start(app.authorization());
    }

    private void render(MemoryDetailUiState state) {
        MemoryDetailUiState.Status status = state.getStatus();
        boolean content = status == MemoryDetailUiState.Status.CONTENT;
        binding.detailContent.setVisibility(content ? View.VISIBLE : View.GONE);
        binding.detailStatus.setVisibility(content ? View.GONE : View.VISIBLE);
        binding.detailBackToList.setVisibility(status == MemoryDetailUiState.Status.NOT_FOUND ? View.VISIBLE : View.GONE);
        switch (status) {
            case SIGNED_OUT:
                // Normally already done by AuthInterceptor; the session layer opens the login screen.
                app.clearSession();
                binding.detailState.hide();
                break;
            case LOADING:
                binding.detailState.showLoading(getString(R.string.memory_detail_loading));
                break;
            case NOT_FOUND:
                binding.detailState.showEmpty(getString(R.string.memory_detail_not_found_title),
                        getString(R.string.memory_detail_not_found_message));
                break;
            case ERROR:
                binding.detailState.showError(getString(R.string.memory_detail_error_title),
                        describe(state.getError()), viewModel::retry);
                break;
            case CONTENT:
            default:
                binding.detailState.hide();
                bindMemory(state);
                break;
        }
    }

    private void bindMemory(MemoryDetailUiState state) {
        MemoryApi.Detail memory = Objects.requireNonNull(state.getMemory());
        Resources resources = getResources();
        binding.detailDate.setText(DateTimeFormatter.ofPattern(getString(R.string.memory_detail_date_pattern), TURKISH)
                .format(Objects.requireNonNull(state.getDate())));
        binding.detailTitle.setText(memory.title());

        boolean category = showLabel(binding.detailBadgeCategory, MemoryLabels.category(memory.category()));
        boolean emotion = showLabel(binding.detailBadgeEmotion, MemoryLabels.emotion(memory.emotion()));
        binding.detailBadges.setVisibility(category || emotion ? View.VISIBLE : View.GONE);

        String note = memory.description();
        boolean hasNote = note != null && !note.trim().isEmpty();
        binding.detailNote.setText(hasNote ? note : getString(R.string.memory_detail_note_empty));
        binding.detailNote.setTextColor(ContextCompat.getColor(requireContext(),
                hasNote ? R.color.ml_plum : R.color.ml_plum_muted));

        binding.detailLocationCard.setVisibility(state.hasLocation() ? View.VISIBLE : View.GONE);
        if (state.hasLocation()) {
            binding.detailLocation.setText(MemoryLabels.coordinates(Objects.requireNonNull(memory.location()),
                    resources.getString(R.string.memory_direction_north),
                    resources.getString(R.string.memory_direction_south),
                    resources.getString(R.string.memory_direction_east),
                    resources.getString(R.string.memory_direction_west)));
        }

        binding.detailGallery.setVisibility(state.hasPhotos() ? View.VISIBLE : View.GONE);
        if (memory != shownMemory) {
            shownMemory = memory;
            shownPhotos = state.getPhotos();
            bindGallery(shownPhotos);
        }
    }

    private void bindGallery(List<MemoryApi.Photo> photos) {
        photoCount = photos.size();
        if (photoCount == 0) {
            binding.detailPhotos.setAdapter(null);
            return;
        }
        Resources resources = getResources();
        // Decode at the frame's size: screen width minus the page and frame padding.
        int width = resources.getDisplayMetrics().widthPixels
                - 2 * resources.getDimensionPixelSize(R.dimen.ml_space_md)
                - 2 * resources.getDimensionPixelSize(R.dimen.memory_detail_frame_padding);
        int height = resources.getDimensionPixelSize(R.dimen.memory_detail_photo_height);
        binding.detailPhotos.setAdapter(new MemoryPhotoAdapter(app.getPhotoImageLoader(), viewModel.memoryId(),
                photos, Math.max(width, 1), height, this::openPhotoViewer));
        buildDots();
        currentPhoto = 0;
        showCurrentPhoto();
        // After a rotation the list restores its own scroll position; read it once laid out.
        binding.detailPhotos.post(this::updateCurrentPhoto);
    }

    private void buildDots() {
        LinearLayout dots = binding.detailDots;
        dots.removeAllViews();
        int gap = getResources().getDimensionPixelSize(R.dimen.memory_dot_gap);
        for (int i = 0; i < photoCount; i++) {
            ImageView dot = new ImageView(requireContext());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(i == 0 ? 0 : gap);
            dots.addView(dot, params);
        }
        // One photo needs no page dots.
        dots.setVisibility(photoCount > 1 ? View.VISIBLE : View.GONE);
    }

    private void updateCurrentPhoto() {
        if (binding == null) return;
        RecyclerView.LayoutManager layout = binding.detailPhotos.getLayoutManager();
        View snapped = layout == null ? null : pager.findSnapView(layout);
        if (snapped == null) return;
        int position = ((LinearLayoutManager) layout).getPosition(snapped);
        if (position == RecyclerView.NO_POSITION || position == currentPhoto) return;
        currentPhoto = position;
        showCurrentPhoto();
    }

    private void showCurrentPhoto() {
        binding.detailPhotoCounter.setText(getString(R.string.memory_detail_photo_counter, currentPhoto + 1, photoCount));
        for (int i = 0; i < binding.detailDots.getChildCount(); i++) {
            ((ImageView) binding.detailDots.getChildAt(i)).setImageResource(
                    i == currentPhoto ? R.drawable.memory_dot_active : R.drawable.memory_dot_inactive);
        }
    }

    private static boolean showLabel(Chip badge, @StringRes int label) {
        boolean shown = label != MemoryLabels.NONE;
        if (shown) badge.setText(label);
        badge.setVisibility(shown ? View.VISIBLE : View.GONE);
        return shown;
    }

    /** Opens the full-screen viewer at {@code position} with the gallery's photos, in order. */
    private void openPhotoViewer(int position) {
        MemoryApi.Detail memory = shownMemory;
        if (memory == null || position < 0 || position >= photoCount) return;
        List<String> photoIds = new ArrayList<>();
        for (MemoryApi.Photo photo : shownPhotos) photoIds.add(photo.id());
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        // A fast double tap delivers a second click after the first navigation; it is ignored.
        if (current == null || current.getId() != R.id.memoryDetailFragment) return;
        navController.navigate(R.id.action_memoryDetailFragment_to_memoryPhotoViewerFragment,
                MemoryPhotoViewerFragment.arguments(viewModel.memoryId(), photoIds, position,
                        memory.title(), memory.memoryDate()));
    }

    /** Opens the edit form; it reads the memory and its ETag itself (the contract's only tag source). */
    private void openEditor() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        // A fast double tap delivers a second click after the first navigation; it is ignored.
        if (current == null || current.getId() != R.id.memoryDetailFragment) return;
        navController.navigate(R.id.action_memoryDetailFragment_to_memoryEditFragment,
                MemoryEditFragment.arguments(viewModel.memoryId()));
    }

    /** Shows the delete confirmation for the memory on screen; a second tap does not open another. */
    private void openDeletePanel() {
        MemoryApi.Detail memory = shownMemory;
        if (memory == null || getChildFragmentManager().findFragmentByTag(MemoryDeleteSheet.TAG) != null) return;
        MemoryDeleteSheet.forMemory(viewModel.memoryId(), memory.title(), memory.memoryDate(),
                memory.photos().size(), memory.coverPhotoId()).show(getChildFragmentManager(), MemoryDeleteSheet.TAG);
    }

    /**
     * The memory is gone: the timeline refreshes and announces it (if it is on the back stack;
     * a detail opened from the map has none and the map reloads its pins itself), then this
     * screen closes.
     */
    private void onDeleted() {
        NavController navController = NavHostFragment.findNavController(this);
        try {
            navController.getBackStackEntry(R.id.memoriesFragment).getSavedStateHandle()
                    .set(MemoryDeleteSheet.RESULT_DELETED, true);
        } catch (IllegalArgumentException notOnTheStack) {
            Snackbar.make(requireActivity().findViewById(android.R.id.content),
                    R.string.memory_deleted, Snackbar.LENGTH_SHORT).show();
        }
        close();
    }

    /**
     * The edit form leaves {@link MemoryEditFragment#RESULT_UPDATED} on this entry after a save;
     * read the memory again once and remove the flag so a later view does not reload again.
     */
    private void observeUpdatedMemory() {
        SavedStateHandle results;
        try {
            results = NavHostFragment.findNavController(this)
                    .getBackStackEntry(R.id.memoryDetailFragment).getSavedStateHandle();
        } catch (IllegalArgumentException replacedByLogin) {
            return; // the session ended and the stack was replaced before this view was created
        }
        results.<Boolean>getLiveData(MemoryEditFragment.RESULT_UPDATED, false)
                .observe(getViewLifecycleOwner(), updated -> {
                    if (!Boolean.TRUE.equals(updated)) return;
                    results.set(MemoryEditFragment.RESULT_UPDATED, false);
                    viewModel.refresh();
                    Snackbar.make(binding.getRoot(), R.string.memory_edit_saved, Snackbar.LENGTH_SHORT).show();
                });
    }

    private void close() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        // A fast double tap delivers a second click after the first pop; it is ignored.
        if (current != null && current.getId() == R.id.memoryDetailFragment) navController.popBackStack();
    }

    private String describe(@Nullable ApiError error) {
        return ErrorMessages.describe(requireContext(), error != null ? error : ApiError.unexpected());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        pager.attachToRecyclerView(null);
        binding = null;
    }
}
