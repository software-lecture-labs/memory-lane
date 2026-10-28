package com.memorylane.backend.auth;

public class AuthFailure extends RuntimeException {
    final int status;
    final String code;
    final long retryAfter;

    AuthFailure(int status, String code, String message) { this(status, code, message, 0); }
    AuthFailure(int status, String code, String message, long retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }
}
