package com.memorylane.feature.auth.data;

import com.memorylane.core.network.ApiCaller;
import com.memorylane.core.network.ApiError;
import com.memorylane.core.network.ApiResult;
import com.memorylane.core.network.Cancellable;
import com.memorylane.core.network.ResultCallback;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Uses the existing shared result and error mapping, without storing credentials or tokens. */
public final class RemoteAuthRepository implements AuthRepository {
    private final AuthApi api;
    private final ApiCaller caller;

    public RemoteAuthRepository(AuthApi api, ApiCaller caller) {
        this.api = api;
        this.caller = caller;
    }

    @Override
    public Cancellable register(AuthRequest request, ResultCallback<UserResponse> callback) {
        return caller.enqueue(api.register(request), result -> callback.onResult(
                result.isSuccess() && !validUser(result.getData())
                        ? ApiResult.failure(ApiError.parse()) : result));
    }

    @Override
    public Cancellable login(AuthRequest request, ResultCallback<LoginResponse> callback) {
        return caller.enqueue(api.login(request), result -> callback.onResult(
                result.isSuccess() && !validLogin(result.getData())
                        ? ApiResult.failure(ApiError.parse()) : result));
    }

    private static boolean validUser(UserResponse user) {
        return user != null && hasText(user.getId()) && hasText(user.getEmail());
    }

    private static boolean validLogin(LoginResponse login) {
        return login != null && hasText(login.getAccessToken())
                && "Bearer".equals(login.getTokenType())
                && validExpiry(login.getExpiresAt()) && validUser(login.getUser());
    }

    private static boolean hasText(String value) {
        // Validates only; never changes the token or other field values.
        return value != null && value.codePoints().anyMatch(
                c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c));
    }

    private static boolean validExpiry(String value) {
        if (!hasText(value) || !value.endsWith("Z")) {
            return false;
        }
        try {
            // minSdk 26 supports Instant without additional dependencies.
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }
}
