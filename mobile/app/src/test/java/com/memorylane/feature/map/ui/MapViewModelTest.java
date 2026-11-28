package com.memorylane.feature.map.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.memorylane.core.network.ApiError;
import com.memorylane.feature.map.data.FakeMapRepository;
import com.memorylane.feature.map.data.MapApi;

import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class MapViewModelTest {
    private static final String SESSION = "Bearer token-a";

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private final FakeMapRepository repository = new FakeMapRepository();
    private final MapViewModel viewModel = new MapViewModel(repository);

    @Test
    public void startLoadsThePointsWithTheSession() {
        viewModel.start(SESSION);

        assertEquals(MapUiState.Status.LOADING, state().getStatus());
        assertEquals(1, repository.requests.size());
        assertEquals(SESSION, repository.last().authorization);
    }

    @Test
    public void pointsAreShownInServerOrderWithoutAnOpenCard() {
        viewModel.start(SESSION);
        MapApi.Point newer = FakeMapRepository.point("m2", 38.4, 27.1);
        MapApi.Point older = FakeMapRepository.point("m1", 41.0, 29.0);

        repository.last().succeed(FakeMapRepository.points(newer, older));

        assertEquals(MapUiState.Status.CONTENT, state().getStatus());
        assertEquals(List.of(newer, older), state().getPoints());
        assertNull(state().getSelected());
        assertEquals(false, state().isTruncated());
    }

    @Test
    public void noLocatedMemoryIsEmptyNotAnError() {
        viewModel.start(SESSION);

        repository.last().succeed(FakeMapRepository.points());

        assertEquals(MapUiState.Status.EMPTY, state().getStatus());
        assertNull(state().getError());
    }

    @Test
    public void truncatedAnswerIsReported() {
        viewModel.start(SESSION);
        MapApi.Point point = FakeMapRepository.point("m1", 41.0, 29.0);

        repository.last().succeed(new MapApi.Points(List.of(point), 1, 1, true));

        assertTrue(state().isTruncated());
    }

    @Test
    public void failedLoadIsAnErrorAndRetryLoadsAgain() {
        viewModel.start(SESSION);
        ApiError offline = ApiError.network();

        repository.last().fail(offline);

        assertEquals(MapUiState.Status.ERROR, state().getStatus());
        assertSame(offline, state().getError());
        viewModel.retry();
        assertEquals(MapUiState.Status.LOADING, state().getStatus());
        assertEquals(2, repository.requests.size());
    }

    @Test
    public void unauthorizedEndsInSignedOut() {
        viewModel.start(SESSION);

        repository.last().fail(ApiError.http(401, "INVALID_SESSION", "Oturum geçersiz."));

        assertEquals(MapUiState.Status.SIGNED_OUT, state().getStatus());
        viewModel.retry();
        assertEquals("no request without a session", 1, repository.requests.size());
    }

    @Test
    public void answerBreakingTheContractIsAParseErrorNotAHalfDrawnMap() {
        for (MapApi.Point broken : List.of(
                new MapApi.Point("m1", "Başlık", "2026-05-01", null, 29.0, null),
                new MapApi.Point("m1", "Başlık", "2026-05-01", 91.0, 29.0, null),
                new MapApi.Point("m1", "Başlık", "2026-05-01", 41.0, 181.0, null),
                new MapApi.Point("", "Başlık", "2026-05-01", 41.0, 29.0, null),
                new MapApi.Point("m1", " ", "2026-05-01", 41.0, 29.0, null),
                new MapApi.Point("m1", "Başlık", "dün", 41.0, 29.0, null))) {
            MapViewModel model = new MapViewModel(repository);
            model.start(SESSION);

            repository.last().succeed(FakeMapRepository.points(FakeMapRepository.point("ok", 1, 1), broken));

            MapUiState state = model.getState().getValue();
            assertEquals(broken.toString(), MapUiState.Status.ERROR, state.getStatus());
            assertEquals(ApiError.Kind.PARSE, state.getError().getKind());
        }
    }

    @Test
    public void selectingAPinOpensItsCardAndClearingClosesIt() {
        viewModel.start(SESSION);
        MapApi.Point point = FakeMapRepository.point("m1", 41.0, 29.0);
        repository.last().succeed(FakeMapRepository.points(point));

        viewModel.select("m1");
        assertSame(point, state().getSelected());

        viewModel.select("not-on-the-map");
        assertNull(state().getSelected());

        viewModel.select("m1");
        viewModel.clearSelection();
        assertNull(state().getSelected());
    }

    @Test
    public void refreshKeepsPinsUntilTheAnswerAndKeepsTheCardIfTheMemoryIsStillThere() {
        viewModel.start(SESSION);
        repository.last().succeed(FakeMapRepository.points(FakeMapRepository.point("m1", 41.0, 29.0)));
        viewModel.select("m1");

        viewModel.refresh();
        assertEquals("pins stay while reloading", MapUiState.Status.CONTENT, state().getStatus());
        MapApi.Point moved = FakeMapRepository.point("m1", 40.0, 30.0);
        repository.last().succeed(FakeMapRepository.points(moved));

        assertSame(moved, state().getSelected());
    }

    @Test
    public void refreshDropsTheCardOfADeletedMemory() {
        viewModel.start(SESSION);
        repository.last().succeed(FakeMapRepository.points(FakeMapRepository.point("m1", 41.0, 29.0),
                FakeMapRepository.point("m2", 40.0, 30.0)));
        viewModel.select("m1");

        viewModel.refresh();
        repository.last().succeed(FakeMapRepository.points(FakeMapRepository.point("m2", 40.0, 30.0)));

        assertNull(state().getSelected());
    }

    @Test
    public void newSessionCancelsTheOldRequestAndIgnoresItsLateAnswer() {
        viewModel.start(SESSION);
        FakeMapRepository.Request old = repository.last();

        viewModel.start("Bearer token-b");
        old.succeed(FakeMapRepository.points(FakeMapRepository.point("m-a", 41.0, 29.0)));

        assertTrue(old.isCancelled());
        assertEquals(MapUiState.Status.LOADING, state().getStatus());
        assertEquals("Bearer token-b", repository.last().authorization);
    }

    @Test
    public void sameSessionAgainKeepsWhatIsShown() {
        viewModel.start(SESSION);
        repository.last().succeed(FakeMapRepository.points(FakeMapRepository.point("m1", 41.0, 29.0)));

        viewModel.start(SESSION);

        assertEquals(1, repository.requests.size());
        assertEquals(MapUiState.Status.CONTENT, state().getStatus());
    }

    @Test
    public void signedOutMakesNoRequest() {
        viewModel.start(null);

        assertEquals(MapUiState.Status.SIGNED_OUT, state().getStatus());
        assertEquals(0, repository.requests.size());
    }

    private MapUiState state() {
        return viewModel.getState().getValue();
    }
}
