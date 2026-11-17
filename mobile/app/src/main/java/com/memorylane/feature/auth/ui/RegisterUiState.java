package com.memorylane.feature.auth.ui;

/** Validation state contains no credentials. */
public final class RegisterUiState {
    public enum EmailError { NONE, REQUIRED, INVALID }
    public enum PasswordError { NONE, REQUIRED, TOO_SHORT, TOO_LONG }
    public enum ConfirmError { NONE, REQUIRED, MISMATCH }
    public enum Focus { NONE, EMAIL, PASSWORD, CONFIRM }

    private final EmailError emailError;
    private final PasswordError passwordError;
    private final ConfirmError confirmError;
    private final Focus focus;

    public RegisterUiState(EmailError emailError, PasswordError passwordError,
                           ConfirmError confirmError, Focus focus) {
        this.emailError = emailError;
        this.passwordError = passwordError;
        this.confirmError = confirmError;
        this.focus = focus;
    }

    public EmailError getEmailError() { return emailError; }
    public PasswordError getPasswordError() { return passwordError; }
    public ConfirmError getConfirmError() { return confirmError; }
    public Focus getFocus() { return focus; }
}
