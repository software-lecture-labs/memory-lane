package com.memorylane.backend.photo;

/** A photo request the contract rejects; carries the HTTP status and error code it maps to. */
class PhotoRejected extends RuntimeException {
    private final int status;
    private final String code;

    private PhotoRejected(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static PhotoRejected invalid(String message) {
        return new PhotoRejected(400, "INVALID_INPUT", message);
    }

    static PhotoRejected tooLarge() {
        return new PhotoRejected(413, "FILE_TOO_LARGE", "Fotoğraf en fazla 5 MB olabilir.");
    }

    static PhotoRejected unsupportedType() {
        return new PhotoRejected(415, "UNSUPPORTED_PHOTO_TYPE", "Yalnız JPEG veya PNG fotoğraf yüklenebilir.");
    }

    static PhotoRejected limitReached() {
        return new PhotoRejected(409, "PHOTO_LIMIT_REACHED", "Bir anıya en fazla 5 fotoğraf eklenebilir.");
    }

    static PhotoRejected keyConflict() {
        return new PhotoRejected(409, "IDEMPOTENCY_CONFLICT", "Bu anahtar farklı bir fotoğraf için kullanıldı.");
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }
}
