package com.memorylane.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ExternalLocalConfigTest {
    // Config files are loaded into the Environment before any bean exists, so an empty source
    // checks the same loading rules without a DataSource, Flyway, JPA or Docker.
    @Configuration(proxyBeanMethods = false)
    static class NoBeans {
    }

    @TempDir Path directory;
    @Test void externalLocalSettingsLoadOnlyWithLocalProfile() throws Exception {
        Files.writeString(directory.resolve("application-local.properties"), "memorylane.test-marker=external-local\n");
        String location = "--spring.config.additional-location=" + directory.toUri();
        try (var context = new SpringApplicationBuilder(NoBeans.class)
                .web(WebApplicationType.NONE).run(location, "--spring.profiles.active=local")) {
            assertEquals("external-local", context.getEnvironment().getProperty("memorylane.test-marker"));
        }
        try (var context = new SpringApplicationBuilder(NoBeans.class)
                .web(WebApplicationType.NONE).run(location, "--spring.profiles.active=default")) {
            assertNull(context.getEnvironment().getProperty("memorylane.test-marker"));
        }
    }
}
