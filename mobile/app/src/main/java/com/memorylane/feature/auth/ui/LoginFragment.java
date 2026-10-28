package com.memorylane.feature.auth.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;

import com.memorylane.R;
import com.memorylane.databinding.FragmentLoginBinding;

public final class LoginFragment extends Fragment {
    private FragmentLoginBinding binding;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewCompat.setAccessibilityHeading(binding.loginHeading, true);
        binding.passwordLayout.setEndIconOnClickListener(icon -> togglePassword());
        binding.loginButton.setOnClickListener(button -> validate());
        clearErrorOnEdit(binding.emailInput, () -> binding.emailLayout.setError(null));
        clearErrorOnEdit(binding.passwordInput, () -> binding.passwordLayout.setError(null));
    }

    private void togglePassword() {
        int start = binding.passwordInput.getSelectionStart();
        int end = binding.passwordInput.getSelectionEnd();
        boolean show = binding.passwordInput.getTransformationMethod()
                instanceof PasswordTransformationMethod;
        binding.passwordInput.setTransformationMethod(show
                ? HideReturnsTransformationMethod.getInstance()
                : PasswordTransformationMethod.getInstance());
        binding.passwordInput.setSelection(Math.max(0, start), Math.max(0, end));
        binding.passwordLayout.setEndIconContentDescription(show
                ? R.string.login_hide_password : R.string.login_show_password);
        binding.passwordLayout.setEndIconActivated(show);
    }

    private void validate() {
        String email = binding.emailInput.getText() == null ? ""
                : binding.emailInput.getText().toString().trim();
        boolean emailValid = !email.isEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches();
        boolean passwordValid = binding.passwordInput.length() > 0;
        binding.emailLayout.setError(emailValid ? null : getString(email.isEmpty()
                ? R.string.login_email_required : R.string.login_email_invalid));
        binding.passwordLayout.setError(passwordValid ? null : getString(R.string.login_password_required));
        if (!emailValid) {
            binding.emailInput.requestFocus();
        } else if (!passwordValid) {
            binding.passwordInput.requestFocus();
        }
        // Valid input stays on this screen until API integration is implemented.
    }

    private void clearErrorOnEdit(android.widget.EditText input, Runnable clearError) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearError.run();
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
