package com.memorylane.backend.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@Configuration
public class AuthConfiguration {
    @Bean
    AuthRequestFilter authRequestFilter(
            AuthLimiter limiter, tools.jackson.databind.ObjectMapper mapper) {
        return new AuthRequestFilter(limiter, mapper);
    }

    @Bean
    Clock authClock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }
}
