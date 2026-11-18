package com.memorylane.feature.auth.ui;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Client-side input rules shared by the login and register screens. They only give early
 * feedback; the server remains the authority (limits from docs/api-contract.md, "Hesap ve oturum").
 */
final class AuthInputRules {
    // Same pattern as AuthService.email on the server: one '@' and a dot in the domain part.
    // Kept this loose on purpose so the app never rejects an address the server would accept.
    private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    static final int EMAIL_MAX_LENGTH = 254;
    static final int PASSWORD_MIN_LENGTH = 15;
    static final int PASSWORD_MAX_LENGTH = 128;

    private AuthInputRules() {
    }

    /**
     * Removes leading and trailing whitespace as {@code String.strip()} does on the server, so
     * Unicode spaces such as U+2003 are removed too. {@code strip()} itself needs API 33.
     */
    static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && Character.isWhitespace(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    /** Mirrors AuthService.email: normalized the same way, then length, character and pattern checks. */
    static boolean isValidEmail(String email) {
        String normalized = strip(email).toLowerCase(Locale.ROOT);
        return normalized.length() <= EMAIL_MAX_LENGTH
                && normalized.codePoints().noneMatch(AuthInputRules::isSpaceOrControl)
                && EMAIL.matcher(normalized).matches();
    }

    private static boolean isSpaceOrControl(int character) {
        return Character.isISOControl(character)
                || Character.isWhitespace(character)
                || Character.isSpaceChar(character);
    }

    /** Password length in Unicode characters, as the server counts it; the value is never trimmed. */
    static int passwordLength(String password) {
        return password.codePointCount(0, password.length());
    }
}
