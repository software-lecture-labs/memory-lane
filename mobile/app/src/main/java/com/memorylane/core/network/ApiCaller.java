package com.memorylane.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Runs Retrofit calls and turns every outcome into an {@link ApiResult}. All repositories go
 * through here so error classification is written once and stays consistent across screens.
 */
public final class ApiCaller {
    private final Gson gson;

    public ApiCaller(Gson gson) {
        this.gson = gson;
    }

    /** On Android the callback runs on the main thread (Retrofit's default callback executor). */
    public <T> Cancellable enqueue(Call<T> call, ResultCallback<T> callback) {
        return enqueue(call, response -> ApiResult.success(response.body()), callback);
    }

    /**
     * Like {@link #enqueue} for endpoints whose 2xx answer must carry a body and an {@code ETag}
     * (the memory detail). A 2xx answer missing either breaks the contract and is reported as
     * {@link ApiError.Kind#PARSE}, so a screen never edits without a tag to send back.
     * Non-2xx answers are classified exactly as in {@link #enqueue}.
     */
    public <T> Cancellable enqueueTagged(Call<T> call, ResultCallback<Tagged<T>> callback) {
        return enqueue(call, response -> {
            T body = response.body();
            String etag = response.headers().get("ETag");
            return body == null || etag == null || etag.trim().isEmpty()
                    ? ApiResult.failure(ApiError.parse())
                    : ApiResult.success(new Tagged<>(body, etag.trim()));
        }, callback);
    }

    private interface SuccessMapper<T, R> {
        ApiResult<R> map(Response<T> response);
    }

    private <T, R> Cancellable enqueue(Call<T> call, SuccessMapper<T, R> onSuccess, ResultCallback<R> callback) {
        call.enqueue(new Callback<T>() {
            @Override
            public void onResponse(@NonNull Call<T> c, @NonNull Response<T> response) {
                if (!c.isCanceled()) {
                    callback.onResult(response.isSuccessful() ? onSuccess.map(response) : toError(response));
                }
            }

            @Override
            public void onFailure(@NonNull Call<T> c, @NonNull Throwable t) {
                // A cancelled call also lands here; the screen that cancelled it no longer listens.
                if (!c.isCanceled()) {
                    callback.onResult(ApiResult.failure(classify(t)));
                }
            }
        });
        return call::cancel;
    }

    private <R> ApiResult<R> toError(Response<?> response) {
        ErrorBody body = readErrorBody(response.errorBody());
        return ApiResult.failure(ApiError.http(
                response.code(),
                body == null ? null : body.code,
                body == null ? null : body.message,
                retryAfterSeconds(response.headers().get("Retry-After"))));
    }

    // The backend sends whole seconds. The HTTP-date form is not used by our API and is treated as
    // missing, so callers fall back to the contract's safe default instead of guessing a date.
    static long retryAfterSeconds(@Nullable String header) {
        if (header == null) {
            return ApiError.NO_RETRY_AFTER;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? seconds : ApiError.NO_RETRY_AFTER;
        } catch (NumberFormatException notSeconds) {
            return ApiError.NO_RETRY_AFTER;
        }
    }

    // Not every error has the contract body (a proxy page, or /api/health's own 503 shape), so a
    // missing or unreadable body still yields an HTTP error with the status code.
    @Nullable
    private ErrorBody readErrorBody(@Nullable ResponseBody errorBody) {
        if (errorBody == null) {
            return null;
        }
        try (ResponseBody body = errorBody) {
            return gson.fromJson(body.string(), ErrorBody.class);
        } catch (IOException | JsonParseException e) {
            return null;
        }
    }

    // Only ParseErrorConverterFactory marks conversion failures, so every other IOException is a
    // transport problem. Exception types alone are ambiguous: OkHttp also throws EOFException when
    // a connection drops mid-body, and that must be shown as "no connection, try again".
    static ApiError classify(Throwable t) {
        if (t instanceof ResponseParseException) {
            return ApiError.parse();
        }
        if (t instanceof IOException) {
            return ApiError.network();
        }
        return ApiError.unexpected();
    }
}
