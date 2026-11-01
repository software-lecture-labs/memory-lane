package com.memorylane.feature.auth.data;

/** Credentials are sent unchanged; normalization belongs to the server. Never log this body. */
public final class AuthRequest {
    private final String email;
    private final String password;

    public AuthRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }
}
