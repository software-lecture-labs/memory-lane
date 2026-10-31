package com.memorylane.feature.auth.ui;

/** Validation state contains no credentials. */
public final class LoginUiState {
    public enum EmailError { NONE, REQUIRED, INVALID }
    public enum Focus { NONE, EMAIL, PASSWORD }

    private final EmailError emailError;
    private final boolean passwordRequired;
    private final Focus focus;

    public LoginUiState(EmailError emailError, boolean passwordRequired, Focus focus) {
        this.emailError = emailError;
        this.passwordRequired = passwordRequired;
        this.focus = focus;
    }

    public EmailError getEmailError() { return emailError; }
    public boolean isPasswordRequired() { return passwordRequired; }
    public Focus getFocus() { return focus; }
}
