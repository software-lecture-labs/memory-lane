package com.memorylane.feature.auth.ui;

import java.util.regex.Pattern;

/**
 * Client-side input rules shared by the login and register screens. They only give early
 * feedback; the server remains the authority (limits from docs/api-contract.md, "Hesap ve oturum").
 */
final class AuthInputRules {
    // Basic address syntax only; account verification belongs to the server.
    static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9+._%\\-]{1,256}@[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}"
                    + "(?:\\.[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25})+");

    static final int EMAIL_MAX_LENGTH = 254;
    static final int PASSWORD_MIN_LENGTH = 15;
    static final int PASSWORD_MAX_LENGTH = 128;

    private AuthInputRules() {
    }

    /** Password length in Unicode characters, as the server counts it; the value is never trimmed. */
    static int passwordLength(String password) {
        return password.codePointCount(0, password.length());
    }
}
