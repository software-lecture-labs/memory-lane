package com.memorylane.core.network;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * A response body together with its {@code ETag} header (docs/api-contract.md: the memory detail
 * GET is the only source of the tag a later PUT sends in {@code If-Match}). The tag is opaque:
 * kept exactly as received, quotes included, and never parsed by the app.
 */
public record Tagged<T>(@NonNull T value, @NonNull String etag) {
    public Tagged {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(etag, "etag");
    }
}
