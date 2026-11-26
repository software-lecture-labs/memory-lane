package com.memorylane.backend.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * ML-18: answers every error that never reached a controller's own handlers (unknown address,
 * unsupported method, Content-Type or Accept, a failure inside a servlet filter) with the
 * contract's single {@code {code,message}} body instead of Spring Boot's default
 * {@code {timestamp,status,error,path}} one. Replaces Boot's BasicErrorController.
 *
 * <p>The body is written directly rather than returned, because content negotiation is exactly
 * what failed for a 406: a returned object would be refused again and leave the body empty.
 */
@RestController
public class ApiErrorController implements ErrorController {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    private final ObjectMapper mapper;

    public ApiErrorController(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    record ApiError(String code, String message) {}

    static ApiError describe(int status) {
        return switch (status) {
            case 400 -> new ApiError("INVALID_INPUT", "İstek geçersiz.");
            case 404 -> new ApiError("HTTP_ERROR", "Adres bulunamadı.");
            case 405 -> new ApiError("HTTP_ERROR", "HTTP yöntemi desteklenmiyor.");
            case 406 -> new ApiError("HTTP_ERROR", "Yanıt yalnız JSON olarak verilebilir.");
            case 413 -> new ApiError("REQUEST_TOO_LARGE", "İstek gövdesi çok büyük.");
            case 415 -> new ApiError("HTTP_ERROR", "İstek içerik türü desteklenmiyor.");
            default -> status >= 500
                    ? new ApiError("INTERNAL_ERROR", "İstek işlenemedi.")
                    : new ApiError("HTTP_ERROR", "İstek işlenemedi.");
        };
    }

    @RequestMapping("${server.error.path:${error.path:/error}}")
    void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code ? code : 500;
        if (status < 400) status = 500;
        if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure && status >= 500) {
            // Method and path only: the query string and headers may carry a token.
            log.error("Unexpected error during {} {}", request.getMethod(),
                    request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI), failure);
        }
        if (response.isCommitted()) return;

        // Keeps the Allow header Spring set for a 405; drops any other partial output.
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        ApiError error = describe(status);
        mapper.writeValue(response.getOutputStream(), Map.of("code", error.code(), "message", error.message()));
    }
}
