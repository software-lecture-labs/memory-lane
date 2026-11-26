package com.memorylane.backend.memory;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Turns the memory endpoints' refusals into the contract's {@code {code,message}} body: field
 * and parameter errors (400 INVALID_INPUT), a reused create key (409), and the PUT preconditions
 * (428 PRECONDITION_REQUIRED, 400 INVALID_PRECONDITION, 412 MEMORY_MODIFIED). Missing and foreign
 * memories (404) are answered by OwnershipErrors, outages and unexpected errors by AuthErrors.
 */
@Order(-1)
@RestControllerAdvice(assignableTypes = MemoryController.class)
class MemoryErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return response(400, "INVALID_INPUT", error.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> parameter(MethodArgumentTypeMismatchException error) {
        return response(400, "INVALID_INPUT", "id".equals(error.getName())
                ? "Anı kimliği geçersiz." : "Sayfa veya sayfa boyutu geçersiz.");
    }

    @ExceptionHandler(MemoryService.IdempotencyConflict.class)
    ResponseEntity<Map<String, String>> conflict() {
        return response(409, "IDEMPOTENCY_CONFLICT", "Bu anahtar farklı bir kayıt için kullanıldı.");
    }

    @ExceptionHandler(MemoryPrecondition.Required.class)
    ResponseEntity<Map<String, String>> preconditionRequired() {
        return response(428, "PRECONDITION_REQUIRED",
                "Güncelleme için If-Match başlığı gerekir. Önce anının güncel halini alın.");
    }

    @ExceptionHandler(MemoryPrecondition.Invalid.class)
    ResponseEntity<Map<String, String>> invalidPrecondition() {
        return response(400, "INVALID_PRECONDITION",
                "If-Match başlığı bu anının tek ve tırnaklı etiketi olmalıdır.");
    }

    @ExceptionHandler(MemoryPrecondition.Modified.class)
    ResponseEntity<Map<String, String>> modified() {
        return response(412, "MEMORY_MODIFIED",
                "Anı başka bir işlemle değişti. Güncel halini alıp değişikliklerinizi kontrol edin.");
    }

    private ResponseEntity<Map<String, String>> response(int status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("code", code, "message", message));
    }
}
