package com.memorylane.feature.memories.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.memories.data.FakeMemoryDetailRepository;
import com.memorylane.feature.memories.data.FakeMemoryEditRepository;
import com.memorylane.feature.memories.data.MemoryApi;

import org.junit.Rule;
import org.junit.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

public class MemoryEditViewModelTest {
    private static final String SESSION = "Bearer token-a";
    private static final String MEMORY = "m1";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final String NEWER_ETAG = "\"memory-m1-r2\"";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final FakeMemoryDetailRepository details = new FakeMemoryDetailRepository();
    private final FakeMemoryEditRepository editor = new FakeMemoryEditRepository();
    private final MemoryEditViewModel viewModel = new MemoryEditViewModel(details, editor, MEMORY, CLOCK);

    // --- Loading ---

    @Test
    public void openingReadsTheDetailAndFillsTheForm() {
        viewModel.start(SESSION);
        assertEquals(MemoryEditUiState.Status.LOADING, state().getStatus());
        assertEquals(MEMORY, details.last().memoryId);
        assertEquals(SESSION, details.last().authorization);

        details.last().succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));

        assertEquals(MemoryEditUiState.Status.READY, state().getStatus());
        assertEquals("Bursa'da bir sonbahar günü", viewModel.title());
        assertEquals("Kestane şekeri molası.", viewModel.description());
        assertEquals(LocalDate.of(2024, 10, 28), state().getDate());
        assertEquals("TRAVEL", state().getCategory());
        assertEquals(Integer.valueOf(1), viewModel.formVersion().getValue());
        assertFalse(viewModel.hasUnsavedChanges());
    }

    @Test
    public void memoryWithoutCategoryOrDescriptionStartsWithOtherAndEmptyText() {
        viewModel.start(SESSION);

        details.last().succeed(new MemoryApi.Detail(MEMORY, "Sade", null, "2025-01-01", null, null, null,
                null, null, 0, null, List.of()));

        assertEquals(MemoryLabels.DEFAULT_CATEGORY, state().getCategory());
        assertEquals("", viewModel.description());
        assertFalse(viewModel.hasUnsavedChanges());
    }

    @Test
    public void missingMemoryIsNotFoundAndExpiredSessionIsSignedOut() {
        viewModel.start(SESSION);
        details.last().fail(ApiError.http(404, "MEMORY_NOT_FOUND", "Anı bulunamadı."));
        assertEquals(MemoryEditUiState.Status.NOT_FOUND, state().getStatus());

        MemoryEditViewModel other = new MemoryEditViewModel(details, editor, MEMORY, CLOCK);
        other.start(SESSION);
        details.last().fail(ApiError.http(401, "INVALID_SESSION", "Geçerli oturum gereklidir."));
        assertEquals(MemoryEditUiState.Status.SIGNED_OUT, other.getState().getValue().getStatus());
    }

    @Test
    public void loadErrorOffersRetry() {
        viewModel.start(SESSION);
        details.last().fail(ApiError.network());

        assertEquals(MemoryEditUiState.Status.LOAD_ERROR, state().getStatus());
        assertEquals(ApiError.Kind.NETWORK, state().getLoadError().getKind());

        viewModel.retryLoad();
        assertEquals(2, details.requests.size());
        details.last().succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));
        assertEquals(MemoryEditUiState.Status.READY, state().getStatus());
    }

    @Test
    public void withoutSessionItIsSignedOutAndRequestsNothing() {
        viewModel.start(null);

        assertEquals(MemoryEditUiState.Status.SIGNED_OUT, state().getStatus());
        assertTrue(details.requests.isEmpty());
    }

    @Test
    public void sameSessionAgainKeepsTheDraft() {
        ready();
        viewModel.onTitleChanged("Yeni başlık");

        viewModel.start(SESSION); // a recreated view, e.g. after rotation

        assertEquals(1, details.requests.size());
        assertEquals("Yeni başlık", viewModel.title());
    }

    // --- Unsaved changes ---

    @Test
    public void everyFieldChangeCountsAndUndoingItDoesNot() {
        ready();

        viewModel.onTitleChanged("Başka başlık");
        assertTrue(viewModel.hasUnsavedChanges());
        viewModel.onTitleChanged("  Bursa'da bir sonbahar günü  "); // only spaces differ, as saved
        assertFalse(viewModel.hasUnsavedChanges());

        viewModel.onDescriptionChanged("Yeni not");
        assertTrue(viewModel.hasUnsavedChanges());
        viewModel.onDescriptionChanged("Kestane şekeri molası.");
        assertFalse(viewModel.hasUnsavedChanges());

        viewModel.onDateSelected(LocalDate.of(2024, 10, 29));
        assertTrue(viewModel.hasUnsavedChanges());
        viewModel.onDateSelected(LocalDate.of(2024, 10, 28));
        assertFalse(viewModel.hasUnsavedChanges());

        viewModel.onCategorySelected("FAMILY");
        assertTrue(viewModel.hasUnsavedChanges());
        viewModel.onCategorySelected("TRAVEL");
        assertFalse(viewModel.hasUnsavedChanges());
    }

    @Test
    public void unknownCategoryIsIgnoredAndNothingIsUnsavedBeforeTheFormLoads() {
        viewModel.start(SESSION);
        viewModel.onTitleChanged("Erken yazı");
        assertFalse(viewModel.hasUnsavedChanges());

        details.last().succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));
        viewModel.onCategorySelected("WORK");

        assertEquals("TRAVEL", state().getCategory());
    }

    // --- Saving ---

    @Test
    public void saveSendsEveryWritableFieldWithTheDetailTagAndKeepsEmotionAndLocation() {
        ready();
        viewModel.onTitleChanged("  Yeni başlık ");
        viewModel.onCategorySelected("FRIENDS");

        viewModel.save();
        viewModel.save(); // a second tap while saving sends nothing

        assertEquals(1, editor.requests.size());
        FakeMemoryEditRepository.Request sent = editor.last();
        assertEquals(SESSION, sent.authorization);
        assertEquals(MEMORY, sent.memoryId);
        assertEquals(FakeMemoryDetailRepository.ETAG, sent.ifMatch);
        assertEquals(new MemoryApi.Draft("Yeni başlık", "Kestane şekeri molası.", "2024-10-28", "FRIENDS",
                "PEACEFUL", new MemoryApi.Location(40.1885, 29.0610)), sent.draft);
        assertTrue(state().isSaving());

        sent.succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));

        assertEquals(Boolean.TRUE, viewModel.saved().getValue());
        assertFalse(state().isSaving());
        assertFalse(viewModel.hasUnsavedChanges());
        viewModel.consumeSaved();
        assertEquals(Boolean.FALSE, viewModel.saved().getValue());
    }

    @Test
    public void memoryWithoutEmotionOrLocationSendsNoneBack() {
        viewModel.start(SESSION);
        details.last().succeed(FakeMemoryDetailRepository.plainMemory(MEMORY));
        viewModel.onTitleChanged("Değişti");

        viewModel.save();

        assertNull(editor.last().draft.emotion());
        assertNull(editor.last().draft.location());
        assertEquals("OTHER", editor.last().draft.category());
    }

    @Test
    public void invalidFieldsStopTheSaveAndPointAtTheFirstOne() {
        ready();
        viewModel.onTitleChanged("   ");
        viewModel.onDescriptionChanged("a".repeat(3001));
        viewModel.onDateSelected(TODAY.plusDays(1));

        viewModel.save();

        assertTrue(editor.requests.isEmpty());
        assertEquals(MemoryCreateUiState.TitleError.REQUIRED, state().getTitleError());
        assertEquals(MemoryCreateUiState.DescriptionError.TOO_LONG, state().getDescriptionError());
        assertEquals(MemoryCreateUiState.DateError.FUTURE, state().getDateError());
        assertEquals(MemoryCreateUiState.Focus.TITLE, state().getFocus());

        viewModel.onTitleChanged("a".repeat(101));
        viewModel.save();
        assertEquals(MemoryCreateUiState.TitleError.TOO_LONG, state().getTitleError());

        viewModel.onTitleChanged("Düzgün");
        viewModel.onDescriptionChanged("Kısa");
        viewModel.save();
        assertEquals(MemoryCreateUiState.Focus.DATE, state().getFocus());
        assertTrue(editor.requests.isEmpty());

        viewModel.onDateSelected(TODAY);
        viewModel.save();
        assertEquals(1, editor.requests.size());
    }

    @Test
    public void fieldAndPreconditionRefusalsAreShownAndClearedByTheNextEdit() {
        ready();
        viewModel.save();
        editor.last().fail(ApiError.http(400, "INVALID_INPUT", "Başlık en fazla 100 karakter olabilir."));

        assertEquals("INVALID_INPUT", state().getSaveError().getCode());
        assertFalse(state().isSaving());
        assertEquals(MemoryEditUiState.Status.READY, state().getStatus());

        viewModel.onTitleChanged("Düzeltildi");
        assertNull(state().getSaveError());

        viewModel.save();
        editor.last().fail(ApiError.http(428, "PRECONDITION_REQUIRED", "Güncelleme için If-Match başlığı gerekir."));
        assertEquals(428, state().getSaveError().getHttpStatus());
        assertEquals(MemoryEditUiState.Conflict.NONE, state().getConflict());
    }

    @Test
    public void memoryGoneWhileEditingIsNotFoundAndRejectedSessionIsSignedOut() {
        ready();
        viewModel.save();
        editor.last().fail(ApiError.http(404, "MEMORY_NOT_FOUND", "Anı bulunamadı."));
        assertEquals(MemoryEditUiState.Status.NOT_FOUND, state().getStatus());
        assertFalse(viewModel.hasUnsavedChanges());

        MemoryEditViewModel other = new MemoryEditViewModel(details, editor, MEMORY, CLOCK);
        other.start(SESSION);
        details.last().succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));
        other.save();
        editor.last().fail(ApiError.http(401, "INVALID_SESSION", "Geçerli oturum gereklidir."));
        assertEquals(MemoryEditUiState.Status.SIGNED_OUT, other.getState().getValue().getStatus());
    }

    // --- 412: the memory changed elsewhere ---

    @Test
    public void conflictKeepsTheDraftAndBlocksSavingUntilTheLatestVersionIsRead() {
        ready();
        viewModel.onTitleChanged("Benim başlığım");
        viewModel.save();

        editor.last().fail(ApiError.http(412, "MEMORY_MODIFIED", "Anı başka bir işlemle değişti."));

        assertEquals(MemoryEditUiState.Conflict.MODIFIED, state().getConflict());
        assertEquals("Benim başlığım", viewModel.title());
        assertTrue(viewModel.hasUnsavedChanges());
        viewModel.save(); // an old tag can only fail again: nothing is sent
        assertEquals(1, editor.requests.size());
    }

    @Test
    public void reloadingShowsTheLatestVersionNextToTheDraftAndSavesWithItsTag() {
        conflict();

        viewModel.reloadLatest();
        assertEquals(MemoryEditUiState.Conflict.RELOADING, state().getConflict());
        assertTrue(state().isSaving());
        assertEquals(2, details.requests.size());
        MemoryApi.Detail latest = changedElsewhere();
        details.last().succeed(latest, NEWER_ETAG);

        assertEquals(MemoryEditUiState.Conflict.REVIEW, state().getConflict());
        assertSame(latest, state().getLatest());
        assertFalse(state().isSaving());
        assertEquals("Benim başlığım", viewModel.title()); // the draft is not replaced
        assertEquals(1, editor.requests.size()); // and nothing was resent automatically

        viewModel.save(); // the user's explicit decision
        FakeMemoryEditRepository.Request sent = editor.last();
        assertEquals(NEWER_ETAG, sent.ifMatch);
        assertEquals("Benim başlığım", sent.draft.title());
        assertEquals("HAPPY", sent.draft.emotion()); // the other device's emotion is not cleared
        assertEquals(MemoryEditUiState.Conflict.NONE, state().getConflict());
    }

    @Test
    public void usingTheLatestVersionReplacesTheDraft() {
        conflict();
        viewModel.reloadLatest();
        details.last().succeed(changedElsewhere(), NEWER_ETAG);
        Integer before = viewModel.formVersion().getValue();

        viewModel.useLatest();

        assertEquals("Başka cihazdaki başlık", viewModel.title());
        assertEquals("FAMILY", state().getCategory());
        assertEquals(MemoryEditUiState.Conflict.NONE, state().getConflict());
        assertNull(state().getLatest());
        assertEquals(Integer.valueOf(before + 1), viewModel.formVersion().getValue());
        assertFalse(viewModel.hasUnsavedChanges());
    }

    @Test
    public void keepingTheDraftLeavesItUnsavedAgainstTheLatestVersion() {
        conflict();
        viewModel.reloadLatest();
        details.last().succeed(changedElsewhere(), NEWER_ETAG);

        viewModel.keepDraft();

        assertEquals(MemoryEditUiState.Conflict.NONE, state().getConflict());
        assertEquals("Benim başlığım", viewModel.title());
        assertTrue(viewModel.hasUnsavedChanges());
    }

    @Test
    public void failedReloadKeepsTheConflictAndShowsWhy() {
        conflict();
        viewModel.reloadLatest();

        details.last().fail(ApiError.network());

        assertEquals(MemoryEditUiState.Conflict.MODIFIED, state().getConflict());
        assertEquals(ApiError.Kind.NETWORK, state().getSaveError().getKind());
        assertFalse(state().isSaving());
        viewModel.reloadLatest();
        assertEquals(3, details.requests.size());
    }

    @Test
    public void clearingTheScreenCancelsItsRequest() {
        ready();
        viewModel.save();

        viewModel.onCleared();

        assertTrue(editor.last().isCancelled());
    }

    private void ready() {
        viewModel.start(SESSION);
        details.last().succeed(FakeMemoryDetailRepository.fullMemory(MEMORY));
    }

    private void conflict() {
        ready();
        viewModel.onTitleChanged("Benim başlığım");
        viewModel.save();
        editor.last().fail(ApiError.http(412, "MEMORY_MODIFIED", "Anı başka bir işlemle değişti."));
    }

    private static MemoryApi.Detail changedElsewhere() {
        return new MemoryApi.Detail(MEMORY, "Başka cihazdaki başlık", "Yeni not", "2024-10-27", "FAMILY", "HAPPY",
                new MemoryApi.Location(40.1885, 29.0610), null, null, 2, "p1",
                FakeMemoryDetailRepository.fullMemory(MEMORY).photos());
    }

    private MemoryEditUiState state() {
        MemoryEditUiState state = viewModel.getState().getValue();
        assertNotNull(state);
        return state;
    }
}
