package com.memorylane.feature.memories.ui;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;
import com.memorylane.feature.memories.data.MemoryApi;
import com.memorylane.feature.memories.data.MemoryRepository;
import com.memorylane.feature.memories.ui.MemoriesUiState.Validation;

import org.junit.Rule;
import org.junit.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.*;

public class MemoriesViewModelTest {
    @Rule public InstantTaskExecutorRule executor = new InstantTaskExecutorRule();

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private final FakeRepository repository = new FakeRepository();
    private final MemoriesViewModel model = new MemoriesViewModel(repository,
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

    @Test
    public void failedSaveKeepsDraftAndRetryKeyThenRefreshesList() {
        var memory = new MemoryApi.Memory("1", "Sahil", "Akşam", "2026-01-01");
        signIn();
        model.onTitleChanged(memory.title());
        model.onDescriptionChanged(memory.description());
        model.onDateSelected(LocalDate.parse(memory.memoryDate()));
        model.onCategorySelected("TRAVEL");
        model.onEmotionSelected("HAPPY");
        model.onLatitudeChanged("41.0082");
        model.onLongitudeChanged("28.9784");
        model.save();
        model.save();
        assertEquals(1, repository.saves);
        var key = repository.key;

        repository.save.onResult(ApiResult.failure(ApiError.network()));
        var failure = model.state().getValue();
        assertEquals("Sahil", failure.title());
        assertEquals("Akşam", failure.description());
        assertEquals(LocalDate.parse(memory.memoryDate()), failure.date());
        assertEquals("TRAVEL", failure.category());
        assertEquals("HAPPY", failure.emotion());
        assertEquals("41.0082", failure.latitude());
        assertEquals("28.9784", failure.longitude());
        assertFalse(failure.saving());
        assertNotNull(failure.saveError());

        model.save();
        assertEquals(key, repository.key);
        repository.save.onResult(ApiResult.success(memory));
        assertEquals(2, repository.loads);
        repository.list.onResult(ApiResult.success(new MemoryApi.Page(List.of(memory), false)));
        var saved = model.state().getValue();
        assertEquals(List.of(memory), saved.rows());
        assertTrue(failure.rows().isEmpty());
        assertEquals("", saved.title());
        assertNull(saved.date());
        assertEquals("OTHER", saved.category());
        assertNull(saved.emotion());
        assertEquals("", saved.latitude());
    }

    @Test
    public void serverRejectionKeepsFormAndEditedRetryGetsNewKey() {
        signIn();
        fillRequired("Doğum günü");
        model.save();
        var first = repository.key;
        repository.save.onResult(ApiResult.failure(ApiError.http(400, "INVALID_INPUT", "Başlık zorunludur.")));
        assertEquals("Doğum günü", model.state().getValue().title());
        assertEquals("Başlık zorunludur.", model.state().getValue().saveError().getMessage());

        model.onTitleChanged("Doğum günüm");
        model.save();
        assertEquals(2, repository.saves);
        assertNotEquals(first, repository.key);
    }

    @Test
    public void defaultsAreOtherCategoryWithoutEmotionOrLocation() {
        signIn();
        assertEquals("OTHER", model.state().getValue().category());
        fillRequired("  Okul gezisi  ");
        model.save();
        assertEquals(new MemoryApi.Draft("Okul gezisi", "", TODAY.toString(), "OTHER", null, null),
                repository.draft);
    }

    @Test
    public void unknownChoicesFallBackToDefaults() {
        model.onCategorySelected("TRAVEL");
        model.onCategorySelected(null);
        model.onEmotionSelected("SAD");
        model.onEmotionSelected("ANGRY");
        assertEquals("OTHER", model.state().getValue().category());
        assertNull(model.state().getValue().emotion());
    }

    @Test
    public void requiredTitleAndDateAreValidatedWithoutRequest() {
        signIn();
        model.onTitleChanged("   ");
        model.save();
        assertEquals(EnumSet.of(Validation.TITLE_REQUIRED, Validation.DATE_REQUIRED),
                model.state().getValue().errors());
        assertEquals(0, repository.saves);

        model.onTitleChanged("Kamp");
        assertEquals(EnumSet.of(Validation.DATE_REQUIRED), model.state().getValue().errors());
        model.onTodaySelected();
        assertEquals(TODAY, model.state().getValue().date());
        assertTrue(model.state().getValue().errors().isEmpty());
        model.save();
        assertEquals(1, repository.saves);
    }

    @Test
    public void futureDateIsRejectedButTodayAndPastAreAccepted() {
        signIn();
        model.onTitleChanged("Yarın");
        model.onDateSelected(TODAY.plusDays(1));
        model.save();
        assertTrue(model.state().getValue().has(Validation.DATE_IN_FUTURE));
        assertEquals(0, repository.saves);

        model.onDateSelected(TODAY.minusYears(10));
        model.save();
        assertEquals(1, repository.saves);
        assertEquals("2016-11-19", repository.draft.memoryDate());
    }

    @Test
    public void titleAndDescriptionLimitsCountUnicodeCharacters() {
        signIn();
        String emoji = new String(Character.toChars(0x1F600));
        fillRequired(emoji.repeat(101));
        model.onDescriptionChanged("a".repeat(3001));
        model.save();
        assertEquals(EnumSet.of(Validation.TITLE_TOO_LONG, Validation.DESCRIPTION_TOO_LONG),
                model.state().getValue().errors());
        assertEquals(0, repository.saves);

        model.onTitleChanged(emoji.repeat(100));
        model.onDescriptionChanged(emoji.repeat(3000));
        model.save();
        assertEquals(1, repository.saves);
        assertEquals(emoji.repeat(100), repository.draft.title());
    }

    @Test
    public void locationNeedsBothValidCoordinates() {
        signIn();
        fillRequired("Liman");
        model.onLatitudeChanged("41");
        model.save();
        assertEquals(EnumSet.of(Validation.LOCATION_INCOMPLETE), model.state().getValue().errors());

        model.onLongitudeChanged("181");
        assertTrue(model.state().getValue().errors().isEmpty());
        model.save();
        assertEquals(EnumSet.of(Validation.LOCATION_INVALID), model.state().getValue().errors());

        model.onLongitudeChanged("NaN");
        model.save();
        assertEquals(EnumSet.of(Validation.LOCATION_INVALID), model.state().getValue().errors());
        assertEquals(0, repository.saves);

        model.onLatitudeChanged("-41,5");
        model.onLongitudeChanged("28.97");
        model.save();
        assertEquals(new MemoryApi.Location(-41.5, 28.97), repository.draft.location());
    }

    @Test
    public void unauthorizedListClearsScreenAndExpiresSession() {
        model.attach("Bearer test");
        model.onTitleChanged("Private draft");
        repository.list.onResult(ApiResult.failure(ApiError.http(401, "INVALID_SESSION", null)));
        var state = model.state().getValue();
        assertTrue(state.sessionExpired());
        assertFalse(state.signedIn());
        assertTrue(state.rows().isEmpty());
        assertEquals("", state.title());
    }

    private void signIn() {
        model.attach("Bearer test");
        repository.list.onResult(ApiResult.success(new MemoryApi.Page(List.of(), false)));
    }

    private void fillRequired(String title) {
        model.onTitleChanged(title);
        model.onDateSelected(TODAY);
    }

    private static class FakeRepository implements MemoryRepository {
        ResultCallback<MemoryApi.Memory> save;
        ResultCallback<MemoryApi.Page> list;
        MemoryApi.Draft draft;
        int saves;
        int loads;
        String key;

        @Override
        public Cancellable save(String session, String key, MemoryApi.Draft draft,
                                ResultCallback<MemoryApi.Memory> callback) {
            saves++;
            this.key = key;
            this.draft = draft;
            save = callback;
            return () -> {};
        }

        @Override
        public Cancellable list(String session, int page, ResultCallback<MemoryApi.Page> callback) {
            loads++;
            list = callback;
            return () -> {};
        }
    }
}
