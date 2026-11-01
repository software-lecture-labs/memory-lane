package com.memorylane.feature.auth.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import org.junit.Rule;
import org.junit.Test;

public class LoginViewModelTest {
    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final LoginViewModel viewModel = new LoginViewModel();

    @Test
    public void initialStateHasNoErrorsOrFocusRequest() {
        assertNoErrors();
    }

    @Test
    public void emptyOrBlankEmailIsRequired() {
        for (String email : new String[] {"", "   "}) {
            viewModel.onLoginClicked(email, "secret");
            assertEquals(LoginUiState.EmailError.REQUIRED, state().getEmailError());
            assertFalse(state().isPasswordRequired());
            assertEquals(LoginUiState.Focus.EMAIL, state().getFocus());
        }
    }

    @Test
    public void malformedEmailIsInvalid() {
        for (String email : new String[] {"user", "user@", "@example.com", "user@example",
                "user name@example.com", "user@@example.com", "user@example..com"}) {
            viewModel.onLoginClicked(email, "secret");
            assertEquals(email, LoginUiState.EmailError.INVALID, state().getEmailError());
            assertEquals(LoginUiState.Focus.EMAIL, state().getFocus());
        }
    }

    @Test
    public void emptyPasswordIsRequiredAndFocused() {
        viewModel.onLoginClicked("user@example.com", "");
        assertEquals(LoginUiState.EmailError.NONE, state().getEmailError());
        assertTrue(state().isPasswordRequired());
        assertEquals(LoginUiState.Focus.PASSWORD, state().getFocus());
    }

    @Test
    public void bothEmptyFieldsShowErrorsAndFocusEmailFirst() {
        viewModel.onLoginClicked("", "");
        assertEquals(LoginUiState.EmailError.REQUIRED, state().getEmailError());
        assertTrue(state().isPasswordRequired());
        assertEquals(LoginUiState.Focus.EMAIL, state().getFocus());
    }

    @Test
    public void validFieldsClearPreviousErrors() {
        viewModel.onLoginClicked("", "");
        viewModel.onLoginClicked("  user+tag@mail.example.test  ", "secret");
        assertNoErrors();
    }

    @Test
    public void whitespacePasswordIsNotTrimmedOrRejected() {
        viewModel.onLoginClicked("user@example.com", "   ");
        assertNoErrors();
    }

    @Test
    public void editingEmailClearsOnlyEmailErrorWithoutMovingFocus() {
        viewModel.onLoginClicked("invalid", "");
        viewModel.onEmailChanged();
        assertEquals(LoginUiState.EmailError.NONE, state().getEmailError());
        assertTrue(state().isPasswordRequired());
        assertEquals(LoginUiState.Focus.NONE, state().getFocus());
    }

    @Test
    public void editingPasswordClearsOnlyPasswordErrorWithoutMovingFocus() {
        viewModel.onLoginClicked("invalid", "");
        viewModel.onPasswordChanged();
        assertEquals(LoginUiState.EmailError.INVALID, state().getEmailError());
        assertFalse(state().isPasswordRequired());
        assertEquals(LoginUiState.Focus.NONE, state().getFocus());
    }

    private LoginUiState state() { return viewModel.getState().getValue(); }

    private void assertNoErrors() {
        assertEquals(LoginUiState.EmailError.NONE, state().getEmailError());
        assertFalse(state().isPasswordRequired());
        assertEquals(LoginUiState.Focus.NONE, state().getFocus());
    }
}
