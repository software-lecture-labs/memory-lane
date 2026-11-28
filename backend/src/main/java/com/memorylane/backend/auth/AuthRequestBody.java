package com.memorylane.backend.auth;

import java.util.LinkedHashMap;

/**
 * JSON body of register and login. It stays a map so AuthController can still refuse unknown,
 * missing and non-text fields exactly as before; only {@link #toString()} differs.
 *
 * <p>Spring MVC logs every request body it reads (DEBUG shortened, TRACE in full) using the body's
 * toString. A plain map would write the password and e-mail into that line in clear text as soon as
 * someone turns request logging on, so the credentials are masked here, in the one place every
 * logger goes through.
 */
final class AuthRequestBody extends LinkedHashMap<String, Object> {
    /** Text logged instead of the body. */
    static final String MASKED = "{credentials masked}";

    @Override
    public String toString() {
        return MASKED;
    }
}
