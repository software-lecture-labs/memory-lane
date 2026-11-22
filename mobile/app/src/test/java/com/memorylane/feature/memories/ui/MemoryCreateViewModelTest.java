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
        // ML-08: with nothing chosen the category is "Diğer" and emotion/location are left out.
        assertEquals(new MemoryApi.Draft("Bursa'da bir gün", "  Kestane  ", "2024-10-20", "OTHER",
                null, null), sent.draft);
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
    public void successKeepsTheCreatedIdAndLocksFurtherSaves() {
        assertNull(viewModel.createdMemoryId());
        fillValid();
        viewModel.save(SESSION);
        repository.last().succeed();

        // The photos are uploaded to this id while the screen stays open.
        assertEquals("m1", viewModel.createdMemoryId());
        viewModel.consumeSaved();
        viewModel.onTitleChanged("Başka bir anı");
        viewModel.save(SESSION);

        assertEquals("A second memory must not be created", 1, repository.requests.size());
        assertFalse(state().isSaving());
        assertEquals(Boolean.FALSE, viewModel.saved().getValue());
    }

    @Test
    public void failedSaveLeavesNoCreatedId() {
        fillValid();
        viewModel.save(SESSION);
        repository.last().fail(ApiError.network());

        assertNull(viewModel.createdMemoryId());
    }

    @Test
    public void successWithoutIdIsAParseError() {
        fillValid();
        viewModel.save(SESSION);
        repository.last().answer(ApiResult.success(new MemoryApi.Memory(null, "Bursa", "", "2024-10-20")));

        // Photos could not be attached to a memory without an id.
        assertEquals(ApiError.Kind.PARSE, state().getServerError().getKind());
        assertNull(viewModel.createdMemoryId());
        assertEquals(Boolean.FALSE, viewModel.saved().getValue());
    }

    // --- After the save (photo upload) ---

    @Test
    public void beforeTheSaveTheFormIsEditableWhateverThePhotosDo() {
        assertEquals(MemoryCreateViewModel.AfterSave.NOT_SAVED, MemoryCreateViewModel.afterSave(null, false, 0));
        assertEquals(MemoryCreateViewModel.AfterSave.NOT_SAVED, MemoryCreateViewModel.afterSave(null, true, 2));
    }

    @Test
    public void runningOrStoppedUploadKeepsTheScreenOpen() {
        // Busy covers uploading, waiting for Retry-After and waiting for "Tekrar dene"/"Vazgeç".
        assertEquals(MemoryCreateViewModel.AfterSave.UPLOADING, MemoryCreateViewModel.afterSave("m1", true, 0));
        assertEquals(MemoryCreateViewModel.AfterSave.UPLOADING, MemoryCreateViewModel.afterSave("m1", true, 1));
    }

    @Test
    public void refusedPhotosAreShownBeforeLeaving() {
        assertEquals(MemoryCreateViewModel.AfterSave.REVIEW, MemoryCreateViewModel.afterSave("m1", false, 1));
    }

    @Test
    public void finishedUploadOrNoPhotosReturnsToTheList() {
        // Also the state after "Vazgeç": the queue is cleared and no summary is kept.
        assertEquals(MemoryCreateViewModel.AfterSave.DONE, MemoryCreateViewModel.afterSave("m1", false, 0));
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

    // --- ML-08: category, emotion, location, today ---

    @Test
    public void categoryDefaultsToOtherAndEmotionAndLocationAreEmpty() {
        assertEquals("OTHER", viewModel.category());
        assertNull(viewModel.emotion());
        assertEquals("", viewModel.latitude());
        assertEquals("", viewModel.longitude());
    }

    @Test
    public void chosenCategoryEmotionAndLocationAreSent() {
        fillValid();
        viewModel.onCategorySelected("TRAVEL");
        viewModel.onEmotionSelected("NOSTALGIC");
        viewModel.onLatitudeChanged(" 40,1885 ");
        viewModel.onLongitudeChanged("-29.061");
        viewModel.save(SESSION);

        MemoryApi.Draft sent = repository.last().draft;
        assertEquals("TRAVEL", sent.category());
        assertEquals("NOSTALGIC", sent.emotion());
        assertEquals(new MemoryApi.Location(40.1885, -29.061), sent.location());
    }

    @Test
    public void unknownCategoryFallsBackToOtherAndUnknownEmotionClearsIt() {
        viewModel.onCategorySelected("FAMILY");
        viewModel.onCategorySelected("WORK");
        assertEquals("OTHER", viewModel.category());
        viewModel.onCategorySelected(null);
        assertEquals("OTHER", viewModel.category());

        viewModel.onEmotionSelected("HAPPY");
        assertEquals("HAPPY", viewModel.emotion());
        viewModel.onEmotionSelected(null);
        assertNull(viewModel.emotion());
        viewModel.onEmotionSelected("ANGRY");
        assertNull(viewModel.emotion());
    }

    @Test
    public void locationNeedsBothCoordinates() {
        fillValid();
        viewModel.onLatitudeChanged("40");
        viewModel.save(SESSION);
        assertEquals(MemoryCreateUiState.LocationError.INCOMPLETE, state().getLocationError());
        assertEquals(MemoryCreateUiState.Focus.LOCATION, state().getFocus());

        viewModel.onLatitudeChanged("  ");
        viewModel.onLongitudeChanged("29");
        viewModel.save(SESSION);
        assertEquals(MemoryCreateUiState.LocationError.INCOMPLETE, state().getLocationError());
        assertTrue(repository.requests.isEmpty());
    }

    @Test
    public void coordinatesOutOfRangeOrNotNumbersAreRejected() {
        fillValid();
        String[][] invalid = {{"90.01", "0"}, {"-91", "0"}, {"0", "180.5"}, {"0", "-181"},
                {"abc", "0"}, {"NaN", "0"}, {"Infinity", "0"}, {"0x10", "0"}, {"1e1", "0"},
                {"4,0,1", "0"}, {"٤٠", "0"}};
        for (String[] pair : invalid) {
            viewModel.onLatitudeChanged(pair[0]);
            viewModel.onLongitudeChanged(pair[1]);
            viewModel.save(SESSION);
            assertEquals(pair[0] + "/" + pair[1], MemoryCreateUiState.LocationError.INVALID,
                    state().getLocationError());
        }
        assertTrue(repository.requests.isEmpty());
    }

    @Test
    public void boundaryCoordinatesAreAccepted() {
        fillValid();
        viewModel.onLatitudeChanged("-90");
        viewModel.onLongitudeChanged("180,0");
        viewModel.save(SESSION);

        assertEquals(MemoryCreateUiState.LocationError.NONE, state().getLocationError());
        assertEquals(new MemoryApi.Location(-90, 180), repository.last().draft.location());
    }

    @Test
    public void editingALocationClearsOnlyTheLocationError() {
        viewModel.onLatitudeChanged("40");
        viewModel.save(SESSION);
        assertEquals(MemoryCreateUiState.TitleError.REQUIRED, state().getTitleError());
        assertEquals(MemoryCreateUiState.LocationError.INCOMPLETE, state().getLocationError());

        viewModel.onLongitudeChanged("2");
        assertEquals(MemoryCreateUiState.LocationError.NONE, state().getLocationError());
        assertEquals(MemoryCreateUiState.TitleError.REQUIRED, state().getTitleError());

        viewModel.onLatitudeChanged("400");
        viewModel.onTitleChanged("Bursa");
        viewModel.onDateSelected(TODAY);
        viewModel.save(SESSION);
        assertEquals(MemoryCreateUiState.LocationError.INVALID, state().getLocationError());
        viewModel.onCategorySelected("SCHOOL");
        assertEquals("A choice keeps the location error", MemoryCreateUiState.LocationError.INVALID,
                state().getLocationError());
    }

    @Test
    public void todayShortcutPicksTodayInTheDeviceZone() {
        viewModel.onTodaySelected();
        assertEquals(TODAY, state().getDate());
    }

    @Test
    public void failedSaveKeepsChoicesAndRetryReusesTheKey() {
        fillValid();
        viewModel.onCategorySelected("FRIENDS");
        viewModel.onEmotionSelected("EXCITED");
        viewModel.onLatitudeChanged("41");
        viewModel.onLongitudeChanged("29");
        viewModel.save(SESSION);
        String key = repository.last().key;
        repository.last().fail(ApiError.network());

        assertEquals("FRIENDS", viewModel.category());
        assertEquals("EXCITED", viewModel.emotion());
        assertEquals("41", viewModel.latitude());
        assertEquals("29", viewModel.longitude());

        viewModel.save(SESSION);
        assertEquals(key, repository.last().key);
    }

    @Test
    public void changingAChoiceAfterAFailureGetsANewKey() {
        fillValid();
        viewModel.save(SESSION);
        String key = repository.last().key;
        repository.last().fail(ApiError.network());

        viewModel.onEmotionSelected("SAD");
        viewModel.save(SESSION);
        assertNotEquals(key, repository.last().key);
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
