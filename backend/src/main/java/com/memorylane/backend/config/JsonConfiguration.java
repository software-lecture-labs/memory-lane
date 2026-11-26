package com.memorylane.backend.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadFeature;

/**
 * ML-18: a request body that names the same field twice, e.g. {"title":"a","title":"b"}, is
 * rejected as unreadable JSON (400 INVALID_JSON) instead of silently keeping the last value. The
 * client and the backend could otherwise disagree about which value was saved.
 */
@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {
    @Bean
    JsonMapperBuilderCustomizer rejectDuplicateFields() {
        return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
}
