package com.memorylane.feature.auth.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.google.gson.Gson;
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

public class RegisterViewModelTest {
    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "correct horse battery";
    // Outside the Basic Multilingual Plane: one Unicode character, two Java chars.
    private static final String EMOJI = "😀";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final RegisterViewModel viewModel = new RegisterViewModel();
    private final FakeRepository repository = new FakeRepository();

    @Test
    public void initialStateHasNoErrorsOrFocusRequest() {
        assertNoErrors();
    }

    @Test
    public void emptyOrBlankEmailIsRequiredAndFocused() {
        // U+2003 EM SPACE and U+3000 IDEOGRAPHIC SPACE are whitespace that trim() would keep.
        for (String email : new String[] {"", "   ", " \t　"}) {
            viewModel.onRegisterClicked(email, PASSWORD, PASSWORD);
            assertEquals(email, RegisterUiState.EmailError.REQUIRED, state().getEmailError());
            assertEquals(RegisterUiState.Focus.EMAIL, state().getFocus());
        }
    }

    @Test
    public void malformedEmailIsInvalid() {
        // U+00A0 NO-BREAK SPACE and U+0007 BELL are rejected by the server as well.
        for (String email : new String[] {"user", "user@", "@example.com", "user@example",
                "user name@example.com", "user@@example.com", "user name@example.com",
                "user\u0007@example.com", "user@example.", "user@.com"}) {
            viewModel.onRegisterClicked(email, PASSWORD, PASSWORD);
            assertEquals(email, RegisterUiState.EmailError.INVALID, state().getEmailError());
            assertEquals(RegisterUiState.Focus.EMAIL, state().getFocus());
        }
    }

    @Test
    public void addressesTheServerAcceptsAreNotRejected() {
        for (String email : new String[] {"dilan@örnek.com", "ışık@example.com",
                "o'brien@example.com", "a!b#c$d&e*f=g?h^i{j|k}l~m@example.com",
                "user@mail." + "a".repeat(40) + ".com", "user@-example.com", "user@example..com"}) {
            viewModel.onRegisterClicked(email, PASSWORD, PASSWORD);
            assertEquals(email, RegisterUiState.EmailError.NONE, state().getEmailError());
        }
    }

    @Test
    public void unicodeWhitespaceAroundEmailIsStripped() {
        viewModel.onRegisterClicked("  user@example.com　\n", PASSWORD, PASSWORD);
        assertNoErrors();
    }

    @Test
    public void emailLongerThanServerLimitIsInvalid() {
        String domain = "@" + "b".repeat(60) + ".com";
        viewModel.onRegisterClicked("a".repeat(254 - domain.length()) + domain, PASSWORD, PASSWORD);
        assertNoErrors();

        viewModel.onRegisterClicked("a".repeat(255 - domain.length()) + domain, PASSWORD, PASSWORD);
        assertEquals(RegisterUiState.EmailError.INVALID, state().getEmailError());
    }

    @Test
    public void emptyPasswordIsRequiredAndFocused() {
        viewModel.onRegisterClicked(EMAIL, "", "");
        assertEquals(RegisterUiState.PasswordError.REQUIRED, state().getPasswordError());
        assertEquals(RegisterUiState.Focus.PASSWORD, state().getFocus());
    }

    @Test
    public void passwordShorterThanFifteenCharactersIsTooShort() {
        String fourteen = "a".repeat(14);
        viewModel.onRegisterClicked(EMAIL, fourteen, fourteen);
        assertEquals(RegisterUiState.PasswordError.TOO_SHORT, state().getPasswordError());
        assertEquals(RegisterUiState.Focus.PASSWORD, state().getFocus());

        String fifteen = "a".repeat(15);
        viewModel.onRegisterClicked(EMAIL, fifteen, fifteen);
        assertNoErrors();
    }

    @Test
    public void passwordLongerThan128CharactersIsTooLong() {
        String limit = "a".repeat(128);
        viewModel.onRegisterClicked(EMAIL, limit, limit);
        assertNoErrors();

        String over = "a".repeat(129);
        viewModel.onRegisterClicked(EMAIL, over, over);
        assertEquals(RegisterUiState.PasswordError.TOO_LONG, state().getPasswordError());
    }

