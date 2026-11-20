package com.memorylane.feature.photos.data;

/** A picked photo that cannot be turned into an upload; the reason decides the message shown. */
public final class PhotoPreparationException extends Exception {
    public enum Reason {
        /** The phone cannot decode it (e.g. HEIC on Android 8). */
        UNSUPPORTED,
        /** Could not be read from the gallery or written to the cache. */
        UNREADABLE
    }

    private final Reason reason;

    public PhotoPreparationException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
