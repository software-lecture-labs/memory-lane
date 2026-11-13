package com.memorylane.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Requires a valid session for every /api/v1 request outside /api/v1/auth, before any controller
 * runs. A new endpoint is therefore protected even if it forgets to check the session, and
 * unauthenticated callers get 401 for unknown paths too, so routes are not revealed.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    static final String USER_ID = SessionAuthenticationFilter.class.getName() + ".userId";

    // Filters are created while the web server starts, before JPA is ready, so AuthService is
    // looked up on the first request instead of at construction.
    private final ObjectProvider<AuthService> auth;
    private final tools.jackson.databind.ObjectMapper mapper;

    public SessionAuthenticationFilter(
            ObjectProvider<AuthService> auth, tools.jackson.databind.ObjectMapper mapper) {
        this.auth = auth;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Same normalisation as AuthRequestFilter; auth endpoints check their own headers.
        String path = request.getServletPath().replaceAll(";[^/]*", "");
        boolean api = path.equals("/api/v1") || path.startsWith("/api/v1/");
        return !api || path.startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        UUID userId;
        try {
            userId = auth.getObject().me(request.getHeader("Authorization")).id();
        } catch (AuthFailure failure) {
            reject(response, failure);
            return;
        } catch (DataAccessResourceFailureException
                | TransientDataAccessResourceException
                | CannotCreateTransactionException e) {
            reject(response, new AuthFailure(503, "DATABASE_UNAVAILABLE", "Veritabanı kullanılamıyor."));
            return;
        }
        request.setAttribute(USER_ID, userId);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, AuthFailure failure) throws IOException {
        response.setStatus(failure.status);
        response.setHeader("Cache-Control", "no-store");
        if (failure.code.equals("INVALID_SESSION")) response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), failure.body());
    }
}
