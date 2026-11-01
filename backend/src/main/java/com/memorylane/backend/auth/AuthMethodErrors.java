package com.memorylane.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * A 405 is raised before a controller is selected, so controller-scoped advice cannot handle it.
 */
@Component
public class AuthMethodErrors implements HandlerExceptionResolver, Ordered {
    private final ObjectMapper mapper;

    public AuthMethodErrors(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        if (!path.startsWith("/api/v1/auth/")
                || !(exception instanceof HttpRequestMethodNotSupportedException methodError)) {
            return null;
        }
        response.setStatus(405);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        if (methodError.getSupportedMethods() != null) {
            response.setHeader("Allow", String.join(", ", methodError.getSupportedMethods()));
        }
        try {
            mapper.writeValue(
                    response.getOutputStream(),
                    new AuthFailure(405, "HTTP_ERROR", "HTTP yöntemi desteklenmiyor.").body());
            return new ModelAndView();
        } catch (IOException writeFailure) {
            // Let the remaining resolvers handle a response that could not be written.
            return null;
        }
    }
}
