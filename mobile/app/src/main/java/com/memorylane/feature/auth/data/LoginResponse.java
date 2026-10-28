package com.memorylane.feature.auth.data;

/** Login payload only; this class does not persist credentials or establish a session. */
public final class LoginResponse {
    private String accessToken;
    private String tokenType;
    private String expiresAt;
    private UserResponse user;

    public String getAccessToken() {
        return accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    /** ISO 8601 UTC text, including optional fractional seconds, preserved from the wire. */
    public String getExpiresAt() {
        return expiresAt;
    }

    public UserResponse getUser() {
        return user;
    }
}
