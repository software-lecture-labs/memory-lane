package com.memorylane.feature.memories.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavBackStackEntry;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.datepicker.CalendarConstraints;
import com.google.android.material.datepicker.DateValidatorPointBackward;
import com.google.android.material.datepicker.MaterialDatePicker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.databinding.FragmentMemoryEditBinding;
import com.memorylane.feature.memories.data.MemoryApi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Memory edit form: draws {@link MemoryEditUiState} in {@link #render} and forwards edits to
 * {@link MemoryEditViewModel}. Leaving with unsaved changes (back button, system back, "İptal")
 * asks first. After a save it leaves {@link #RESULT_UPDATED} on the detail and timeline entries
 * and closes; the detail reads the new version, the timeline reloads when it is shown again.
 */
public class MemoryEditFragment extends Fragment {
    /** Navigation argument: id of the memory to edit (nav_graph.xml, memoryEditFragment). */
    static final String ARG_MEMORY_ID = "memoryId";
    /** Set to true on the previous back stack entry after a successful save. */
    static final String RESULT_UPDATED = "memory_updated";

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final String DATE_PICKER_TAG = "memory_edit_date";

    private FragmentMemoryEditBinding binding;
    private AppContainer app;
    private MemoryEditViewModel viewModel;
    private DateTimeFormatter dateFormat;
    /** True while texts or the category are written from the ViewModel, not typed or tapped. */
    private boolean writingForm;
    private int appliedFormVersion = -1;
    @Nullable private AlertDialog leaveDialog;

    private final OnBackPressedCallback back = new OnBackPressedCallback(true) {
        @Override
        public void handleOnBackPressed() {
            leave();
        }
    };

    /** Arguments for editing {@code memoryId}. */
    static Bundle arguments(@NonNull String memoryId) {
        Bundle arguments = new Bundle();
        arguments.putString(ARG_MEMORY_ID, Objects.requireNonNull(memoryId, "memoryId"));
        return arguments;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMemoryEditBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = AppContainer.from(requireContext());
        String memoryId = Objects.requireNonNull(requireArguments().getString(ARG_MEMORY_ID), ARG_MEMORY_ID);
        viewModel = new ViewModelProvider(this, MemoryEditViewModel.factory(app.getMemoryRepository(), memoryId))
                .get(MemoryEditViewModel.class);
        dateFormat = DateTimeFormatter.ofPattern(getString(R.string.memory_list_date_pattern), TURKISH);
        appliedFormVersion = -1;

        ViewCompat.setAccessibilityHeading(binding.editScreenTitle, true);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), back);
        binding.editBack.setOnClickListener(button -> leave());
        binding.editCancel.setOnClickListener(button -> leave());
        binding.editBackToList.setOnClickListener(button -> backToList());
        binding.editSave.setOnClickListener(button -> viewModel.save());
        binding.editConflictReload.setOnClickListener(button -> viewModel.reloadLatest());
        binding.editUseLatest.setOnClickListener(button -> viewModel.useLatest());
        binding.editKeepDraft.setOnClickListener(button -> viewModel.keepDraft());
        binding.editDate.setOnClickListener(row -> openDatePicker());
        addCategoryChips();
        binding.editCategories.setOnCheckedStateChangeListener((group, checked) -> {
            if (writingForm || checked.isEmpty()) return;
            View chip = group.findViewById(checked.get(0));
            if (chip != null) viewModel.onCategorySelected((String) chip.getTag());
        });
        onEdit(binding.editTitleInput, viewModel::onTitleChanged);
        onEdit(binding.editNoteInput, viewModel::onDescriptionChanged);
        Fragment openPicker = getChildFragmentManager().findFragmentByTag(DATE_PICKER_TAG);
        if (openPicker instanceof MaterialDatePicker) listenTo(castPicker(openPicker));

        viewModel.formVersion().observe(getViewLifecycleOwner(), version -> writeTexts(version));
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        viewModel.saved().observe(getViewLifecycleOwner(), saved -> {
            if (!Boolean.TRUE.equals(saved)) return;
            viewModel.consumeSaved();
            closeAfterSave();
        });
        viewModel.start(app.authorization());
    }

    /** Writes the ViewModel's texts into the inputs when they were replaced (or the view is new). */
    private void writeTexts(@Nullable Integer version) {
        int current = version == null ? 0 : version;
        if (current == appliedFormVersion) return;
        appliedFormVersion = current;
        writingForm = true;
        binding.editTitleInput.setText(viewModel.title());
        binding.editNoteInput.setText(viewModel.description());
        writingForm = false;
    }

    private void render(MemoryEditUiState state) {
        MemoryEditUiState.Status status = state.getStatus();
        boolean ready = status == MemoryEditUiState.Status.READY;
        binding.editForm.setVisibility(ready ? View.VISIBLE : View.GONE);
        binding.editActions.setVisibility(ready ? View.VISIBLE : View.GONE);
        binding.editStatus.setVisibility(ready ? View.GONE : View.VISIBLE);
        binding.editBackToList.setVisibility(status == MemoryEditUiState.Status.NOT_FOUND ? View.VISIBLE : View.GONE);
        switch (status) {
            case SIGNED_OUT:
                // Normally already done by AuthInterceptor; the session layer opens the login screen.
                app.clearSession();
                binding.editState.hide();
                return;
            case LOADING:
                binding.editState.showLoading(getString(R.string.memory_detail_loading));
                return;
            case NOT_FOUND:
                binding.editState.showEmpty(getString(R.string.memory_detail_not_found_title),
                        getString(R.string.memory_detail_not_found_message));
                return;
            case LOAD_ERROR:
                binding.editState.showError(getString(R.string.memory_edit_load_error_title),
                        describe(state.getLoadError()), viewModel::retryLoad);
                return;
            case READY:
            default:
                binding.editState.hide();
                renderForm(state);
        }
    }

    private void renderForm(MemoryEditUiState state) {
        boolean saving = state.isSaving();
        MemoryEditUiState.Conflict conflict = state.getConflict();
        binding.editTitleLayout.setError(message(titleMessage(state.getTitleError())));
        binding.editNoteLayout.setError(message(descriptionMessage(state.getDescriptionError())));
        int dateMessage = dateMessage(state.getDateError());
        binding.editDateError.setText(dateMessage == 0 ? null : getString(dateMessage));
        binding.editDateError.setVisibility(dateMessage == 0 ? View.GONE : View.VISIBLE);
        LocalDate date = state.getDate();
        binding.editDateValue.setText(date == null ? getString(R.string.memory_create_date_empty) : dateFormat.format(date));

        writingForm = true;
        View chosen = binding.editCategories.findViewWithTag(state.getCategory());
        if (chosen != null) binding.editCategories.check(chosen.getId());
        writingForm = false;

        ApiError saveError = state.getSaveError();
        binding.editError.setText(saveError == null ? null : describe(saveError));
        binding.editError.setVisibility(saveError == null ? View.GONE : View.VISIBLE);
        renderConflict(state);

        boolean locked = saving || conflict == MemoryEditUiState.Conflict.MODIFIED
                || conflict == MemoryEditUiState.Conflict.RELOADING;
        binding.editSave.setEnabled(!locked);
        binding.editProgress.setVisibility(saving ? View.VISIBLE : View.GONE);
        binding.editTitleInput.setEnabled(!saving);
        binding.editNoteInput.setEnabled(!saving);
        binding.editDate.setEnabled(!saving);
        for (int i = 0; i < binding.editCategories.getChildCount(); i++) {
            binding.editCategories.getChildAt(i).setEnabled(!saving);
        }

        switch (state.getFocus()) {
            case TITLE:
                binding.editTitleInput.requestFocus();
                break;
            case DATE:
                binding.editDate.requestFocus();
                break;
            case DESCRIPTION:
                binding.editNoteInput.requestFocus();
                break;
            case NONE:
            default:
                break;
        }
    }

    private void renderConflict(MemoryEditUiState state) {
        MemoryEditUiState.Conflict conflict = state.getConflict();
        binding.editConflict.setVisibility(conflict == MemoryEditUiState.Conflict.NONE ? View.GONE : View.VISIBLE);
        boolean review = conflict == MemoryEditUiState.Conflict.REVIEW;
        binding.editConflictMessage.setText(review ? R.string.memory_edit_conflict_review
                : R.string.memory_edit_conflict_message);
        binding.editConflictReload.setVisibility(review ? View.GONE : View.VISIBLE);
        binding.editConflictReload.setEnabled(conflict == MemoryEditUiState.Conflict.MODIFIED);
        MemoryApi.Detail latest = state.getLatest();
        binding.editLatest.setVisibility(review && latest != null ? View.VISIBLE : View.GONE);
        if (review && latest != null) {
            binding.editLatestTitle.setText(latest.title());
            LocalDate day = MemoryDetailViewModel.validDate(latest);
            int category = MemoryLabels.category(latest.category());
            String when = day == null ? "" : dateFormat.format(day);
            binding.editLatestMeta.setText(category == MemoryLabels.NONE ? when
                    : getString(R.string.memory_edit_latest_meta, when, getString(category)));
            String note = latest.description();
            boolean hasNote = note != null && !note.trim().isEmpty();
            binding.editLatestNote.setText(hasNote ? note : null);
            binding.editLatestNote.setVisibility(hasNote ? View.VISIBLE : View.GONE);
        }
    }

    /** Back button, system back and "İptal": ask first when there are unsaved changes. */
    private void leave() {
        if (!viewModel.hasUnsavedChanges()) {
            close();
            return;
        }
        if (leaveDialog != null && leaveDialog.isShowing()) return;
        leaveDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.memory_edit_discard_title)
                .setMessage(R.string.memory_edit_discard_message)
                .setNegativeButton(R.string.memory_edit_discard_leave, (dialog, which) -> close())
                .setPositiveButton(R.string.memory_edit_discard_stay, null)
                .show();
    }

    /** The detail behind reads the new version; the timeline further back reloads its rows. */
    private void closeAfterSave() {
        NavController navController = NavHostFragment.findNavController(this);
        NavBackStackEntry previous = navController.getPreviousBackStackEntry();
        if (previous != null) previous.getSavedStateHandle().set(RESULT_UPDATED, true);
        try {
            navController.getBackStackEntry(R.id.memoriesFragment).getSavedStateHandle().set(RESULT_UPDATED, true);
        } catch (IllegalArgumentException noTimeline) {
            // Not opened from the timeline; nothing else shows this memory.
        }
        close();
    }

    private void close() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        // A fast double tap delivers a second click after the first pop; it is ignored.
        if (current != null && current.getId() == R.id.memoryEditFragment) navController.popBackStack();
    }

    /** NOT_FOUND: the detail behind is gone too, so return straight to the list. */
    private void backToList() {
        NavHostFragment.findNavController(this).popBackStack(R.id.memoriesFragment, false);
    }

    private void openDatePicker() {
        if (getChildFragmentManager().findFragmentByTag(DATE_PICKER_TAG) != null) return;
        LocalDate shown = viewModel.getState().getValue() != null && viewModel.getState().getValue().getDate() != null
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
        picker.show(getChildFragmentManager(), DATE_PICKER_TAG);
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

    private void onEdit(EditText input, Consumer<String> onChanged) {
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                // Only the final text matters.
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                // Only the final text matters.
            }

            @Override
            public void afterTextChanged(Editable text) {
                if (!writingForm) onChanged.accept(text.toString());
            }
        });
    }

    @Nullable
    private String message(@StringRes int id) {
        return id == 0 ? null : getString(id);
    }

    @StringRes
    private static int titleMessage(MemoryCreateUiState.TitleError error) {
        switch (error) {
            case REQUIRED: return R.string.memory_create_title_required;
            case TOO_LONG: return R.string.memory_create_title_too_long;
            case NONE:
            default: return 0;
        }
    }

    @StringRes
    private static int descriptionMessage(MemoryCreateUiState.DescriptionError error) {
        return error == MemoryCreateUiState.DescriptionError.TOO_LONG ? R.string.memory_create_description_too_long : 0;
    }

    @StringRes
    private static int dateMessage(MemoryCreateUiState.DateError error) {
        switch (error) {
            case REQUIRED: return R.string.memory_create_date_required;
            case FUTURE: return R.string.memory_create_date_future;
            case NONE:
            default: return 0;
        }
    }

    private String describe(@Nullable ApiError error) {
        return ErrorMessages.describe(requireContext(), error != null ? error : ApiError.unexpected());
    }

    /**
     * One chip per {@link MemoryLabels#CATEGORIES} entry, tagged with its API code; render checks
     * the chip whose tag is the draft's category.
     */
    private void addCategoryChips() {
        ChipGroup group = binding.editCategories;
        group.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(group.getContext());
        for (MemoryLabels.Choice choice : MemoryLabels.CATEGORIES) {
            Chip chip = (Chip) inflater.inflate(R.layout.item_category_chip, group, false);
            chip.setId(View.generateViewId());
            chip.setTag(choice.code());
            chip.setText(choice.label());
            group.addView(chip);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (leaveDialog != null) leaveDialog.dismiss();
        leaveDialog = null;
        binding = null;
    }
}
