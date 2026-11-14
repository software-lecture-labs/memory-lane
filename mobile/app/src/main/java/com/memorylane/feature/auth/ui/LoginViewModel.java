package com.memorylane.feature.auth.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.regex.Pattern;

import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.feature.auth.data.AuthRepository;
import com.memorylane.feature.auth.data.AuthRequest;
import com.memorylane.feature.auth.data.LoginResponse;

public final class LoginViewModel extends ViewModel {
    // Basic address syntax only; account verification belongs to the server.
    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9+._%\\-]{1,256}@[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}"
                    + "(?:\\.[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25})+");

    private final MutableLiveData<LoginUiState> state = new MutableLiveData<>(
            new LoginUiState(LoginUiState.EmailError.NONE, false, LoginUiState.Focus.NONE));

    private final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    private final MutableLiveData<ApiResult<LoginResponse>> result = new MutableLiveData<>();
    private Cancellable request;

    LiveData<Boolean> busy() { return busy; }

    LiveData<ApiResult<LoginResponse>> result() { return result; }

    void consumeResult() { result.setValue(null); }

    void login(AuthRepository repository, String email, String password) {
        if (Boolean.TRUE.equals(busy.getValue())) return;
        consumeResult();
        onLoginClicked(email, password);
        if (state.getValue().getFocus() != LoginUiState.Focus.NONE) return;
        busy.setValue(true);
        request = repository.login(new AuthRequest(email, password), response -> {
            busy.setValue(false);
            result.setValue(response);
        });
    }

    @Override
    protected void onCleared() {
        if (request != null) request.cancel();
    }

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
