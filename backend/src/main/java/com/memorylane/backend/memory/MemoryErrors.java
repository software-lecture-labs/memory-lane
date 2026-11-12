package com.memorylane.backend.memory;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@Order(-1)
@RestControllerAdvice(assignableTypes = MemoryController.class)
class MemoryErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return response(400, "INVALID_INPUT", error.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> parameter() {
        return response(400, "INVALID_INPUT", "Sayfa veya sayfa boyutu geçersiz.");
    }

    @ExceptionHandler(MemoryService.IdempotencyConflict.class)
    ResponseEntity<Map<String, String>> conflict() {
        return response(409, "IDEMPOTENCY_CONFLICT", "Bu anahtar farklı bir kayıt için kullanıldı.");
    }

    private ResponseEntity<Map<String, String>> response(int status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("code", code, "message", message));
    }
}
