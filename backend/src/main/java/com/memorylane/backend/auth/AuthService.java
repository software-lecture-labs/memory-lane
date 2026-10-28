package com.memorylane.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final AuthRepository repository;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();

    public record UserResponse(UUID id, String email) { }
    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) { }
    public AuthService(AuthRepository repository, PasswordEncoder encoder, Clock clock) {
        this.repository = repository; this.encoder = encoder; this.clock = clock;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }
    static String email(String email) {
        if (email == null) throw invalidInput();
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) throw invalidInput();
        return normalized;
    }
    static void password(String password) {
        if (password == null) throw invalidInput();
        int length = password.codePointCount(0, password.length());
        if (length < 15 || length > 128) throw invalidInput();
    }
    static AuthFailure invalidInput() { return new AuthFailure(400, "INVALID_INPUT", "Geçerli e-posta ve 15–128 karakter parola gereklidir."); }

    @Transactional
    public UserResponse register(String email, String password) {
        String normalized = email(email); password(password);
        UUID id = UUID.randomUUID();
        if (!repository.insert(id, normalized, encoder.encode(password)))
            throw new AuthFailure(409, "EMAIL_IN_USE", "Bu e-posta zaten kayıtlı.");
        return new UserResponse(id, normalized);
    }
    @Transactional
    public LoginResponse login(String normalized, String password) {
        if (password == null || password.codePointCount(0, password.length()) > 128) throw invalidInput();
        var account = repository.find(normalized);
        boolean matches = encoder.matches(password, account.map(AuthRepository.Account::passwordHash).orElse(dummyHash));
        if (account.isEmpty() || !matches)
            throw new AuthFailure(401, "INVALID_CREDENTIALS", "E-posta veya parola hatalı.");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant(); Instant expires = now.plus(Duration.ofHours(24));
        var user = account.get(); repository.session(user.id(), digest(token), now, expires);
        return new LoginResponse(token, "Bearer", expires, new UserResponse(user.id(), user.email()));
    }
    @Transactional(readOnly=true)
    public UserResponse me(String authorization) {
        var user = repository.authenticated(digest(token(authorization)), clock.instant())
                .orElseThrow(AuthService::invalidSession);
        return new UserResponse(user.id(), user.email());
    }
    @Transactional
    public void logout(String authorization) { repository.revoke(digest(token(authorization)), clock.instant()); }
    static String token(String authorization) {
        if (authorization == null || !authorization.matches("(?i)Bearer [A-Za-z0-9_-]{43}")) throw invalidSession();
        String token = authorization.substring(7);
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(token)) throw invalidSession();
        } catch (IllegalArgumentException e) { throw invalidSession(); }
        return token;
    }
    static AuthFailure invalidSession() { return new AuthFailure(401, "INVALID_SESSION", "Geçerli oturum gereklidir."); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
