package com.memorylane.feature.auth.data;

/** Register returns a user only, without starting a session. */
public final class UserResponse {
    private String id;
    private String email;

    public String getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }
}
