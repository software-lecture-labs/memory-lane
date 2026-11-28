package com.memorylane.feature.memories.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.EditText;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.SavedStateHandle;
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
import com.google.android.material.snackbar.Snackbar;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.databinding.FragmentMemoryCreateBinding;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.photos.ui.PhotoSection;
import com.memorylane.feature.photos.ui.PhotoSelectionViewModel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * New memory with its photos. The memory is saved first; the picked photos are then uploaded
 * to it one by one while this screen stays open (the upload queue lives in this screen's
 * {@link PhotoSelectionViewModel}, so leaving would cancel it). Once nothing is left to show it
 * hands {@link #RESULT_CREATED} to the memory list's back stack entry and returns there; the
 * list then refreshes. A 401 from the save or a photo upload clears the session and returns.
 */
public final class MemoryCreateFragment extends Fragment {
    /** SavedStateHandle key on the memory list entry: true after a memory was created. */
    public static final String RESULT_CREATED = "memory_created";

    private static final String DATE_PICKER = "memory_date_picker";
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private FragmentMemoryCreateBinding binding;
    private MemoryCreateViewModel viewModel;
    private PhotoSelectionViewModel photoModel;
    private PhotoSection photoSection;
    private AppContainer app;
    private DateTimeFormatter dateFormat;
    // Registered before the fragment is created, as the Activity Result API requires; results
    // arrive after onViewCreated, so photoModel is set by then.
    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker =
            PhotoSection.registerPicker(this, uris -> photoModel.pick(uris));
    /** Once the memory exists, back must not drop the photo upload or skip the list result. */
    private final OnBackPressedCallback afterSaveBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (afterSave() == MemoryCreateViewModel.AfterSave.UPLOADING) {
                Snackbar.make(binding.getRoot(), R.string.memory_create_photos_wait, Snackbar.LENGTH_SHORT).show();
            } else {
                returnToList();
            }
        }
    };

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentMemoryCreateBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        app = AppContainer.from(requireContext());
        viewModel = new ViewModelProvider(this, MemoryCreateViewModel.factory(app.getMemoryRepository()))
                .get(MemoryCreateViewModel.class);
        photoModel = new ViewModelProvider(this,
                PhotoSelectionViewModel.factory(app.getPhotoUploads(), app.getPhotoPreparer()))
                .get(PhotoSelectionViewModel.class);
        photoSection = new PhotoSection(requireContext(), binding.createPhotos, photoModel, photoPicker);
        dateFormat = DateTimeFormatter.ofPattern(getString(R.string.memory_list_date_pattern), TURKISH);
        ViewCompat.setAccessibilityHeading(binding.createHeading, true);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), afterSaveBack);

        // Before the save the close button simply leaves; afterwards it is only enabled when
        // the upload is over, and the list must still learn about the new memory.
        binding.createClose.setOnClickListener(button -> {
            if (viewModel.createdMemoryId() == null) goBack();
            else returnToList();
        });
        binding.createDateCard.setOnClickListener(card -> openDatePicker());
        binding.createToday.setOnClickListener(button -> viewModel.onTodaySelected());
        binding.createSave.setOnClickListener(button -> viewModel.save(app.authorization()));
        binding.createLocationPick.setOnClickListener(button -> openLocationPicker());

        // Restored before the watchers are attached, so restoring is not taken for a user edit.
        restore(binding.createTitleInput, viewModel.title());
        restore(binding.createDescriptionInput, viewModel.description());
        restore(binding.createLatitudeInput, viewModel.latitude());
        restore(binding.createLongitudeInput, viewModel.longitude());
        onEdit(binding.createTitleInput, viewModel::onTitleChanged);
        onEdit(binding.createDescriptionInput, viewModel::onDescriptionChanged);
        onEdit(binding.createLatitudeInput, viewModel::onLatitudeChanged);
        onEdit(binding.createLongitudeInput, viewModel::onLongitudeChanged);
        // After the watchers: a point from the map picker is written as if it had been typed.
        observePickedLocation();
        // Chips are rebuilt from the ViewModel's choice on every new view, rotation included.
        addChips(binding.createCategoryGroup, MemoryLabels.CATEGORIES, viewModel.category(),
                viewModel::onCategorySelected);
        addChips(binding.createEmotionGroup, MemoryLabels.EMOTIONS, viewModel.emotion(),
                viewModel::onEmotionSelected);
        // A picker left open across rotation lost its listener with the old view.
        Fragment openPicker = getChildFragmentManager().findFragmentByTag(DATE_PICKER);
        if (openPicker instanceof MaterialDatePicker) {
            listenTo(castPicker(openPicker));
        }

        viewModel.getState().observe(getViewLifecycleOwner(), state -> {
            render(state);
            focus(state);
        });
        photoModel.changes().observe(getViewLifecycleOwner(), ignored -> onPhotosChanged());
        viewModel.saved().observe(getViewLifecycleOwner(), saved -> {
            if (!Boolean.TRUE.equals(saved)) return;
            viewModel.consumeSaved();
            String memoryId = viewModel.createdMemoryId();
            String authorization = app.authorization();
            // A memory saved without photos needs nothing here and returns at once.
            if (memoryId != null && authorization != null) photoModel.startUpload(authorization, memoryId);
            onPhotosChanged();
        });
        viewModel.sessionExpired().observe(getViewLifecycleOwner(), expired -> {
            if (!Boolean.TRUE.equals(expired)) return;
            viewModel.consumeSessionExpired();
            endExpiredSession();
        });
    }

    private void onPhotosChanged() {
        if (photoModel.queue().sessionExpired()) {
            // Same handling as an expired session on the memory save. The queue is left as it
            // is: leaving clears the photo view model, which cancels it and deletes the copies.
            endExpiredSession();
            return;
        }
        render(viewModel.getState().getValue());
        // While the saved event is still pending (e.g. a recreated view observes this first)
        // the photos have not been handed to the upload yet, so nothing is finished.
        boolean handedOver = !Boolean.TRUE.equals(viewModel.saved().getValue());
        if (handedOver && afterSave() == MemoryCreateViewModel.AfterSave.DONE) returnToList();
    }

    private MemoryCreateViewModel.AfterSave afterSave() {
        PhotoSelectionViewModel.Summary summary = photoModel.summary();
        return MemoryCreateViewModel.afterSave(viewModel.createdMemoryId(), photoModel.busy(),
                summary == null ? 0 : summary.rejected());
    }

    private void endExpiredSession() {
        // A later photo update may report the same expiry before the pop has finished.
        NavDestination current = NavHostFragment.findNavController(this).getCurrentDestination();
        if (current == null || current.getId() != R.id.memoryCreateFragment) return;
        app.clearSession();
        // Attached to the activity so the message stays visible on the list screen.
        Snackbar.make(requireActivity().findViewById(android.R.id.content),
                R.string.memory_list_session_expired, Snackbar.LENGTH_LONG).show();
        goBack();
    }

    /** Opens the map picker (ML-15) on the typed location when it is a valid one. */
    private void openLocationPicker() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        if (current == null || current.getId() != R.id.memoryCreateFragment) return;
        navController.navigate(R.id.action_memoryCreateFragment_to_memoryLocationPickerFragment,
                MemoryLocationPickerFragment.arguments(
                        MemoryFormRules.location(viewModel.latitude(), viewModel.longitude())));
    }

    /**
     * The picker leaves {@link PickedLocation#RESULT} on this entry. It fills the coordinate
     * fields, so the form's own location check and draft apply to it unchanged; "Konumu kaldır"
     * empties them.
     */
    private void observePickedLocation() {
        SavedStateHandle results;
        try {
            results = NavHostFragment.findNavController(this)
                    .getBackStackEntry(R.id.memoryCreateFragment).getSavedStateHandle();
        } catch (IllegalArgumentException replacedByLogin) {
            return;
        }
        results.<double[]>getLiveData(PickedLocation.RESULT).observe(getViewLifecycleOwner(), picked -> {
            if (picked == null) return;
            results.set(PickedLocation.RESULT, null);
            MemoryApi.Location location = PickedLocation.read(picked);
            binding.createLatitudeInput.setText(location == null ? "" : PickedLocation.text(location.latitude()));
            binding.createLongitudeInput.setText(location == null ? "" : PickedLocation.text(location.longitude()));
        });
    }

    /** Leaves after the memory was created, telling the list to refresh once. */
    private void returnToList() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        if (current == null || current.getId() != R.id.memoryCreateFragment) return;
        NavBackStackEntry list = navController.getPreviousBackStackEntry();
        if (list != null) list.getSavedStateHandle().set(RESULT_CREATED, true);
        goBack();
    }

    private void render(MemoryCreateUiState state) {
        MemoryCreateViewModel.AfterSave step = afterSave();
        boolean saved = step != MemoryCreateViewModel.AfterSave.NOT_SAVED;
        boolean uploading = step == MemoryCreateViewModel.AfterSave.UPLOADING;
        // The form is locked while saving and for good once the memory exists.
        boolean locked = state.isSaving() || saved;
        afterSaveBack.setEnabled(saved);
        binding.createTitleLayout.setError(message(titleMessage(state.getTitleError())));
        binding.createDescriptionLayout.setError(message(descriptionMessage(state.getDescriptionError())));
        String dateError = message(dateMessage(state.getDateError()));
        binding.createDateError.setText(dateError);
        binding.createDateError.setVisibility(dateError == null ? View.GONE : View.VISIBLE);
        String locationError = message(locationMessage(state.getLocationError()));
        binding.createLocationError.setText(locationError);
        binding.createLocationError.setVisibility(locationError == null ? View.GONE : View.VISIBLE);
        binding.createDateValue.setText(state.getDate() == null
                ? getString(R.string.memory_create_date_empty) : dateFormat.format(state.getDate()));

        binding.createError.setText(state.getServerError() == null
                ? null : ErrorMessages.describe(requireContext(), state.getServerError()));
        binding.createError.setVisibility(state.getServerError() == null ? View.GONE : View.VISIBLE);

        binding.createProgress.setVisibility(state.isSaving() || uploading ? View.VISIBLE : View.GONE);
        // Saving waits until picked photos are copied, so none is left out of this memory.
        binding.createSave.setEnabled(!locked && !photoModel.preparing());
        binding.createTitleInput.setEnabled(!locked);
        binding.createDescriptionInput.setEnabled(!locked);
        binding.createDateCard.setEnabled(!locked);
        binding.createToday.setEnabled(!locked);
        binding.createLatitudeInput.setEnabled(!locked);
        binding.createLongitudeInput.setEnabled(!locked);
        binding.createLocationPick.setEnabled(!locked);
        setChipsEnabled(binding.createCategoryGroup, !locked);
        setChipsEnabled(binding.createEmotionGroup, !locked);
        binding.createClose.setEnabled(!state.isSaving() && !uploading);
        // A photo picked during the save would miss the upload, and PhotoSection frees the
        // picker for "the next memory" once an upload ends; on this screen there is no next
        // memory, so picking stays closed from the save on.
        photoSection.render();
        if (locked) binding.createPhotos.photoAdd.setEnabled(false);
    }

    /** Only on a new form state: a photo update must not pull focus back to a field. */
    private void focus(MemoryCreateUiState state) {
        switch (state.getFocus()) {
            case TITLE:
                binding.createTitleInput.requestFocus();
                break;
            case DATE:
                binding.createDateCard.requestFocus();
                break;
            case DESCRIPTION:
                binding.createDescriptionInput.requestFocus();
                break;
            case LOCATION:
                (viewModel.latitude().trim().isEmpty() && !viewModel.longitude().trim().isEmpty()
                        ? binding.createLatitudeInput
                        : viewModel.longitude().trim().isEmpty() && !viewModel.latitude().trim().isEmpty()
                                ? binding.createLongitudeInput : binding.createLatitudeInput).requestFocus();
                break;
            default:
                break;
        }
    }

    private void openDatePicker() {
        // A fast double tap must not stack two pickers.
        if (getChildFragmentManager().findFragmentByTag(DATE_PICKER) != null) return;
        LocalDate shown = viewModel.getState().getValue() != null
                && viewModel.getState().getValue().getDate() != null
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
        picker.show(getChildFragmentManager(), DATE_PICKER);
    }

    /**
     * One chip per choice, tagged with its code. Selection is reported as the chosen code, or
     * null when nothing is chosen (only possible for the optional emotion).
     */
    private void addChips(ChipGroup group, List<MemoryLabels.Choice> choices, @Nullable String selected,
                          Consumer<String> onSelected) {
        group.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(group.getContext());
        for (MemoryLabels.Choice choice : choices) {
            Chip chip = (Chip) inflater.inflate(R.layout.item_choice_chip, group, false);
            chip.setId(View.generateViewId());
            chip.setTag(choice.code());
            chip.setText(choice.label());
            chip.setChecked(choice.code().equals(selected));
            group.addView(chip, new ChipGroup.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        group.setOnCheckedStateChangeListener((chips, checkedIds) -> {
            Chip checked = checkedIds.isEmpty() ? null : chips.findViewById(checkedIds.get(0));
            onSelected.accept(checked == null ? null : (String) checked.getTag());
        });
    }

    private static void setChipsEnabled(ChipGroup group, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setEnabled(enabled);
        }
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

    private void goBack() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        // A fast double tap delivers a second click after the first pop; popping again would
        // also close the memory list underneath.
        if (current != null && current.getId() == R.id.memoryCreateFragment) {
            navController.popBackStack();
        }
    }

    @Nullable
    private String message(@StringRes int text) {
        return text == 0 ? null : getString(text);
    }

    @StringRes
    private static int titleMessage(MemoryCreateUiState.TitleError error) {
        switch (error) {
            case REQUIRED: return R.string.memory_create_title_required;
            case TOO_LONG: return R.string.memory_create_title_too_long;
            default: return 0;
        }
    }

    @StringRes
    private static int descriptionMessage(MemoryCreateUiState.DescriptionError error) {
        return error == MemoryCreateUiState.DescriptionError.TOO_LONG
                ? R.string.memory_create_description_too_long : 0;
    }

    @StringRes
    private static int dateMessage(MemoryCreateUiState.DateError error) {
        switch (error) {
            case REQUIRED: return R.string.memory_create_date_required;
            case FUTURE: return R.string.memory_create_date_future;
            default: return 0;
        }
    }

    @StringRes
    private static int locationMessage(MemoryCreateUiState.LocationError error) {
        switch (error) {
            case INCOMPLETE: return R.string.memory_location_incomplete;
            case INVALID: return R.string.memory_location_invalid;
            default: return 0;
        }
    }

    private static void restore(EditText input, String value) {
        input.setText(value);
        input.setSelection(value.length());
    }

    private static void onEdit(EditText input, Consumer<String> onChanged) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                onChanged.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable value) { }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