    @Test
    public void passwordLengthCountsUnicodeCharactersNotJavaChars() {
        String fifteen = EMOJI.repeat(15);
        viewModel.onRegisterClicked(EMAIL, fifteen, fifteen);
        assertNoErrors();

        String fourteen = EMOJI.repeat(14);
        viewModel.onRegisterClicked(EMAIL, fourteen, fourteen);
        assertEquals(RegisterUiState.PasswordError.TOO_SHORT, state().getPasswordError());

        String over = EMOJI.repeat(129);
        viewModel.onRegisterClicked(EMAIL, over, over);
        assertEquals(RegisterUiState.PasswordError.TOO_LONG, state().getPasswordError());
    }

    @Test
    public void passwordIsNotTrimmedBeforeCounting() {
        String padded = "  " + "a".repeat(13);
        viewModel.onRegisterClicked(EMAIL, padded, padded);
        assertNoErrors();
    }

    @Test
    public void emptyConfirmationIsRequiredAndFocused() {
        viewModel.onRegisterClicked(EMAIL, PASSWORD, "");
        assertEquals(RegisterUiState.ConfirmError.REQUIRED, state().getConfirmError());
        assertEquals(RegisterUiState.Focus.CONFIRM, state().getFocus());
    }

    @Test
    public void differentConfirmationIsMismatch() {
        for (String confirm : new String[] {"correct horse batterY", PASSWORD + " "}) {
            viewModel.onRegisterClicked(EMAIL, PASSWORD, confirm);
            assertEquals(confirm, RegisterUiState.ConfirmError.MISMATCH, state().getConfirmError());
            assertEquals(RegisterUiState.Focus.CONFIRM, state().getFocus());
        }
    }

    @Test
    public void allInvalidFieldsShowErrorsAndFocusEmailFirst() {
        viewModel.onRegisterClicked("", "", "");
        assertEquals(RegisterUiState.EmailError.REQUIRED, state().getEmailError());
        assertEquals(RegisterUiState.PasswordError.REQUIRED, state().getPasswordError());
        assertEquals(RegisterUiState.ConfirmError.REQUIRED, state().getConfirmError());
        assertEquals(RegisterUiState.Focus.EMAIL, state().getFocus());
    }

    @Test
    public void editingEachFieldClearsOnlyItsOwnErrorWithoutMovingFocus() {
        viewModel.onRegisterClicked("invalid", "short", "");
        viewModel.onEmailChanged("invalid2");
        assertEquals(RegisterUiState.EmailError.NONE, state().getEmailError());
        assertEquals(RegisterUiState.PasswordError.TOO_SHORT, state().getPasswordError());
        assertEquals(RegisterUiState.ConfirmError.REQUIRED, state().getConfirmError());
        assertEquals(RegisterUiState.Focus.NONE, state().getFocus());

        viewModel.onPasswordChanged("shorter");
        assertEquals(RegisterUiState.PasswordError.NONE, state().getPasswordError());
        assertEquals(RegisterUiState.ConfirmError.REQUIRED, state().getConfirmError());

        viewModel.onConfirmChanged("x");
        assertNoErrors();
    }

    @Test
    public void editingPasswordAlsoClearsMismatch() {
        viewModel.onRegisterClicked(EMAIL, PASSWORD, "something else entirely");
        viewModel.onPasswordChanged(PASSWORD + "!");
        assertNoErrors();
    }

    @Test
    public void fieldTextsAreKeptForARecreatedView() {
        viewModel.onEmailChanged(EMAIL);
        viewModel.onPasswordChanged(PASSWORD);
        viewModel.onConfirmChanged("typed so far");
        assertEquals(EMAIL, viewModel.email());
        assertEquals(PASSWORD, viewModel.password());
        assertEquals("typed so far", viewModel.confirm());
    }

    @Test
    public void invalidInputDoesNotCallRepository() {
        viewModel.register(repository, EMAIL, PASSWORD, "different password here");
        assertTrue(repository.pending.isEmpty());
        assertEquals(Boolean.FALSE, viewModel.busy().getValue());
    }

    @Test
    public void validInputSendsOneUnchangedRequestAndKeepsSuccessForNewObserver() {
        viewModel.register(repository, "  User@Example.com ", PASSWORD, PASSWORD);
        viewModel.register(repository, "  User@Example.com ", PASSWORD, PASSWORD);
        assertEquals(1, repository.pending.size());
        assertEquals(List.of("  User@Example.com "), repository.emails);
        assertEquals(Boolean.TRUE, viewModel.busy().getValue());

        repository.pending.get(0).onResult(ApiResult.success(null));
        List<Boolean> delivered = new ArrayList<>();
        viewModel.registered().observeForever(delivered::add);
        assertEquals(List.of(true), delivered);
        assertEquals(Boolean.FALSE, viewModel.busy().getValue());

        viewModel.consumeRegistered();
        assertEquals(Boolean.FALSE, viewModel.registered().getValue());
    }

