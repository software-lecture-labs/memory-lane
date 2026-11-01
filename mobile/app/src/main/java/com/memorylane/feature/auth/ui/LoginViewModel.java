package com.memorylane.feature.auth.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.regex.Pattern;

public final class LoginViewModel extends ViewModel {
    // Basic address syntax only; account verification belongs to the server.
    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9+._%\\-]{1,256}@[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}"
                    + "(?:\\.[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25})+");

    private final MutableLiveData<LoginUiState> state = new MutableLiveData<>(
            new LoginUiState(LoginUiState.EmailError.NONE, false, LoginUiState.Focus.NONE));

    public LiveData<LoginUiState> getState() { return state; }

    public void onLoginClicked(String email, String password) {
        String address = email.trim();
        LoginUiState.EmailError emailError = address.isEmpty()
                ? LoginUiState.EmailError.REQUIRED
                : EMAIL.matcher(address).matches()
                        ? LoginUiState.EmailError.NONE : LoginUiState.EmailError.INVALID;
        boolean passwordRequired = password.isEmpty();
        LoginUiState.Focus focus = emailError != LoginUiState.EmailError.NONE
                ? LoginUiState.Focus.EMAIL
                : passwordRequired ? LoginUiState.Focus.PASSWORD : LoginUiState.Focus.NONE;
        state.setValue(new LoginUiState(emailError, passwordRequired, focus));
        // Valid input stays on this screen until API integration is implemented.
    }

    public void onEmailChanged() {
        LoginUiState current = state.getValue();
        state.setValue(new LoginUiState(LoginUiState.EmailError.NONE,
                current.isPasswordRequired(), LoginUiState.Focus.NONE));
    }

    public void onPasswordChanged() {
        LoginUiState current = state.getValue();
        state.setValue(new LoginUiState(current.getEmailError(), false, LoginUiState.Focus.NONE));
    }
}
