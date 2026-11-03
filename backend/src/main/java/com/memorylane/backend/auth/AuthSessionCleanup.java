package com.memorylane.backend.auth;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Physical removal is separate from expiry checks, which reject tokens immediately. */
@Component
public class AuthSessionCleanup {
    private static final int BATCH_SIZE = 1000;
    private final AuthRepository repository;
    private final Clock clock;

    public AuthSessionCleanup(AuthRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${memorylane.auth.session-cleanup-delay:PT1H}",
            initialDelayString = "${memorylane.auth.session-cleanup-delay:PT1H}")
    public void cleanup() {
        repository.deleteExpiredSessions(clock.instant(), BATCH_SIZE);
    }
}
