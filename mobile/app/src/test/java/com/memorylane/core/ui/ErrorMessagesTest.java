package com.memorylane.core.ui;

import static org.junit.Assert.assertEquals;

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
}
