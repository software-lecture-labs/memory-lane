package com.memorylane.backend.auth;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthErrors {
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
    public ResponseEntity<Map<String, String>> database() {
        return auth(new AuthFailure(503, "DATABASE_UNAVAILABLE", "Veritabanı kullanılamıyor."));
    }

    @ExceptionHandler(Exception.class)
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
