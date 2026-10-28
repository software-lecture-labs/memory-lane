package com.memorylane.core.ui;

import static org.junit.Assert.assertEquals;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/** ML-28 acceptance: a connection error must never be shown as an empty list. */
public class UiStateTest {

    @Test
    public void networkFailureOnListIsErrorNotEmpty() {
        UiState<List<String>> state = UiState.fromList(ApiResult.failure(ApiError.network()));

        assertEquals(UiState.Status.ERROR, state.getStatus());
        assertEquals(ApiError.Kind.NETWORK, state.getError().getKind());
    }

    @Test
    public void successfulEmptyListIsEmpty() {
        UiState<List<String>> state = UiState.fromList(ApiResult.success(Collections.emptyList()));

        assertEquals(UiState.Status.EMPTY, state.getStatus());
    }

    @Test
    public void successfulListWithItemsIsSuccess() {
        UiState<List<String>> state = UiState.fromList(ApiResult.success(Collections.singletonList("anı")));

        assertEquals(UiState.Status.SUCCESS, state.getStatus());
        assertEquals(1, state.getData().size());
    }

    @Test
    public void failureIsErrorForSingleValues() {
        UiState<String> state = UiState.from(ApiResult.failure(ApiError.http(503, null, null)));

        assertEquals(UiState.Status.ERROR, state.getStatus());
        assertEquals(503, state.getError().getHttpStatus());
    }
}
