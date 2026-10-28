package com.memorylane.backend.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;
    private final AuthLimiter limiter;

    public AuthController(AuthService service, AuthLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    // Reject unknown fields and non-string values before calling the service.
    private String field(Map<String, Object> body, String field) {
        if (body.size() != 2
                || !body.containsKey("email")
                || !body.containsKey("password")
                || !(body.get(field) instanceof String)) throw AuthService.invalidInput();
        return (String) body.get(field);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthService.UserResponse> register(
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201)
                .body(service.register(field(body, "email"), field(body, "password")));
    }

    @PostMapping("/login")
    public AuthService.LoginResponse login(@RequestBody Map<String, Object> body) {
        String email = AuthService.email(field(body, "email"));
        String password = field(body, "password");
        return limiter.login(email, () -> service.login(email, password));
    }

    @GetMapping("/me")
    public AuthService.UserResponse me(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.me(authorization);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        service.logout(authorization);
        return ResponseEntity.noContent().build();
    }
}
