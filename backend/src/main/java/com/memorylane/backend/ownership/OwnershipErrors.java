package com.memorylane.backend.ownership;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

// Highest precedence so controller-specific catch-all handlers cannot turn this into a 500.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class OwnershipErrors {
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ResourceNotFoundException error) {
        return ResponseEntity.status(404)
                .header("Cache-Control", "no-store")
                .body(Map.of("code", error.code(), "message", error.getMessage()));
    }
}
