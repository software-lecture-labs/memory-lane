package com.memorylane.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@Configuration
@EnableScheduling
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
    PasswordEncoder passwordEncoder(
            @Value("${memorylane.auth.max-concurrent-hashes:2}") int maxConcurrent) {
        return new BoundedPasswordEncoder(
                new Argon2PasswordEncoder(16, 32, 1, 19456, 2), maxConcurrent);
    }
}
