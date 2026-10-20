package com.memorylane.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ExternalLocalConfigTest {
    @TempDir Path directory;
    @Test void externalLocalSettingsLoadOnlyWithLocalProfile() throws Exception {
        Files.writeString(directory.resolve("application-local.properties"), "memorylane.test-marker=external-local\n");
        String location = "--spring.config.additional-location=" + directory.toUri();
        try (var context = new SpringApplicationBuilder(MemoryLaneApplication.class)
                .web(WebApplicationType.NONE).run(location, "--spring.profiles.active=local")) {
            assertEquals("external-local", context.getEnvironment().getProperty("memorylane.test-marker"));
        }
        try (var context = new SpringApplicationBuilder(MemoryLaneApplication.class)
                .web(WebApplicationType.NONE).run(location, "--spring.profiles.active=default")) {
            assertNull(context.getEnvironment().getProperty("memorylane.test-marker"));
        }
    }
}
