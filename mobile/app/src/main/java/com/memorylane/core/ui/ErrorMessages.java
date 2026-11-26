package com.memorylane.core.ui;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.memorylane.R;
import com.memorylane.core.network.ApiError;

/**
 * Maps an {@link ApiError} to the text shown to the user, the same way on every screen.
 *
 * <p>ML-18: the decision is made on the contract {@code code} (and the HTTP status when a body is
 * missing), never on the server's message text. Codes about the request itself (a field rule,
 * a taken e-mail, a full photo set) show the server's Turkish message, because it tells the user
 * what to fix. Codes about the server or the app (outage, overload, rate limit, internal error, a
 * request the backend does not understand) use the app's own text, which says what the user can
 * do; for a rate limit it includes the wait from Retry-After.
 */
public final class ErrorMessages {
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final int HTTP_SERVER_ERROR = 500;
    private static final long SECONDS_SHOWN_AS_SECONDS = 90;

    private ErrorMessages() {
    }

    public static String describe(@NonNull Context context, @NonNull ApiError error) {
        if (usesServerMessage(error)) {
            return error.getMessage();
        }
        @StringRes int text = messageFor(error);
        if (text == R.string.error_rate_limited_seconds || text == R.string.error_rate_limited_minutes) {
            return context.getString(text, waitAmount(error.getRetryAfterSeconds()));
        }
        return context.getString(text);
    }

    /** True when the server's own message is the right text: a request-specific code with a message. */
    static boolean usesServerMessage(@NonNull ApiError error) {
        String message = error.getMessage();
        return error.getKind() == ApiError.Kind.HTTP
                && ownMessageFor(error) == null
                && message != null && !message.trim().isEmpty();
    }

    @StringRes
    static int messageFor(@NonNull ApiError error) {
        switch (error.getKind()) {
            case NETWORK:
                return R.string.error_network;
            case HTTP:
                Integer own = ownMessageFor(error);
                return own != null ? own : R.string.error_http;
            case PARSE:
                return R.string.error_parse;
            default:
                return R.string.error_unexpected;
        }
    }

    /** The app's text for server- and app-level codes, or null when the code is about the request. */
    @Nullable
    @StringRes
    private static Integer ownMessageFor(@NonNull ApiError error) {
        String code = error.getCode() == null ? "" : error.getCode();
        int status = error.getHttpStatus();
        switch (code) {
            case "DATABASE_UNAVAILABLE":
                return R.string.error_database_unavailable;
            case "AUTH_BUSY":
                return R.string.error_server_busy;
            case "RATE_LIMITED":
                return rateLimited(error);
            case "INTERNAL_ERROR":
                return R.string.error_server;
            case "HTTP_ERROR":
            case "INVALID_JSON":
                return R.string.error_app_mismatch;
            case "INVALID_SESSION":
                return R.string.error_session_expired;
            case "":
                // No contract body (a proxy page, /api/health's own 503): fall back to the status.
                if (status == HTTP_SERVICE_UNAVAILABLE) return R.string.error_database_unavailable;
                if (status == HTTP_TOO_MANY_REQUESTS) return rateLimited(error);
                if (status == HTTP_UNAUTHORIZED) return R.string.error_session_expired;
                if (status >= HTTP_SERVER_ERROR) return R.string.error_server;
                return null;
            default:
                return null;
        }
    }

    @StringRes
    private static int rateLimited(@NonNull ApiError error) {
        long seconds = error.getRetryAfterSeconds();
        if (seconds <= 0) return R.string.error_rate_limited;
        return seconds <= SECONDS_SHOWN_AS_SECONDS
                ? R.string.error_rate_limited_seconds : R.string.error_rate_limited_minutes;
    }

    /** Seconds up to 90 are shown as seconds, longer waits as whole minutes rounded up. */
    static long waitAmount(long retryAfterSeconds) {
        return retryAfterSeconds <= SECONDS_SHOWN_AS_SECONDS
                ? retryAfterSeconds : (retryAfterSeconds + 59) / 60;
    }
}
