package com.memorylane.backend.auth;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Failures the client only sees as a generic 500/503 must leave a trace in the server log, and
 * that trace must never contain the session token or the query string (PR #16 review).
 */
@ExtendWith(OutputCaptureExtension.class)
class ErrorLoggingTest {
    private static final String TOKEN = "secret-session-token";

    private final AuthErrors errors = new AuthErrors();

    @Test
    void unexpectedErrorIsLoggedWithRequestAndStackTraceButWithoutSecrets(CapturedOutput output) {
        var response = errors.unexpected(new IOException("No space left on device"),
                request("POST", "/api/v1/memories/42/photos"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals("INTERNAL_ERROR", response.getBody().get("code"));
        assertTrue(output.getOut().contains("ERROR"), output.getOut());
        assertTrue(output.getOut().contains("Unexpected error during POST /api/v1/memories/42/photos"));
        assertTrue(output.getOut().contains("java.io.IOException: No space left on device"));
        assertTrue(output.getOut().contains("\tat "), "stack trace is logged");
        assertNoSecrets(output);
    }

    @Test
    void databaseOutageIsAWarningWithoutStackTrace(CapturedOutput output) {
        var response = errors.database(new DataAccessResourceFailureException("Connection refused"),
                request("GET", "/api/v1/memories"));

        assertEquals(503, response.getStatusCode().value());
        assertTrue(output.getOut().contains("WARN"), output.getOut());
        assertTrue(output.getOut().contains("Database unavailable during GET /api/v1/memories: Connection refused"));
        assertFalse(output.getOut().contains("ERROR"));
        assertFalse(output.getOut().contains("\tat "));
        assertNoSecrets(output);
    }

    @Test
    void clientMistakesAreAnsweredButNotLoggedAsErrors(CapturedOutput output) {
        var response = errors.unexpected(new HttpRequestMethodNotSupportedException("PATCH"),
                request("PATCH", "/api/v1/memories"));

        assertEquals(405, response.getStatusCode().value());
        assertFalse(output.getOut().contains("ERROR"), output.getOut());
    }

    @Test
    void clientThatDisconnectedIsNotAServerError(CapturedOutput output) {
        var response = errors.unexpected(new ClientAbortException("Broken pipe"),
                request("GET", "/api/v1/memories/42/photos/7/content"));

        assertNull(response, "nothing can be written to a closed connection");
        assertFalse(output.getOut().contains("ERROR"), output.getOut());
    }

    @Test
    void sessionFilterLogsDatabaseOutageBeforeAnsweringWith503(CapturedOutput output) throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.me(any())).thenThrow(new DataAccessResourceFailureException("Connection refused"));
        var filter = new SessionAuthenticationFilter(
                new StaticListableBeanFactory(Map.of("auth", auth)).getBeanProvider(AuthService.class),
                new tools.jackson.databind.ObjectMapper());
        var request = request("GET", "/api/v1/memories");
        request.setServletPath("/api/v1/memories");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(503, response.getStatus());
        assertTrue(output.getOut().contains(
                "Database unavailable while checking the session for GET /api/v1/memories: Connection refused"),
                output.getOut());
        assertNoSecrets(output);
    }

    private static MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setQueryString("token=" + TOKEN);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private static void assertNoSecrets(CapturedOutput output) {
        assertFalse(output.getOut().contains(TOKEN), "token must not be logged");
        assertFalse(output.getOut().contains("token="), "query string must not be logged");
    }
}
