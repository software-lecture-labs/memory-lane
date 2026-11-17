package com.memorylane.feature.auth.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.feature.auth.data.AuthRepository;
import com.memorylane.feature.auth.data.AuthRequest;
import com.memorylane.feature.auth.data.UserResponse;

public final class RegisterViewModel extends ViewModel {
    private final MutableLiveData<RegisterUiState> state = new MutableLiveData<>(
            new RegisterUiState(RegisterUiState.EmailError.NONE, RegisterUiState.PasswordError.NONE,
                    RegisterUiState.ConfirmError.NONE, RegisterUiState.Focus.NONE));

    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<ApiResult<UserResponse>> result = new MutableLiveData<>();
    private Cancellable request;

    LiveData<Boolean> busy() { return busy; }

    /** Kept until consumed so a recreated view still receives a result that arrived meanwhile. */
    LiveData<ApiResult<UserResponse>> result() { return result; }

    void consumeResult() { result.setValue(null); }

    void register(AuthRepository repository, String email, String password, String confirm) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        consumeResult();
        onRegisterClicked(email, password, confirm);
        if (state.getValue().getFocus() != RegisterUiState.Focus.NONE) return;
        busy.setValue(true);
        // Values are sent unchanged; the server trims and lowercases the address itself.
        request = repository.register(new AuthRequest(email, password), response -> {
            busy.setValue(false);
            result.setValue(response);
        });
    }

    @Override
    protected void onCleared() {
        if (request != null) request.cancel();
    }

    public LiveData<RegisterUiState> getState() { return state; }

    public void onRegisterClicked(String email, String password, String confirm) {
        RegisterUiState.EmailError emailError = emailError(email);
        RegisterUiState.PasswordError passwordError = passwordError(password);
        RegisterUiState.ConfirmError confirmError = confirm.isEmpty()
                ? RegisterUiState.ConfirmError.REQUIRED
                : confirm.equals(password)
                        ? RegisterUiState.ConfirmError.NONE : RegisterUiState.ConfirmError.MISMATCH;
        RegisterUiState.Focus focus = emailError != RegisterUiState.EmailError.NONE
                ? RegisterUiState.Focus.EMAIL
                : passwordError != RegisterUiState.PasswordError.NONE
                        ? RegisterUiState.Focus.PASSWORD
                        : confirmError != RegisterUiState.ConfirmError.NONE
                                ? RegisterUiState.Focus.CONFIRM : RegisterUiState.Focus.NONE;
        state.setValue(new RegisterUiState(emailError, passwordError, confirmError, focus));
    }

    public void onEmailChanged() {
        RegisterUiState current = state.getValue();
        state.setValue(new RegisterUiState(RegisterUiState.EmailError.NONE,
                current.getPasswordError(), current.getConfirmError(), RegisterUiState.Focus.NONE));
    }

    public void onPasswordChanged() {
        RegisterUiState current = state.getValue();
        // A mismatch depends on both fields, so editing the password also clears it.
        RegisterUiState.ConfirmError confirmError =
                current.getConfirmError() == RegisterUiState.ConfirmError.MISMATCH
                        ? RegisterUiState.ConfirmError.NONE : current.getConfirmError();
        state.setValue(new RegisterUiState(current.getEmailError(),
                RegisterUiState.PasswordError.NONE, confirmError, RegisterUiState.Focus.NONE));
    }

    public void onConfirmChanged() {
        RegisterUiState current = state.getValue();
        state.setValue(new RegisterUiState(current.getEmailError(), current.getPasswordError(),
                RegisterUiState.ConfirmError.NONE, RegisterUiState.Focus.NONE));
    }

    private static RegisterUiState.EmailError emailError(String email) {
        String address = email.trim();
        if (address.isEmpty()) return RegisterUiState.EmailError.REQUIRED;
        return address.length() <= AuthInputRules.EMAIL_MAX_LENGTH
                && AuthInputRules.EMAIL.matcher(address).matches()
                ? RegisterUiState.EmailError.NONE : RegisterUiState.EmailError.INVALID;
    }

    private static RegisterUiState.PasswordError passwordError(String password) {
        if (password.isEmpty()) return RegisterUiState.PasswordError.REQUIRED;
        int length = AuthInputRules.passwordLength(password);
        if (length < AuthInputRules.PASSWORD_MIN_LENGTH) return RegisterUiState.PasswordError.TOO_SHORT;
        if (length > AuthInputRules.PASSWORD_MAX_LENGTH) return RegisterUiState.PasswordError.TOO_LONG;
        return RegisterUiState.PasswordError.NONE;
    }
}
