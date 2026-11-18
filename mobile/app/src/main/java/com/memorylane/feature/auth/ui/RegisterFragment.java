package com.memorylane.feature.auth.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.NavDestination;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.ui.ErrorMessages;
import com.memorylane.databinding.FragmentRegisterBinding;

import java.util.function.Consumer;

/**
 * Account creation. Registration does not sign the user in (see docs/api-contract.md), so on
 * success this screen returns to the screen that opened it, which is the login screen.
 */
public final class RegisterFragment extends Fragment {
    private FragmentRegisterBinding binding;
    private RegisterViewModel viewModel;
    // Changing the transformation method rewrites the text; that is not a user edit.
    private boolean togglingVisibility;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentRegisterBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(RegisterViewModel.class);
        ViewCompat.setAccessibilityHeading(binding.registerHeading, true);

        binding.passwordLayout.setEndIconOnClickListener(
                icon -> togglePassword(binding.passwordLayout, binding.passwordInput));
        binding.confirmLayout.setEndIconOnClickListener(
                icon -> togglePassword(binding.confirmLayout, binding.confirmInput));
        binding.registerButton.setOnClickListener(button -> submit());
        binding.confirmInput.setOnEditorActionListener((input, actionId, event) -> {
            boolean enterKey = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId != EditorInfo.IME_ACTION_DONE && !enterKey) return false;
            // A hardware Enter arrives as key down and key up. Submit on key up: submitting on key
            // down moves focus first, and the key up then reaches that field and advances it.
            if (!enterKey || event.getAction() == KeyEvent.ACTION_UP) submit();
            return true;
        });
        binding.backButton.setOnClickListener(button -> goBack());
        binding.loginLink.setOnClickListener(button -> goBack());

        // Restored before the watchers are attached, so restoring is not taken for a user edit
        // that would clear the errors kept in the ViewModel.
        restore(binding.emailInput, viewModel.email());
        restore(binding.passwordInput, viewModel.password());
        restore(binding.confirmInput, viewModel.confirm());
        onEdit(binding.emailInput, viewModel::onEmailChanged);
        onEdit(binding.passwordInput, viewModel::onPasswordChanged);
        onEdit(binding.confirmInput, viewModel::onConfirmChanged);

        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        viewModel.busy().observe(getViewLifecycleOwner(), this::renderBusy);
        viewModel.registered().observe(getViewLifecycleOwner(), registered -> {
            if (!Boolean.TRUE.equals(registered)) return;
            viewModel.consumeRegistered();
            onRegistered();
        });
    }

    private void submit() {
        viewModel.register(AppContainer.from(requireContext()).getAuthRepository(),
                text(binding.emailInput), text(binding.passwordInput), text(binding.confirmInput));
    }

    private void onRegistered() {
        binding.passwordInput.setText("");
        binding.confirmInput.setText("");
        // Attached to the activity's content view so the message stays visible after leaving this screen.
        Snackbar.make(requireActivity().findViewById(android.R.id.content),
                R.string.register_success, Snackbar.LENGTH_LONG).show();
        goBack();
    }

    private void goBack() {
        NavController navController = NavHostFragment.findNavController(this);
        NavDestination current = navController.getCurrentDestination();
        // A fast double tap delivers a second click after the first pop; popping again would
        // also close the login screen underneath.
        if (current != null && current.getId() == R.id.registerFragment) {
            navController.popBackStack();
        }
    }

    private void renderBusy(boolean busy) {
        binding.registerProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.registerButton.setEnabled(!busy);
        binding.emailInput.setEnabled(!busy);
        binding.passwordInput.setEnabled(!busy);
        binding.confirmInput.setEnabled(!busy);
        binding.backButton.setEnabled(!busy);
        binding.loginLink.setEnabled(!busy);
    }

    private void render(RegisterUiState state) {
        binding.emailLayout.setError(message(emailMessage(state.getEmailError())));
        binding.passwordLayout.setError(message(passwordMessage(state.getPasswordError())));
        binding.confirmLayout.setError(message(confirmMessage(state.getConfirmError())));
        ApiError serverError = state.getServerError();
        binding.registerError.setText(serverError == null
                ? null : ErrorMessages.describe(requireContext(), serverError));
        binding.registerError.setVisibility(serverError == null ? View.GONE : View.VISIBLE);
        switch (state.getFocus()) {
            case EMAIL:
                binding.emailInput.requestFocus();
                break;
            case PASSWORD:
                binding.passwordInput.requestFocus();
                break;
            case CONFIRM:
                binding.confirmInput.requestFocus();
                break;
            default:
                break;
        }
    }

    @Nullable
    private String message(@StringRes int text) {
        return text == 0 ? null : getString(text);
    }

    @StringRes
    private static int emailMessage(RegisterUiState.EmailError error) {
        switch (error) {
            case REQUIRED: return R.string.login_email_required;
            case INVALID: return R.string.login_email_invalid;
            case IN_USE: return R.string.register_email_in_use;
            default: return 0;
        }
    }

    @StringRes
    private static int passwordMessage(RegisterUiState.PasswordError error) {
        switch (error) {
            case REQUIRED: return R.string.register_password_required;
            case TOO_SHORT: return R.string.register_password_too_short;
            case TOO_LONG: return R.string.register_password_too_long;
            default: return 0;
        }
    }

    @StringRes
    private static int confirmMessage(RegisterUiState.ConfirmError error) {
        switch (error) {
            case REQUIRED: return R.string.register_confirm_required;
            case MISMATCH: return R.string.register_confirm_mismatch;
            default: return 0;
        }
    }

    // Keeps the cursor in place and updates the icon's spoken label, as on the login screen.
    private void togglePassword(TextInputLayout layout, TextInputEditText input) {
        int start = input.getSelectionStart();
        int end = input.getSelectionEnd();
        boolean show = input.getTransformationMethod() instanceof PasswordTransformationMethod;
        togglingVisibility = true;
        try {
            input.setTransformationMethod(show
                    ? HideReturnsTransformationMethod.getInstance()
                    : PasswordTransformationMethod.getInstance());
        } finally {
            togglingVisibility = false;
        }
        input.setSelection(Math.max(0, start), Math.max(0, end));
        layout.setEndIconContentDescription(show
                ? R.string.login_hide_password : R.string.login_show_password);
        layout.setEndIconActivated(show);
    }

    private void onEdit(EditText input, Consumer<String> onChanged) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (togglingVisibility) return;
                onChanged.accept(s.toString());
            }
            @Override public void afterTextChanged(Editable value) { }
        });
    }

    private static void restore(EditText input, String value) {
        input.setText(value);
        input.setSelection(value.length());
    }

    private static String text(EditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
