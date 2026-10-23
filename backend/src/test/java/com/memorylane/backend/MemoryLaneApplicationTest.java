package com.memorylane.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@RequiresDocker
class MemoryLaneApplicationTest {
    @Value("${local.server.port}")
    int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> SharedPostgres.container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> SharedPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> SharedPostgres.container().getPassword());
    }

    @Test
    void healthEndpointReportsDatabaseUpOnRunningServer() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"database\":\"UP\""), response.body());
    }
}
