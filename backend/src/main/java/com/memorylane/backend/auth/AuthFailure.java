package com.memorylane.backend.auth;

public class AuthFailure extends RuntimeException {
    final int status;
    final String code;
    final long retryAfter;

    java.util.Map<String, String> body() {
        return java.util.Map.of("code", code, "message", getMessage());
    }

    AuthFailure(int status, String code, String message) {
        this(status, code, message, 0);
    }

    AuthFailure(int status, String code, String message, long retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }
}
