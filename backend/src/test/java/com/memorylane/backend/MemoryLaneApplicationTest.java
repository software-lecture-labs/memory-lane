package com.memorylane.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MemoryLaneApplicationTest {
    @Test
    void applicationStartsWithEmbeddedServer() {
        // Context startup verifies that HTTP server and application wiring are valid.
    }
}
