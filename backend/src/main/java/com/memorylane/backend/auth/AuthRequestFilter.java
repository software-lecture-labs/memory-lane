package com.memorylane.backend.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound auth bodies before JSON parsing, including requests without Content-Length. */
public class AuthRequestFilter extends OncePerRequestFilter {
    private final AuthLimiter limiter;
    public AuthRequestFilter(AuthLimiter limiter) { this.limiter = limiter; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/auth/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI();
        try {
            if (request.getMethod().equals("POST") && (path.endsWith("/register") || path.endsWith("/login"))) {
                limiter.ip(request.getRemoteAddr()); // Forwarded headers are deliberately not trusted.
                byte[] bytes = request.getInputStream().readNBytes(8193);
                if (bytes.length > 8192) throw new AuthFailure(413,"REQUEST_TOO_LARGE","İstek gövdesi en fazla 8 KiB olabilir.");
                var wrapped = new HttpServletRequestWrapper(request) {
                    @Override public jakarta.servlet.ServletInputStream getInputStream() {
                        var input = new java.io.ByteArrayInputStream(bytes);
                        return new jakarta.servlet.ServletInputStream() {
                            @Override public int read() { return input.read(); }
                            @Override public boolean isFinished() { return input.available() == 0; }
                            @Override public boolean isReady() { return true; }
                            @Override public void setReadListener(jakarta.servlet.ReadListener listener) { throw new UnsupportedOperationException(); }
                        };
                    }
                };
                chain.doFilter(wrapped, response); return;
            }
            chain.doFilter(request, response);
        } catch (AuthFailure e) {
            response.setStatus(e.status); response.setContentType("application/json"); response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            if (e.retryAfter > 0) response.setHeader("Retry-After", Long.toString(e.retryAfter));
            response.getWriter().write("{\"code\":\"" + e.code + "\",\"message\":\"" + e.getMessage() + "\"}");
        }
    }
}
