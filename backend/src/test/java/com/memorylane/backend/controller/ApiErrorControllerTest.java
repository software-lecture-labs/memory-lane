package com.memorylane.backend.controller;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@ExtendWith(OutputCaptureExtension.class)
class ApiErrorControllerTest {
    private static final String TOKEN = "secret-session-token";
    private final ApiErrorController controller = new ApiErrorController(JsonMapper.builder().build());

    @Test
    void failureInsideAFilterIsLoggedWithoutSecretsAndAnsweredGenerically(CapturedOutput output) throws Exception {
        var request = errorRequest(500, "/api/v1/memories");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("/srv/storage/photos/x.jpg kayıp"));
        request.setQueryString("token=" + TOKEN);
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();

        controller.error(request, response);

        assertEquals(500, response.getStatus());
        String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"INTERNAL_ERROR\""), body);
        assertFalse(body.contains("/srv/storage"), "the exception's file path never reaches the client");
        assertEquals("no-store", response.getHeader("Cache-Control"));

        assertTrue(output.getOut().contains("Unexpected error during GET /api/v1/memories"), output.getOut());
        assertTrue(output.getOut().contains("IllegalStateException"), "the cause is in the log");
        assertFalse(output.getOut().contains(TOKEN), "token must not be logged");
        assertFalse(output.getOut().contains("token="), "query string must not be logged");
    }

    @Test
    void clientErrorsAreNotLoggedAsServerFaults(CapturedOutput output) throws IOException {
        var response = new MockHttpServletResponse();
        controller.error(errorRequest(404, "/api/v1/yok"), response);

        assertEquals(404, response.getStatus());
        assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("\"HTTP_ERROR\""));
        assertFalse(output.getOut().contains("ERROR"), output.getOut());
    }

    @Test
    void everyStatusHasAContractCode() {
        assertEquals("INVALID_INPUT", ApiErrorController.describe(400).code());
        assertEquals("HTTP_ERROR", ApiErrorController.describe(404).code());
        assertEquals("HTTP_ERROR", ApiErrorController.describe(405).code());
        assertEquals("HTTP_ERROR", ApiErrorController.describe(406).code());
        assertEquals("REQUEST_TOO_LARGE", ApiErrorController.describe(413).code());
        assertEquals("HTTP_ERROR", ApiErrorController.describe(415).code());
        assertEquals("HTTP_ERROR", ApiErrorController.describe(418).code());
        assertEquals("INTERNAL_ERROR", ApiErrorController.describe(500).code());
        assertEquals("INTERNAL_ERROR", ApiErrorController.describe(503).code());
    }

    private static MockHttpServletRequest errorRequest(int status, String uri) {
        var request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, uri);
        return request;
    }
}
