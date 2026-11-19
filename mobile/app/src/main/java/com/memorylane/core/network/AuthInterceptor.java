package com.memorylane.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.memorylane.core.config.ApiConfig;
import com.memorylane.core.session.SessionStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Applies the in-memory session to every protected API call, so endpoints and repositories no
 * longer pass the token around, and ends the session when the server rejects it.
 *
 * <p>Protected means {@code api/v1/**} on the configured server, except
 * {@code api/v1/auth/login} and {@code api/v1/auth/register} (docs/api-contract.md: every other
 * {@code /api/v1} call needs a Bearer token). {@code api/health} is outside {@code api/v1}. The token
 * is never sent to another scheme, host or port, even if a full URL is passed to an endpoint.
 *
 * <ul>
 *   <li>An {@code Authorization} header already on the request is kept as it is.</li>
 *   <li>A 401 on a protected call expires the session only if the rejected token is still the
 *       current one ({@link SessionStore#expire}); the login endpoint's 401 means wrong
 *       credentials and never touches the session.</li>
 * </ul>
 */
public final class AuthInterceptor implements Interceptor {
    static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int UNAUTHORIZED = 401;

    private final SessionStore sessionStore;
    private final HttpUrl base;
    private final List<String> basePath;

    public AuthInterceptor(@NonNull SessionStore sessionStore, @NonNull String baseUrl) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.base = HttpUrl.get(ApiConfig.normalizeBaseUrl(baseUrl));
        this.basePath = segments(base);
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        if (!isProtected(request.url())) {
            return chain.proceed(request);
        }
        String sentToken;
        String existing = request.header(AUTHORIZATION);
        if (existing != null) {
            sentToken = bearerToken(existing);
        } else {
            sentToken = sessionStore.token();
            if (sentToken != null) {
                request = request.newBuilder().header(AUTHORIZATION, BEARER_PREFIX + sentToken).build();
            }
        }
        Response response = chain.proceed(request);
        if (response.code() == UNAUTHORIZED && sentToken != null) {
            sessionStore.expire(sentToken);
        }
        return response;
    }

    private boolean isProtected(HttpUrl url) {
        if (!url.scheme().equals(base.scheme()) || !url.host().equals(base.host())
                || url.port() != base.port()) {
            return false;
        }
        List<String> path = segments(url);
        if (path.size() < basePath.size() || !path.subList(0, basePath.size()).equals(basePath)) {
            return false;
        }
        List<String> relative = path.subList(basePath.size(), path.size());
        if (relative.size() < 2 || !relative.get(0).equals("api") || !relative.get(1).equals("v1")) {
            return false;
        }
        boolean publicAuth = relative.size() == 4 && relative.get(2).equals("auth")
                && (relative.get(3).equals("login") || relative.get(3).equals("register"));
        return !publicAuth;
    }

    /** Decoded path segments without the empty segment a trailing slash leaves behind. */
    private static List<String> segments(HttpUrl url) {
        List<String> result = new ArrayList<>(url.pathSegments());
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    /** Token of a {@code Bearer} header (scheme is case-insensitive); null for any other value. */
    @Nullable
    private static String bearerToken(String header) {
        String value = header.trim();
        if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = value.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
