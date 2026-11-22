package com.memorylane.feature.auth.ui;

import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;

/** Validation and server error state; contains no credentials. */
public final class RegisterUiState {
    public enum EmailError { NONE, REQUIRED, INVALID, IN_USE }
    public enum PasswordError { NONE, REQUIRED, TOO_SHORT, TOO_LONG }
    public enum ConfirmError { NONE, REQUIRED, MISMATCH }
    public enum Focus { NONE, EMAIL, PASSWORD, CONFIRM }

    private final EmailError emailError;
    private final PasswordError passwordError;
    private final ConfirmError confirmError;
    private final Focus focus;
    @Nullable private final ApiError serverError;

    public RegisterUiState(EmailError emailError, PasswordError passwordError,
                           ConfirmError confirmError, Focus focus, @Nullable ApiError serverError) {
        this.emailError = emailError;
        this.passwordError = passwordError;
        this.confirmError = confirmError;
        this.focus = focus;
        this.serverError = serverError;
    }

    public EmailError getEmailError() { return emailError; }
    public PasswordError getPasswordError() { return passwordError; }
    public ConfirmError getConfirmError() { return confirmError; }
    public Focus getFocus() { return focus; }

    /** A failed request that belongs to no single field (network, 429, 503); shown above the button. */
    @Nullable
    public ApiError getServerError() { return serverError; }
}
