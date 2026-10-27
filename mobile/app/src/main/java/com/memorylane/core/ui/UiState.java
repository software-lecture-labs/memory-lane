package com.memorylane.core.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;

import java.util.List;

/** What a screen shows. EMPTY and ERROR are separate states so a failed load never looks like "no items". */
public final class UiState<T> {
    public enum Status { LOADING, SUCCESS, EMPTY, ERROR }

    private final Status status;
    @Nullable private final T data;
    @Nullable private final ApiError error;

    private UiState(Status status, @Nullable T data, @Nullable ApiError error) {
        this.status = status;
        this.data = data;
        this.error = error;
    }

    public static <T> UiState<T> loading() {
        return new UiState<>(Status.LOADING, null, null);
    }

    public static <T> UiState<T> success(@Nullable T data) {
        return new UiState<>(Status.SUCCESS, data, null);
    }

    public static <T> UiState<T> empty() {
        return new UiState<>(Status.EMPTY, null, null);
    }

    public static <T> UiState<T> error(@NonNull ApiError error) {
        return new UiState<>(Status.ERROR, null, error);
    }

    public static <T> UiState<T> from(@NonNull ApiResult<T> result) {
        return result.isSuccess() ? success(result.getData()) : error(errorOf(result));
    }

    /** For list screens: only a successful response with no items is EMPTY. */
    public static <E> UiState<List<E>> fromList(@NonNull ApiResult<List<E>> result) {
        if (!result.isSuccess()) {
            return error(errorOf(result));
        }
        List<E> items = result.getData();
        return items == null || items.isEmpty() ? empty() : success(items);
    }

    private static ApiError errorOf(ApiResult<?> result) {
        ApiError error = result.getError();
        return error != null ? error : ApiError.unexpected();
    }

    public Status getStatus() {
        return status;
    }

    @Nullable
    public T getData() {
        return data;
    }

    @Nullable
    public ApiError getError() {
        return error;
    }
}
