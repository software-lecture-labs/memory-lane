package com.memorylane.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Either the response body or an {@link ApiError}, never both. Repositories return this instead
 * of an empty list or null on failure, so "no data" and "could not load" stay distinguishable.
 */
public final class ApiResult<T> {
    @Nullable private final T data;
    @Nullable private final ApiError error;

    private ApiResult(@Nullable T data, @Nullable ApiError error) {
        this.data = data;
        this.error = error;
    }

    /** {@code data} may be null for responses without a body, such as 204. */
    public static <T> ApiResult<T> success(@Nullable T data) {
        return new ApiResult<>(data, null);
    }

    public static <T> ApiResult<T> failure(@NonNull ApiError error) {
        return new ApiResult<>(null, error);
    }

    public boolean isSuccess() {
        return error == null;
    }

    @Nullable
    public T getData() {
        return data;
    }

    /** Non-null exactly when {@link #isSuccess()} is false. */
    @Nullable
    public ApiError getError() {
        return error;
    }
}
