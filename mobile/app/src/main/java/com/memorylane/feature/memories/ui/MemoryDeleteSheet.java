package com.memorylane.feature.memories.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.databinding.SheetMemoryDeleteBinding;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

/**
 * Delete confirmation panel (ML-13): shows what will be deleted and deletes only after "Anıyı Sil".
 * While the request runs the panel cannot be dismissed. On success it hands
 * {@link #RESULT_DELETED} to the fragment that opened it and closes.
 */
public class MemoryDeleteSheet extends BottomSheetDialogFragment {
    /** Fragment result key, delivered to the opening fragment's child fragment manager. */
    static final String RESULT_DELETED = "memory_deleted";
    static final String TAG = "memory_delete";

    private static final String ARG_MEMORY_ID = "memoryId";
    private static final String ARG_TITLE = "title";
    private static final String ARG_DATE = "memoryDate";
    private static final String ARG_PHOTO_COUNT = "photoCount";
    private static final String ARG_COVER = "coverPhotoId";
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private SheetMemoryDeleteBinding binding;
    private AppContainer app;
    private MemoryDeleteViewModel viewModel;

    /** @param memoryDate the memory's {@code memoryDate}, already checked to be YYYY-MM-DD */
    static MemoryDeleteSheet forMemory(@NonNull String memoryId, @NonNull String title, @NonNull String memoryDate,
                                       int photoCount, @Nullable String coverPhotoId) {
        Bundle arguments = new Bundle();
        arguments.putString(ARG_MEMORY_ID, Objects.requireNonNull(memoryId, "memoryId"));
        arguments.putString(ARG_TITLE, title);
        arguments.putString(ARG_DATE, memoryDate);
        arguments.putInt(ARG_PHOTO_COUNT, photoCount);
        arguments.putString(ARG_COVER, coverPhotoId);
        MemoryDeleteSheet sheet = new MemoryDeleteSheet();
        sheet.setArguments(arguments);
        return sheet;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = SheetMemoryDeleteBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = AppContainer.from(requireContext());
        Bundle arguments = requireArguments();
        String memoryId = Objects.requireNonNull(arguments.getString(ARG_MEMORY_ID), ARG_MEMORY_ID);
        viewModel = new ViewModelProvider(this, MemoryDeleteViewModel.factory(app.getMemoryRepository(), memoryId))
                .get(MemoryDeleteViewModel.class);

        binding.deleteTitle.setText(arguments.getString(ARG_TITLE));
        binding.deleteDate.setText(DateTimeFormatter.ofPattern(getString(R.string.memory_delete_date_pattern), TURKISH)
                .format(LocalDate.parse(Objects.requireNonNull(arguments.getString(ARG_DATE)))));
        int photos = arguments.getInt(ARG_PHOTO_COUNT);
        binding.deletePhotoCount.setVisibility(photos > 0 ? View.VISIBLE : View.GONE);
        binding.deletePhotoCount.setText(getResources().getQuantityString(R.plurals.memory_delete_photo_count, photos, photos));
        showCover(memoryId, arguments.getString(ARG_COVER));

        binding.deleteConfirm.setOnClickListener(button -> viewModel.delete(app.authorization()));
        binding.deleteCancel.setOnClickListener(button -> dismiss());
        viewModel.getStatus().observe(getViewLifecycleOwner(), this::render);
    }

    private void showCover(String memoryId, @Nullable String coverPhotoId) {
        if (coverPhotoId == null) {
            binding.deleteCover.setImageResource(R.drawable.memory_photo_missing);
            return;
        }
        int size = getResources().getDimensionPixelSize(R.dimen.memory_delete_cover);
        app.getPhotoImageLoader().load(binding.deleteCover, memoryId, coverPhotoId, size, size, bitmap -> {
            if (binding == null) return;
            if (bitmap != null) binding.deleteCover.setImageBitmap(bitmap);
            else binding.deleteCover.setImageResource(R.drawable.memory_photo_missing);
        });
    }

    private void render(MemoryDeleteViewModel.Status status) {
        boolean deleting = status == MemoryDeleteViewModel.Status.DELETING;
        setCancelable(!deleting);
        binding.deleteConfirm.setEnabled(!deleting);
        binding.deleteCancel.setEnabled(!deleting);
        binding.deleteConfirm.setText(deleting ? R.string.memory_delete_running
                : status == MemoryDeleteViewModel.Status.FAILED ? R.string.state_retry : R.string.memory_delete_confirm);
        boolean failed = status == MemoryDeleteViewModel.Status.FAILED;
        binding.deleteError.setVisibility(failed ? View.VISIBLE : View.GONE);
        if (failed) {
            ApiError error = viewModel.getError();
            binding.deleteErrorMessage.setText(ErrorMessages.describe(requireContext(),
                    error != null ? error : ApiError.unexpected()));
        }
        if (status == MemoryDeleteViewModel.Status.DELETED) {
            getParentFragmentManager().setFragmentResult(RESULT_DELETED, Bundle.EMPTY);
            dismiss();
        } else if (status == MemoryDeleteViewModel.Status.SIGNED_OUT) {
            // Normally already done by AuthInterceptor; the session layer opens the login screen.
            app.clearSession();
            dismiss();
        }
    }

    @Override
    public void onDestroyView() {
        app.getPhotoImageLoader().cancel(binding.deleteCover);
        binding = null;
        super.onDestroyView();
    }
}
