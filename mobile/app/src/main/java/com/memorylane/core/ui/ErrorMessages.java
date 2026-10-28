package com.memorylane.core.ui;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.memorylane.R;
import com.memorylane.core.network.ApiError;

/** Maps an {@link ApiError} to the text shown to the user, the same way on every screen. */
public final class ErrorMessages {
    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final String DATABASE_UNAVAILABLE = "DATABASE_UNAVAILABLE";

    private ErrorMessages() {
    }

    public static String describe(@NonNull Context context, @NonNull ApiError error) {
        // The contract guarantees message is user-facing Turkish without internals, so prefer it.
        String serverMessage = error.getMessage();
        if (serverMessage != null && !serverMessage.trim().isEmpty()) {
            return serverMessage;
        }
        return context.getString(messageFor(error));
    }

    @StringRes
    static int messageFor(@NonNull ApiError error) {
        switch (error.getKind()) {
            case NETWORK:
                return R.string.error_network;
            case HTTP:
                // /api/health answers 503 without a code; business endpoints send DATABASE_UNAVAILABLE.
                if (error.getHttpStatus() == HTTP_SERVICE_UNAVAILABLE || DATABASE_UNAVAILABLE.equals(error.getCode())) {
                    return R.string.error_database_unavailable;
                }
                return R.string.error_http;
            case PARSE:
                return R.string.error_parse;
            default:
                return R.string.error_unexpected;
        }
    }
}
