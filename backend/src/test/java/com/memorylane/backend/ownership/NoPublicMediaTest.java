package com.memorylane.backend.ownership;

import static org.junit.jupiter.api.Assertions.*;

import com.memorylane.backend.RequiresDocker;
import com.memorylane.backend.SharedPostgres;
import com.memorylane.backend.TestApi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Media must only be served by protected endpoints (ML-29). Spring Boot would otherwise publish
 * anything under classpath static/ or public/ to everyone; src/test/resources/static/public-probe.txt
 * stands in for a file placed there by mistake.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
class NoPublicMediaTest {
    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        r.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        r.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
    }

    @Test
    void filesInStaticFoldersAreNotServed() throws Exception {
        assertEquals(404, new TestApi(port).get("/public-probe.txt", null).statusCode());
    }
}
