package com.memorylane.feature.auth.ui;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.feature.auth.data.AuthRepository;
import com.memorylane.feature.auth.data.AuthRequest;
import com.memorylane.feature.auth.data.UserResponse;

public final class RegisterViewModel extends ViewModel {
    private static final String EMAIL_IN_USE = "EMAIL_IN_USE";

    private final MutableLiveData<RegisterUiState> state = new MutableLiveData<>(
            new RegisterUiState(RegisterUiState.EmailError.NONE, RegisterUiState.PasswordError.NONE,
                    RegisterUiState.ConfirmError.NONE, RegisterUiState.Focus.NONE, null));

    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> registered = new MutableLiveData<>(false);
    private Cancellable request;

    // Field texts live here so a recreated view gets them back. The inputs do not save their own
    // text (saveEnabled="false"), which keeps the password out of the saved instance state.
    private String email = "";
    private String password = "";
    private String confirm = "";

    LiveData<Boolean> busy() { return busy; }

    /** True until consumed, so a recreated view still sees a success that arrived meanwhile. */
    LiveData<Boolean> registered() { return registered; }

    void consumeRegistered() { registered.setValue(false); }

    String email() { return email; }
    String password() { return password; }
    String confirm() { return confirm; }

    void register(AuthRepository repository, String email, String password, String confirm) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        // A new attempt starts clean: this also drops EMAIL_IN_USE and any earlier server error.
        onRegisterClicked(email, password, confirm);
        if (state.getValue().getFocus() != RegisterUiState.Focus.NONE) return;
        busy.setValue(true);
        // Values are sent unchanged; the server trims and lowercases the address itself.
        request = repository.register(new AuthRequest(email, password), this::onResult);
    }

    private void onResult(ApiResult<UserResponse> response) {
        busy.setValue(false);
        if (response.isSuccess()) {
            registered.setValue(true);
            return;
        }
        ApiError error = response.getError() != null ? response.getError() : ApiError.unexpected();
        RegisterUiState current = state.getValue();
        if (error.getKind() == ApiError.Kind.HTTP && EMAIL_IN_USE.equals(error.getCode())) {
            update(RegisterUiState.EmailError.IN_USE, current.getPasswordError(),
                    current.getConfirmError(), RegisterUiState.Focus.EMAIL, null);
        } else {
            update(current.getEmailError(), current.getPasswordError(), current.getConfirmError(),
                    RegisterUiState.Focus.NONE, error);
        }
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
        update(emailError, passwordError, confirmError, focus, null);
    }

    /** A new address may be free, so this also clears EMAIL_IN_USE. */
    public void onEmailChanged(String email) {
        this.email = email;
        RegisterUiState current = state.getValue();
        update(RegisterUiState.EmailError.NONE, current.getPasswordError(),
                current.getConfirmError(), RegisterUiState.Focus.NONE, null);
    }

    /** Keeps the email error, including EMAIL_IN_USE: the address is still taken. */
    public void onPasswordChanged(String password) {
        this.password = password;
        RegisterUiState current = state.getValue();
        // A mismatch depends on both fields, so editing the password also clears it.
        RegisterUiState.ConfirmError confirmError =
                current.getConfirmError() == RegisterUiState.ConfirmError.MISMATCH
                        ? RegisterUiState.ConfirmError.NONE : current.getConfirmError();
        update(current.getEmailError(), RegisterUiState.PasswordError.NONE, confirmError,
                RegisterUiState.Focus.NONE, null);
    }

    /** Keeps the email error, including EMAIL_IN_USE. */
    public void onConfirmChanged(String confirm) {
        this.confirm = confirm;
        RegisterUiState current = state.getValue();
        update(current.getEmailError(), current.getPasswordError(),
                RegisterUiState.ConfirmError.NONE, RegisterUiState.Focus.NONE, null);
    }

    private void update(RegisterUiState.EmailError emailError,
                        RegisterUiState.PasswordError passwordError,
                        RegisterUiState.ConfirmError confirmError,
                        RegisterUiState.Focus focus, @Nullable ApiError serverError) {
        state.setValue(new RegisterUiState(emailError, passwordError, confirmError, focus, serverError));
    }

    private static RegisterUiState.EmailError emailError(String email) {
        if (AuthInputRules.strip(email).isEmpty()) return RegisterUiState.EmailError.REQUIRED;
        return AuthInputRules.isValidEmail(email)
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
