package com.memorylane.feature.auth.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.feature.auth.data.AuthRepository;
import com.memorylane.feature.auth.data.AuthRequest;
import com.memorylane.feature.auth.data.LoginResponse;
import com.memorylane.feature.auth.data.UserResponse;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;

public class LoginViewModelTest {
    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final LoginViewModel viewModel = new LoginViewModel();

    @Test
    public void pendingLoginRejectsDuplicateAndKeepsResultForNewObserver() {
        var pending = new ArrayList<ResultCallback<
                LoginResponse>>();
        var repository = new AuthRepository() {
            @Override
            public Cancellable login(
                    AuthRequest request,
                    ResultCallback<LoginResponse> callback) {
                pending.add(callback);
                return () -> { };
            }

            @Override
            public Cancellable register(
                    AuthRequest request,
                    ResultCallback<UserResponse> callback) {
                throw new AssertionError("Unexpected registration");
            }
        };
        viewModel.login(repository, "user@example.com", "secret");
        viewModel.login(repository, "user@example.com", "secret");
        assertEquals(1, pending.size());
        assertEquals(Boolean.TRUE, viewModel.busy().getValue());

        var failure = ApiError.network();
        pending.get(0).onResult(ApiResult.failure(failure));
        var delivered = new ArrayList<ApiError>();
        viewModel.result().observeForever(result -> delivered.add(result.getError()));
        assertEquals(List.of(failure), delivered);
        assertEquals(Boolean.FALSE, viewModel.busy().getValue());
    }

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
