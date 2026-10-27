package com.memorylane.core.config;

import java.util.Locale;

public final class ApiConfig {
    private ApiConfig() {
    }

    /**
     * Retrofit resolves relative paths against the base URL, so a missing trailing slash would
     * silently drop the last path segment. A wrong value in local.properties fails fast here
     * instead of turning every request into a confusing network error.
     */
    public static String normalizeBaseUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("memorylane.apiBaseUrl is empty");
        }
        String url = rawUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new IllegalArgumentException("memorylane.apiBaseUrl must start with http:// or https://: " + url);
        }
        return url.endsWith("/") ? url : url + "/";
    }
}
