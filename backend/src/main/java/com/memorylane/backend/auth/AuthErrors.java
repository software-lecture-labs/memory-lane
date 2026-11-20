package com.memorylane.backend.auth;

import com.memorylane.backend.memory.MemoryController;
import com.memorylane.backend.photo.PhotoController;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.DisconnectedClientHelper;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {AuthController.class, MemoryController.class, PhotoController.class})
public class AuthErrors {
    private static final Logger log = LoggerFactory.getLogger(AuthErrors.class);

    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String, String>> auth(AuthFailure failure) {
        var response = ResponseEntity.status(failure.status).header("Cache-Control", "no-store");
        if (failure.code.equals("INVALID_SESSION")) response.header("WWW-Authenticate", "Bearer");
        if (failure.retryAfter > 0)
            response.header("Retry-After", Long.toString(failure.retryAfter));
        return response.body(failure.body());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> json() {
        return auth(new AuthFailure(400, "INVALID_JSON", "Geçerli JSON gereklidir."));
    }

    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        TransientDataAccessResourceException.class,
        CannotCreateTransactionException.class
    })
    public ResponseEntity<Map<String, String>> database(Exception exception, HttpServletRequest request) {
        // An outage is expected to happen and is already answered with 503; one line without the
        // stack trace keeps the log readable when every request fails the same way.
        log.warn("Database unavailable during {}: {}", describe(request), rootCause(exception));
        return database();
    }

    public ResponseEntity<Map<String, String>> database() {
        return auth(AuthFailure.databaseUnavailable());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception exception, HttpServletRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(exception)) {
            // The client went away (e.g. closed a photo download). Nothing can be sent back and it is
            // not a server fault, so it must not show up as an error.
            log.debug("Client disconnected during {}", describe(request));
            return null;
        }
        var response = unexpected(exception);
        if (response.getStatusCode().is5xxServerError()) {
            // The client only gets a generic body, so this line is the only trace of what broke.
            log.error("Unexpected error during {}", describe(request), exception);
        }
        return response;
    }

    /**
     * Message of the innermost cause. Spring wraps a refused connection a few times ("Could not open
     * JPA EntityManager for transaction"), and only the innermost message says what actually went
     * wrong, e.g. "Connection refused". Without the stack trace that line has to carry it.
     */
    static String rootCause(Throwable exception) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(exception);
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    // Method and path only: headers, query strings and bodies may carry tokens or passwords.
    private static String describe(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }

    public ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        int status =
                exception instanceof HttpMediaTypeNotSupportedException
                        ? 415
                        : exception instanceof HttpRequestMethodNotSupportedException ? 405 : 500;
        return auth(
                new AuthFailure(
                        status,
                        status == 500 ? "INTERNAL_ERROR" : "HTTP_ERROR",
                        "İstek işlenemedi."));
    }
}
