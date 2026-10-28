package com.memorylane.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.stream.MalformedJsonException;

import java.io.EOFException;
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
        call.enqueue(new Callback<T>() {
            @Override
            public void onResponse(@NonNull Call<T> c, @NonNull Response<T> response) {
                if (!c.isCanceled()) {
                    callback.onResult(toResult(response));
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

    private <T> ApiResult<T> toResult(Response<T> response) {
        if (response.isSuccessful()) {
            return ApiResult.success(response.body());
        }
        ErrorBody body = readErrorBody(response.errorBody());
        return ApiResult.failure(ApiError.http(
                response.code(),
                body == null ? null : body.code,
                body == null ? null : body.message));
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

    // Gson reports some malformed bodies as IOExceptions; they must not be shown as "no connection".
    static ApiError classify(Throwable t) {
        if (t instanceof JsonParseException || t instanceof MalformedJsonException || t instanceof EOFException) {
            return ApiError.parse();
        }
        if (t instanceof IOException) {
            return ApiError.network();
        }
        return ApiError.unexpected();
    }
}
