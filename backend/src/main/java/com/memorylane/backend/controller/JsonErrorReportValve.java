package com.memorylane.backend.controller;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * ML-18: Tomcat rejects some requests before Spring ever sees them (a path containing {@code %00},
 * a malformed request line) and its default ErrorReportValve answers those with an HTML page.
 * This valve replaces it and writes the same {@code {code,message}} body as
 * {@link ApiErrorController}, so even these answers follow the contract.
 *
 * <p>It only writes when nothing else did: errors Spring handled already have a body, and those
 * responses are left untouched.
 */
public class JsonErrorReportValve extends ErrorReportValve {
    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        ApiErrorController.ApiError error = ApiErrorController.describe(status);
        try {
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            PrintWriter writer = response.getReporter();
            if (writer != null) {
                // Codes and messages are fixed texts without quotes or backslashes, so no escaping is needed.
                writer.write("{\"code\":\"" + error.code() + "\",\"message\":\"" + error.message() + "\"}");
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException ignored) {
            // The client is gone or the response was already committed; nothing more can be sent.
        }
    }
}
