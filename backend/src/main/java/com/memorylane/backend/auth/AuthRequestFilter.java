package com.memorylane.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bound auth bodies before JSON parsing, including requests without Content-Length. */
public class AuthRequestFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 8 * 1024;
    private final AuthLimiter limiter;
    private final tools.jackson.databind.ObjectMapper mapper;

    public AuthRequestFilter(AuthLimiter limiter, tools.jackson.databind.ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    private String path(HttpServletRequest request) {
        // Servlet path is decoded; strip path parameters just as Spring's handler mapping does.
        return request.getServletPath().replaceAll(";[^/]*", "");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !path(request).startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        String path = path(request);
        try {
            if (request.getMethod().equals("POST")
                    && (path.endsWith("/register") || path.endsWith("/login"))) {
                // Forwarded headers are deliberately not trusted.
                limiter.ip(request.getRemoteAddr());
                byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
                if (bytes.length > MAX_BODY_BYTES)
                    throw new AuthFailure(
                            413, "REQUEST_TOO_LARGE", "İstek gövdesi en fazla 8 KiB olabilir.");
                var wrapped =
                        new HttpServletRequestWrapper(request) {
                            @Override
                            public jakarta.servlet.ServletInputStream getInputStream() {
                                var input = new java.io.ByteArrayInputStream(bytes);
                                return new jakarta.servlet.ServletInputStream() {
                                    @Override
                                    public int read() {
                                        return input.read();
                                    }

                                    @Override
                                    public boolean isFinished() {
                                        return input.available() == 0;
                                    }

                                    @Override
                                    public boolean isReady() {
                                        return true;
                                    }

                                    @Override
                                    public void setReadListener(
                                            jakarta.servlet.ReadListener listener) {
                                        throw new UnsupportedOperationException();
                                    }
                                };
                            }
                        };
                chain.doFilter(wrapped, response);
                return;
            }
            chain.doFilter(request, response);
        } catch (AuthFailure e) {
            response.setStatus(e.status);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            if (e.retryAfter > 0) response.setHeader("Retry-After", Long.toString(e.retryAfter));
            mapper.writeValue(response.getOutputStream(), e.body());
        }
    }
}
