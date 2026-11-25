package com.memorylane.feature.memories.ui;

import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.databinding.FragmentMemoryPhotoViewerBinding;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Full-screen viewer for a memory's photos. Opens at the photo tapped on the detail screen,
 * pages with a swipe or the previous/next buttons and shows the memory's title and date below.
 * Everything it shows comes from its navigation arguments; the photos themselves load through
 * the session's photo loader, so signing out or a 401 clears them like any other screen.
 */
public class MemoryPhotoViewerFragment extends Fragment {
    static final String ARG_MEMORY_ID = "memoryId";
    static final String ARG_PHOTO_IDS = "photoIds";
    static final String ARG_INITIAL_POSITION = "initialPosition";
    static final String ARG_TITLE = "title";
    static final String ARG_MEMORY_DATE = "memoryDate";

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private FragmentMemoryPhotoViewerBinding binding;
    private final PagerSnapHelper pager = new PagerSnapHelper();
    private int photoCount;
    private int currentPhoto;

    /**
     * Arguments for opening {@code photoIds} (in gallery order) at {@code position}.
     *
     * @param memoryDate the memory's {@code YYYY-MM-DD} day, shown in the caption
     */
    static Bundle arguments(@NonNull String memoryId, @NonNull List<String> photoIds, int position,
                            @Nullable String title, @Nullable String memoryDate) {
        Bundle arguments = new Bundle();
        arguments.putString(ARG_MEMORY_ID, Objects.requireNonNull(memoryId, "memoryId"));
        arguments.putStringArray(ARG_PHOTO_IDS, photoIds.toArray(new String[0]));
        arguments.putInt(ARG_INITIAL_POSITION, position);
        arguments.putString(ARG_TITLE, title);
        arguments.putString(ARG_MEMORY_DATE, memoryDate);
        return arguments;
    }

    /** The page to open: the requested one, kept inside the list. */
    static int startPosition(int requested, int count) {
        if (count <= 0) return 0;
        return Math.max(0, Math.min(requested, count - 1));
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMemoryPhotoViewerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle arguments = requireArguments();
        String memoryId = Objects.requireNonNull(arguments.getString(ARG_MEMORY_ID), ARG_MEMORY_ID);
        String[] ids = arguments.getStringArray(ARG_PHOTO_IDS);
        List<String> photoIds = ids == null ? List.of() : Arrays.asList(ids);
        photoCount = photoIds.size();
        if (photoCount == 0) {
            // Nothing to show (the detail never opens the viewer without photos).
            close();
            return;
        }
        setSystemBarsHidden(true);

        binding.viewerClose.setOnClickListener(button -> close());
        binding.viewerPrevious.setOnClickListener(button -> showPage(currentPhoto - 1));
        binding.viewerNext.setOnClickListener(button -> showPage(currentPhoto + 1));
        bindCaption(arguments.getString(ARG_TITLE), arguments.getString(ARG_MEMORY_DATE));

        DisplayMetrics screen = getResources().getDisplayMetrics();
        binding.viewerPages.setAdapter(new PhotoPagerAdapter(AppContainer.from(requireContext()).getPhotoImageLoader(),
                memoryId, photoIds, screen.widthPixels, screen.heightPixels));
        pager.attachToRecyclerView(binding.viewerPages);
        binding.viewerPages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView list, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updateCurrentPhoto();
            }
        });
        buildDots();

        currentPhoto = startPosition(arguments.getInt(ARG_INITIAL_POSITION), photoCount);
        if (savedInstanceState == null) {
            binding.viewerPages.scrollToPosition(currentPhoto);
        }
        showCurrentPhoto();
        // After a rotation the list restores its own position; read it once laid out.
        binding.viewerPages.post(this::updateCurrentPhoto);
    }

    private void bindCaption(@Nullable String title, @Nullable String memoryDate) {
        boolean hasTitle = title != null && !title.trim().isEmpty();
        binding.viewerTitle.setText(hasTitle ? title : null);
        binding.viewerTitle.setVisibility(hasTitle ? View.VISIBLE : View.GONE);
        String date = formatDate(memoryDate);
        binding.viewerDate.setText(date);
        binding.viewerDate.setVisibility(date == null ? View.GONE : View.VISIBLE);
        binding.viewerCaption.setVisibility(hasTitle || date != null ? View.VISIBLE : View.GONE);
    }

    @Nullable
    private String formatDate(@Nullable String memoryDate) {
        if (memoryDate == null) return null;
        try {
            return DateTimeFormatter.ofPattern(getString(R.string.memory_list_date_pattern), TURKISH)
                    .format(LocalDate.parse(memoryDate));
        } catch (DateTimeParseException notADay) {
            return null;
        }
    }

    private void buildDots() {
        LinearLayout dots = binding.viewerDots;
        dots.removeAllViews();
        int gap = getResources().getDimensionPixelSize(R.dimen.memory_dot_gap);
        for (int i = 0; i < photoCount; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(i == 0 ? 0 : gap);
            dots.addView(new ImageView(requireContext()), params);
        }
        // One photo needs no page dots; the space stays so the caption does not jump.
        dots.setVisibility(photoCount > 1 ? View.VISIBLE : View.INVISIBLE);
    }

    private void showPage(int position) {
        if (position < 0 || position >= photoCount) return;
        currentPhoto = position;
        showCurrentPhoto();
        binding.viewerPages.smoothScrollToPosition(position);
    }

    private void updateCurrentPhoto() {
        if (binding == null) return;
        RecyclerView.LayoutManager layout = binding.viewerPages.getLayoutManager();
        View snapped = layout == null ? null : pager.findSnapView(layout);
        if (snapped == null) return;
        int position = ((LinearLayoutManager) layout).getPosition(snapped);
        if (position == RecyclerView.NO_POSITION || position == currentPhoto) return;
        currentPhoto = position;
        showCurrentPhoto();
    }

    private void showCurrentPhoto() {
        binding.viewerCounter.setText(getString(R.string.memory_detail_photo_counter, currentPhoto + 1, photoCount));
        binding.viewerPrevious.setVisibility(currentPhoto > 0 ? View.VISIBLE : View.GONE);
        binding.viewerNext.setVisibility(currentPhoto < photoCount - 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < binding.viewerDots.getChildCount(); i++) {
            ((ImageView) binding.viewerDots.getChildAt(i)).setImageResource(
                    i == currentPhoto ? R.drawable.memory_viewer_dot_active : R.drawable.memory_dot_inactive);
        }
    }

    /** Photos fill the screen: the status and navigation bars come back with a swipe from the edge. */
    private void setSystemBarsHidden(boolean hidden) {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                requireActivity().getWindow(), requireActivity().getWindow().getDecorView());
        if (hidden) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void close() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        // A fast double tap delivers a second click after the first pop; it is ignored.
        if (current != null && current.getId() == R.id.memoryPhotoViewerFragment) navController.popBackStack();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (photoCount > 0) {
            setSystemBarsHidden(false);
            pager.attachToRecyclerView(null);
        }
        binding = null;
    }
}
