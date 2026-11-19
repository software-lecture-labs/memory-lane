package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.ViewModelProvider;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.feature.health.ui.HealthViewModel;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryCreateRepository;

import org.junit.Rule;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MemoryCreateViewModelTest {
    private static final String SESSION = "Bearer token-a";
    private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");
    /** 2024-10-28 10:00 in Istanbul. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2024-10-28T07:00:00Z"), ISTANBUL);
    private static final LocalDate TODAY = LocalDate.of(2024, 10, 28);
    // Outside the Basic Multilingual Plane: one Unicode character, two Java chars.
    private static final String EMOJI = "😀";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final FakeCreateRepository repository = new FakeCreateRepository();
    private final MemoryCreateViewModel viewModel = new MemoryCreateViewModel(repository, CLOCK);

    // --- Validation ---

    @Test
    public void initialStateHasNoErrorsOrDate() {
        assertFalse(state().hasFieldErrors());
        assertNull(state().getDate());
        assertFalse(state().isSaving());
        assertEquals(MemoryCreateUiState.Focus.NONE, state().getFocus());
    }

    @Test
    public void emptyOrBlankTitleIsRequiredAndFocusedFirst() {
        // U+2003 EM SPACE is whitespace the server strips as well.
        for (String title : new String[] {"", "   ", " \t"}) {
            viewModel.onTitleChanged(title);
            viewModel.save(SESSION);
            assertEquals(title, MemoryCreateUiState.TitleError.REQUIRED, state().getTitleError());
            assertEquals(MemoryCreateUiState.Focus.TITLE, state().getFocus());
        }
        assertTrue(repository.requests.isEmpty());
    }

    @Test
    public void titleLengthCountsUnicodeCharactersAfterTrimming() {
        viewModel.onDateSelected(TODAY);
        viewModel.onTitleChanged(EMOJI.repeat(101));
        viewModel.save(SESSION);
        assertEquals(MemoryCreateUiState.TitleError.TOO_LONG, state().getTitleError());
        assertTrue(repository.requests.isEmpty());

        // 100 characters plus surrounding spaces: the spaces do not count.
        viewModel.onTitleChanged("  " + EMOJI.repeat(100) + "  ");
        viewModel.save(SESSION);
        assertEquals(MemoryCreateUiState.TitleError.NONE, state().getTitleError());
        assertEquals(EMOJI.repeat(100), repository.last().draft.title());
    }

    @Test
    public void missingDateIsRequired() {
        viewModel.onTitleChanged("Bursa");
        viewModel.save(SESSION);

        assertEquals(MemoryCreateUiState.DateError.REQUIRED, state().getDateError());
        assertEquals(MemoryCreateUiState.Focus.DATE, state().getFocus());
        assertTrue(repository.requests.isEmpty());
    }

    @Test
    public void futureDateIsRejectedButTodayIsAccepted() {
        viewModel.onTitleChanged("Bursa");
        viewModel.onDateSelected(TODAY.plusDays(1));
        viewModel.save(SESSION);
        assertEquals(MemoryCreateUiState.DateError.FUTURE, state().getDateError());

        viewModel.onDateSelected(TODAY);
        viewModel.save(SESSION);
        assertEquals(MemoryCreateUiState.DateError.NONE, state().getDateError());
        assertEquals(1, repository.requests.size());
    }

    @Test
    public void todayFollowsTheDeviceTimeZone() {
        // 23:30 UTC on the 27th is already the 28th in Istanbul.
        Clock lateUtc = Clock.fixed(Instant.parse("2024-10-27T23:30:00Z"), ISTANBUL);
        assertEquals(TODAY, new MemoryCreateViewModel(repository, lateUtc).today());
    }

    @Test
    public void descriptionLongerThan3000CharactersIsTooLong() {
        viewModel.onTitleChanged("Bursa");
        viewModel.onDateSelected(TODAY);
        viewModel.onDescriptionChanged(EMOJI.repeat(3001));
        viewModel.save(SESSION);

        assertEquals(MemoryCreateUiState.DescriptionError.TOO_LONG, state().getDescriptionError());
        assertEquals(MemoryCreateUiState.Focus.DESCRIPTION, state().getFocus());
        assertTrue(repository.requests.isEmpty());

        viewModel.onDescriptionChanged(EMOJI.repeat(3000));
        viewModel.save(SESSION);
        assertEquals(1, repository.requests.size());
    }

    @Test
    public void allInvalidFieldsShowErrorsAndFocusTheFirstOnScreen() {
        viewModel.onDescriptionChanged("a".repeat(3001));
        viewModel.save(SESSION);

        assertEquals(MemoryCreateUiState.TitleError.REQUIRED, state().getTitleError());
        assertEquals(MemoryCreateUiState.DateError.REQUIRED, state().getDateError());
        assertEquals(MemoryCreateUiState.DescriptionError.TOO_LONG, state().getDescriptionError());
        assertEquals(MemoryCreateUiState.Focus.TITLE, state().getFocus());
    }

    @Test
    public void editingAFieldClearsOnlyItsOwnErrorAndTheFocusRequest() {
        viewModel.onDescriptionChanged("a".repeat(3001));
        viewModel.save(SESSION);

        viewModel.onTitleChanged("B");
        assertEquals(MemoryCreateUiState.TitleError.NONE, state().getTitleError());
        assertEquals(MemoryCreateUiState.DateError.REQUIRED, state().getDateError());
        assertEquals(MemoryCreateUiState.Focus.NONE, state().getFocus());

        viewModel.onDateSelected(TODAY);
        assertEquals(MemoryCreateUiState.DateError.NONE, state().getDateError());
        assertEquals(TODAY, state().getDate());
        assertEquals(MemoryCreateUiState.DescriptionError.TOO_LONG, state().getDescriptionError());

        viewModel.onDescriptionChanged("");
        assertFalse(state().hasFieldErrors());
    }

    // --- Saving ---

    @Test
    public void validDraftSendsOneRequestWithTrimmedTitleAndIsoDate() {
        fillValid();
        viewModel.save(SESSION);
        viewModel.save(SESSION);

        assertEquals("A second tap while saving is ignored", 1, repository.requests.size());
        FakeCreateRepository.Request sent = repository.last();
        assertEquals(SESSION, sent.authorization);
        assertEquals(new MemoryApi.Draft("Bursa'da bir gün", "  Kestane  ", "2024-10-20"), sent.draft);
        // The server accepts only a lowercase UUID as the key.
        assertEquals(sent.key, UUID.fromString(sent.key).toString());
        assertTrue(state().isSaving());
    }

    @Test
    public void successIsReportedOnceAndKeptForANewObserver() {
        fillValid();
        viewModel.save(SESSION);
        repository.last().succeed();

        assertFalse(state().isSaving());
        List<Boolean> delivered = new ArrayList<>();
        viewModel.saved().observeForever(delivered::add);
        assertEquals(List.of(true), delivered);

        viewModel.consumeSaved();
        assertEquals(Boolean.FALSE, viewModel.saved().getValue());
    }

    @Test
    public void successWithoutBodyIsAParseError() {
        fillValid();
        viewModel.save(SESSION);
        repository.last().answer(ApiResult.success(null));

        assertEquals(ApiError.Kind.PARSE, state().getServerError().getKind());
        assertEquals(Boolean.FALSE, viewModel.saved().getValue());
    }

    @Test
    public void failureIsKeptInStateAndRetryReusesTheIdempotencyKey() {
        fillValid();
        viewModel.save(SESSION);
        String firstKey = repository.last().key;
        ApiError failure = ApiError.network();
        repository.last().fail(failure);

        assertSame(failure, state().getServerError());
        assertFalse(state().isSaving());

        // The first request may have reached the server; the same key prevents a duplicate.
        viewModel.save(SESSION);
        assertEquals(firstKey, repository.last().key);
        assertNull("A new attempt clears the old error", state().getServerError());
    }

    @Test
    public void changedDraftGetsANewIdempotencyKey() {
        fillValid();
        viewModel.save(SESSION);
        String firstKey = repository.last().key;
        repository.last().fail(ApiError.network());

        viewModel.onTitleChanged("Başka bir başlık");
        viewModel.save(SESSION);

        assertNotEquals(firstKey, repository.last().key);
    }

    @Test
    public void serverValidationErrorIsShownAsServerError() {
        fillValid();
        viewModel.save(SESSION);
        repository.last().fail(ApiError.http(400, "INVALID_INPUT", "Anı tarihi gelecekte olamaz."));

        assertEquals("INVALID_INPUT", state().getServerError().getCode());
        assertFalse(state().hasFieldErrors());
    }

    @Test
    public void editingAfterAFailureClearsTheServerError() {
        fillValid();
        viewModel.save(SESSION);
        repository.last().fail(ApiError.network());

        viewModel.onDescriptionChanged("Yeni açıklama");

        assertNull(state().getServerError());
    }

    // --- Session ---

    @Test
    public void unauthorizedEndsTheSessionWithoutAServerError() {
        fillValid();
        viewModel.save(SESSION);
        repository.last().fail(ApiError.http(401, "INVALID_SESSION", "Geçerli oturum gereklidir."));

        assertEquals(Boolean.TRUE, viewModel.sessionExpired().getValue());
        assertNull(state().getServerError());
        assertFalse(state().isSaving());
        viewModel.consumeSessionExpired();
        assertEquals(Boolean.FALSE, viewModel.sessionExpired().getValue());
    }

    @Test
    public void withoutSessionNothingIsSentAndTheScreenIsToldToSignIn() {
        fillValid();
        viewModel.save(null);

        assertTrue(repository.requests.isEmpty());
        assertEquals(Boolean.TRUE, viewModel.sessionExpired().getValue());
    }

    @Test
    public void invalidDraftWithoutSessionShowsTheFieldErrorsFirst() {
        viewModel.save(null);

        assertEquals(MemoryCreateUiState.TitleError.REQUIRED, state().getTitleError());
        assertEquals(Boolean.FALSE, viewModel.sessionExpired().getValue());
    }

    // --- Lifecycle ---

    @Test
    public void fieldTextsAreKeptForARecreatedView() {
        viewModel.onTitleChanged("Bursa");
        viewModel.onDescriptionChanged("Kestane");

        assertEquals("Bursa", viewModel.title());
        assertEquals("Kestane", viewModel.description());
    }

    @Test
    public void clearingTheViewModelCancelsTheSave() {
        fillValid();
        viewModel.save(SESSION);

        viewModel.onCleared();

        assertTrue(repository.last().cancelled);
    }

    @Test
    public void factoryCreatesOnlyThisViewModel() {
        ViewModelProvider.Factory factory = MemoryCreateViewModel.factory(repository);

        assertNotNull(factory.create(MemoryCreateViewModel.class));
        assertThrows(IllegalArgumentException.class, () -> factory.create(HealthViewModel.class));
    }

    @Test
    public void stripRemovesUnicodeWhitespaceOnBothEnds() {
        assertEquals("Bursa gezisi", MemoryCreateViewModel.strip("  Bursa gezisi　\n"));
        assertEquals("", MemoryCreateViewModel.strip(" \t "));
    }

    private void fillValid() {
        viewModel.onTitleChanged("  Bursa'da bir gün ");
        viewModel.onDescriptionChanged("  Kestane  ");
        viewModel.onDateSelected(LocalDate.of(2024, 10, 20));
    }

    private MemoryCreateUiState state() { return viewModel.getState().getValue(); }

    /** Records create calls; each stays pending until the test answers it. */
    private static final class FakeCreateRepository implements MemoryCreateRepository {
        final List<Request> requests = new ArrayList<>();

        @Override
        public Cancellable create(String authorization, String idempotencyKey, MemoryApi.Draft draft,
                                  ResultCallback<MemoryApi.Memory> callback) {
            Request request = new Request(authorization, idempotencyKey, draft, callback);
            requests.add(request);
            return () -> request.cancelled = true;
        }

        Request last() { return requests.get(requests.size() - 1); }

        static final class Request {
            final String authorization;
            final String key;
            final MemoryApi.Draft draft;
            private final ResultCallback<MemoryApi.Memory> callback;
            boolean cancelled;

            Request(String authorization, String key, MemoryApi.Draft draft,
                    ResultCallback<MemoryApi.Memory> callback) {
                this.authorization = authorization;
                this.key = key;
                this.draft = draft;
                this.callback = callback;
            }

            void succeed() {
                answer(ApiResult.success(new MemoryApi.Memory("m1", draft.title(), draft.description(),
                        draft.memoryDate())));
            }

            void fail(ApiError error) { answer(ApiResult.failure(error)); }

            void answer(ApiResult<MemoryApi.Memory> result) {
                if (!cancelled) callback.onResult(result);
            }
        }
    }
}
