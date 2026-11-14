package com.memorylane.feature.auth.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.R;
import com.memorylane.core.di.AppContainer;
import com.memorylane.core.ui.ErrorMessages;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import com.memorylane.databinding.FragmentLoginBinding;

public final class LoginFragment extends Fragment {
    private FragmentLoginBinding binding;
    private LoginViewModel viewModel;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(LoginViewModel.class);
        ViewCompat.setAccessibilityHeading(binding.loginHeading, true);
        binding.passwordLayout.setEndIconOnClickListener(icon -> togglePassword());
        binding.loginButton.setOnClickListener(button -> submit());
        onEdit(binding.emailInput, viewModel::onEmailChanged);
        onEdit(binding.passwordInput, viewModel::onPasswordChanged);
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        viewModel.busy().observe(getViewLifecycleOwner(), busy -> {
            binding.loginButton.setEnabled(!busy);
            binding.emailInput.setEnabled(!busy);
            binding.passwordInput.setEnabled(!busy);
        });
        viewModel.result().observe(getViewLifecycleOwner(), result -> {
            if (result == null) return;
            viewModel.consumeResult();
            if (!result.isSuccess()) {
                binding.passwordLayout.setError(ErrorMessages.describe(requireContext(), result.getError()));
                return;
            }
            AppContainer.from(requireContext()).startSession(result.getData().getAccessToken());
            binding.passwordInput.setText("");
            NavHostFragment.findNavController(this).navigate(R.id.memoriesFragment, null,
                    new NavOptions.Builder().setPopUpTo(R.id.memoriesFragment, true).build());
        });
    }

    private void submit() {
        String email = binding.emailInput.getText() == null ? "" : binding.emailInput.getText().toString();
        String password = binding.passwordInput.getText() == null ? "" : binding.passwordInput.getText().toString();
        viewModel.login(AppContainer.from(requireContext()).getAuthRepository(), email, password);
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

    private void render(LoginUiState state) {
        binding.emailLayout.setError(state.getEmailError() == LoginUiState.EmailError.NONE
                ? null : getString(state.getEmailError() == LoginUiState.EmailError.REQUIRED
                        ? R.string.login_email_required : R.string.login_email_invalid));
        binding.passwordLayout.setError(state.isPasswordRequired()
                ? getString(R.string.login_password_required) : null);
        if (state.getFocus() == LoginUiState.Focus.EMAIL) {
            binding.emailInput.requestFocus();
        } else if (state.getFocus() == LoginUiState.Focus.PASSWORD) {
            binding.passwordInput.requestFocus();
        }
    }

    private void onEdit(android.widget.EditText input, Runnable onChanged) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                onChanged.run();
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
