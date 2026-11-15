package com.memorylane.backend.photo;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Turns photo errors into the contract's {code,message} body. Runs before the shared AuthErrors
 * handlers (Order -1), which still answer database outages (503) and unexpected errors (500) for
 * this controller in the one place ADR-0003 asks for. 404s come from OwnershipErrors.
 */
@Order(-1)
@RestControllerAdvice(assignableTypes = PhotoController.class)
class PhotoErrors {
    @ExceptionHandler(PhotoRejected.class)
    ResponseEntity<Map<String, String>> rejected(PhotoRejected error) {
        return response(error.status(), error.code(), error.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, String>> invalidId() {
        return response(400, "INVALID_INPUT", "Anı veya fotoğraf kimliği geçersiz.");
    }

    static ResponseEntity<Map<String, String>> response(int status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("code", code, "message", message));
    }

    /**
     * Requests far above 5 MB are stopped by the servlet multipart limit before any controller is
     * chosen, so a controller-scoped advice never sees them. This one is global but only handles
     * that single exception, giving it the same 413 body as the in-code size check.
     */
    @RestControllerAdvice
    static class UploadSizeErrors {
        @ExceptionHandler(MaxUploadSizeExceededException.class)
        ResponseEntity<Map<String, String>> tooLarge() {
            PhotoRejected error = PhotoRejected.tooLarge();
            return response(error.status(), error.code(), error.getMessage());
        }
    }
}