    @Test
    public void emailInUseIsKeptInStateAsEmailErrorAndFocusesEmail() {
        failWith(emailInUse());
        assertEquals(RegisterUiState.EmailError.IN_USE, state().getEmailError());
        assertEquals(RegisterUiState.Focus.EMAIL, state().getFocus());
        assertNull(state().getServerError());
        assertEquals(Boolean.FALSE, viewModel.registered().getValue());
    }

    @Test
    public void emailInUseSurvivesPasswordAndConfirmEdits() {
        failWith(emailInUse());
        viewModel.onPasswordChanged(PASSWORD + " new");
        assertEquals(RegisterUiState.EmailError.IN_USE, state().getEmailError());
        viewModel.onConfirmChanged(PASSWORD + " new");
        assertEquals(RegisterUiState.EmailError.IN_USE, state().getEmailError());
        assertEquals(RegisterUiState.Focus.NONE, state().getFocus());
    }

    @Test
    public void editingEmailClearsEmailInUse() {
        failWith(emailInUse());
        viewModel.onEmailChanged("other@example.com");
        assertNoErrors();
    }

    @Test
    public void newRequestClearsEmailInUse() {
        failWith(emailInUse());
        viewModel.register(repository, "other@example.com", PASSWORD, PASSWORD);
        assertNoErrors();
        assertEquals(2, repository.pending.size());
    }

    @Test
    public void generalServerErrorIsKeptInStateWithoutFieldErrors() {
        ApiError failure = ApiError.http(429, "TOO_MANY_REQUESTS", "Çok fazla deneme.");
        failWith(failure);
        assertSame(failure, state().getServerError());
        assertEquals(RegisterUiState.EmailError.NONE, state().getEmailError());
        assertEquals(RegisterUiState.Focus.NONE, state().getFocus());
    }

    @Test
    public void editingAnyFieldClearsGeneralServerError() {
        failWith(ApiError.network());
        viewModel.onPasswordChanged(PASSWORD + "!");
        assertNull(state().getServerError());

        failWith(ApiError.network());
        viewModel.onConfirmChanged("x");
        assertNull(state().getServerError());

        failWith(ApiError.network());
        viewModel.onEmailChanged(EMAIL);
        assertNull(state().getServerError());
    }

    @Test
    public void newRequestClearsGeneralServerError() {
        failWith(ApiError.network());
        viewModel.register(repository, EMAIL, PASSWORD, PASSWORD);
        assertNull(state().getServerError());
    }

    @Test
    public void clearingViewModelCancelsPendingRequest() {
        viewModel.register(repository, EMAIL, PASSWORD, PASSWORD);
        viewModel.onCleared();
        assertEquals(1, repository.cancelled);
    }

    private RegisterUiState state() { return viewModel.getState().getValue(); }

    /** Sends a valid request and answers the latest one with the given error. */
    private void failWith(ApiError error) {
        viewModel.register(repository, EMAIL, PASSWORD, PASSWORD);
        repository.pending.get(repository.pending.size() - 1).onResult(ApiResult.failure(error));
    }

    private static ApiError emailInUse() {
        return ApiError.http(409, "EMAIL_IN_USE", "Bu e-posta zaten kayıtlı.");
    }

    private void assertNoErrors() {
        assertEquals(RegisterUiState.EmailError.NONE, state().getEmailError());
        assertEquals(RegisterUiState.PasswordError.NONE, state().getPasswordError());
        assertEquals(RegisterUiState.ConfirmError.NONE, state().getConfirmError());
        assertEquals(RegisterUiState.Focus.NONE, state().getFocus());
        assertNull(state().getServerError());
    }

    /** Records register calls. AuthRequest has no getters, so the email is read from its JSON wire form. */
    private static final class FakeRepository implements AuthRepository {
        final List<ResultCallback<UserResponse>> pending = new ArrayList<>();
        final List<String> emails = new ArrayList<>();
        int cancelled;

        @Override
        public Cancellable register(AuthRequest request, ResultCallback<UserResponse> callback) {
            pending.add(callback);
            emails.add(new Gson().toJsonTree(request).getAsJsonObject()
                    .get("email").getAsString());
            return () -> cancelled++;
        }

        @Override
        public Cancellable login(AuthRequest request, ResultCallback<LoginResponse> callback) {
            throw new AssertionError("Unexpected login");
        }
    }
}
