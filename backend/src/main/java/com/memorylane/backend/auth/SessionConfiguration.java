package com.memorylane.backend.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionConfiguration {
    @Bean
    SessionAuthenticationFilter sessionAuthenticationFilter(
            ObjectProvider<AuthService> auth, tools.jackson.databind.ObjectMapper mapper) {
        return new SessionAuthenticationFilter(auth, mapper);
    }
}
