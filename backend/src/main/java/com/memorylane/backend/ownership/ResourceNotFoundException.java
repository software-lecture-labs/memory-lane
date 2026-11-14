package com.memorylane.backend.ownership;

/**
 * A memory or photo that does not exist or belongs to another account. Both cases share one
 * response so a caller cannot learn whether someone else's id exists (API contract, 404).
 */
public class ResourceNotFoundException extends RuntimeException {
    private final String code;

    private ResourceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static ResourceNotFoundException memory() {
        return new ResourceNotFoundException("MEMORY_NOT_FOUND", "Anı bulunamadı.");
    }

    public static ResourceNotFoundException photo() {
        return new ResourceNotFoundException("PHOTO_NOT_FOUND", "Fotoğraf bulunamadı.");
    }

    public String code() {
        return code;
    }
}
