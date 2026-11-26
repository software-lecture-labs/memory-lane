package com.memorylane.core.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.memorylane.R;
import com.memorylane.core.network.ApiError;

import org.junit.Test;

public class ErrorMessagesTest {

    @Test
    public void networkErrorAsksToCheckConnection() {
        assertEquals(R.string.error_network, ErrorMessages.messageFor(ApiError.network()));
    }

    @Test
    public void healthDownExplainsDatabaseProblem() {
        assertEquals(R.string.error_database_unavailable, ErrorMessages.messageFor(ApiError.http(503, null, null)));
    }

    @Test
    public void contractDatabaseCodeExplainsDatabaseProblem() {
        assertEquals(R.string.error_database_unavailable,
                ErrorMessages.messageFor(ApiError.http(500, "DATABASE_UNAVAILABLE", null)));
    }

    @Test
    public void otherHttpErrorIsGeneric() {
        assertEquals(R.string.error_http, ErrorMessages.messageFor(ApiError.http(404, "MEMORY_NOT_FOUND", null)));
    }

    @Test
    public void parseErrorHasOwnMessage() {
        assertEquals(R.string.error_parse, ErrorMessages.messageFor(ApiError.parse()));
    }

    // ML-18: the text is chosen by the contract code, not by the server's message.

    @Test
    public void requestSpecificCodesShowTheServersFieldMessage() {
        for (String code : new String[] {"INVALID_INPUT", "EMAIL_IN_USE", "INVALID_CREDENTIALS",
                "PHOTO_LIMIT_REACHED", "UNSUPPORTED_PHOTO_TYPE", "FILE_TOO_LARGE", "MEMORY_NOT_FOUND"}) {
            assertTrue(code, ErrorMessages.usesServerMessage(ApiError.http(400, code, "Başlık zorunludur.")));
        }
    }

    @Test
    public void serverAndAppLevelCodesIgnoreTheServersMessage() {
        assertOwnText(R.string.error_database_unavailable, ApiError.http(503, "DATABASE_UNAVAILABLE", "Veritabanı kullanılamıyor."));
        assertOwnText(R.string.error_server_busy, ApiError.http(503, "AUTH_BUSY", "Daha sonra deneyin.", 1));
        assertOwnText(R.string.error_server, ApiError.http(500, "INTERNAL_ERROR", "İstek işlenemedi."));
        assertOwnText(R.string.error_app_mismatch, ApiError.http(404, "HTTP_ERROR", "Adres bulunamadı."));
        assertOwnText(R.string.error_app_mismatch, ApiError.http(400, "INVALID_JSON", "Geçerli JSON gereklidir."));
        assertOwnText(R.string.error_session_expired, ApiError.http(401, "INVALID_SESSION", "Geçerli oturum gereklidir."));
    }

    @Test
    public void rateLimitTextCarriesTheWaitFromRetryAfter() {
        assertOwnText(R.string.error_rate_limited_seconds, ApiError.http(429, "RATE_LIMITED", "Daha sonra yeniden deneyin.", 42));
        assertOwnText(R.string.error_rate_limited_minutes, ApiError.http(429, "RATE_LIMITED", "Daha sonra yeniden deneyin.", 600));
        // Missing Retry-After: no number is invented.
        assertOwnText(R.string.error_rate_limited, ApiError.http(429, "RATE_LIMITED", "Daha sonra yeniden deneyin."));

        assertEquals(42, ErrorMessages.waitAmount(42));
        assertEquals(90, ErrorMessages.waitAmount(90));
        assertEquals(2, ErrorMessages.waitAmount(91));
        assertEquals(10, ErrorMessages.waitAmount(600));
        assertEquals(11, ErrorMessages.waitAmount(601));
    }

    @Test
    public void responsesWithoutAContractBodyFallBackToTheStatus() {
        assertOwnText(R.string.error_server, ApiError.http(502, null, null));
        assertOwnText(R.string.error_rate_limited_seconds, ApiError.http(429, null, null, 30));
        assertOwnText(R.string.error_session_expired, ApiError.http(401, null, null));
        assertEquals(R.string.error_http, ErrorMessages.messageFor(ApiError.http(418, null, null)));
        assertFalse(ErrorMessages.usesServerMessage(ApiError.http(418, null, null)));
    }

    @Test
    public void blankServerMessageIsNeverShown() {
        assertFalse(ErrorMessages.usesServerMessage(ApiError.http(400, "INVALID_INPUT", "  ")));
        assertEquals(R.string.error_http, ErrorMessages.messageFor(ApiError.http(400, "INVALID_INPUT", "  ")));
    }

    @Test
    public void transportErrorsNeverUseAServerMessage() {
        assertFalse(ErrorMessages.usesServerMessage(ApiError.network()));
        assertFalse(ErrorMessages.usesServerMessage(ApiError.parse()));
        assertEquals(R.string.error_unexpected, ErrorMessages.messageFor(ApiError.unexpected()));
    }

    private static void assertOwnText(int expected, ApiError error) {
        assertFalse(error.getCode() + " must not show the server's message", ErrorMessages.usesServerMessage(error));
        assertEquals(error.getCode() + " " + error.getHttpStatus(), expected, ErrorMessages.messageFor(error));
    }
}
