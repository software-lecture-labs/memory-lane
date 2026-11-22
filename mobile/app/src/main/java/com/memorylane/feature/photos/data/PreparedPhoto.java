package com.memorylane.feature.photos.data;

import java.io.File;

/**
 * A selected photo that is ready to send: a private copy in the app's cache, its type, and the
 * Idempotency-Key chosen once at selection. Retries must reuse both, otherwise the server sees new
 * bytes under the same key (409) or a new key for the same photo (a duplicate). The user's gallery
 * original is never modified; only {@link #file()} is deleted when it is no longer needed.
 */
public record PreparedPhoto(String key, File file, String contentType) {}
