package com.memorylane.core.network;

import androidx.annotation.Nullable;

/** Why a request failed. Screens decide on {@link Kind} and the contract {@code code}, never on message text. */
public final class ApiError {
    public enum Kind {
        /** No response at all: server down, wrong address, timeout, no Wi-Fi. */
        NETWORK,
        /** The server answered with a non-2xx status. */
        HTTP,
        /** A 2xx response whose body could not be read as the expected JSON. */
        PARSE,
        UNEXPECTED
    }

    public static final int NO_HTTP_STATUS = -1;

    private final Kind kind;
    private final int httpStatus;
    @Nullable private final String code;
    @Nullable private final String message;

    private ApiError(Kind kind, int httpStatus, @Nullable String code, @Nullable String message) {
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    public static ApiError network() {
        return new ApiError(Kind.NETWORK, NO_HTTP_STATUS, null, null);
    }

    public static ApiError http(int httpStatus, @Nullable String code, @Nullable String message) {
        return new ApiError(Kind.HTTP, httpStatus, code, message);
    }

    public static ApiError parse() {
        return new ApiError(Kind.PARSE, NO_HTTP_STATUS, null, null);
    }

    public static ApiError unexpected() {
        return new ApiError(Kind.UNEXPECTED, NO_HTTP_STATUS, null, null);
    }

    public Kind getKind() {
        return kind;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** {@code code} field of the contract error body, e.g. {@code DATABASE_UNAVAILABLE}. */
    @Nullable
    public String getCode() {
        return code;
    }

    /** Server-provided Turkish message, already safe to show to the user. */
    @Nullable
    public String getMessage() {
        return message;
    }
}
