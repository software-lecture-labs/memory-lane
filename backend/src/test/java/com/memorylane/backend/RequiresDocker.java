package com.memorylane.backend;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Skips a Docker-backed test only on a developer machine without Docker. On CI (CI env variable set,
 * as on GitHub Actions) the test always runs, so a missing Docker fails the build instead of hiding it.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresDocker.Condition.class)
public @interface RequiresDocker {
    class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (System.getenv("CI") != null) {
                return ConditionEvaluationResult.enabled("CI requires Docker-backed tests to run");
            }
            if (DockerClientFactory.instance().isDockerAvailable()) {
                return ConditionEvaluationResult.enabled("Docker is available");
            }
            return ConditionEvaluationResult.disabled("Docker is not available; skipped locally (never skipped on CI)");
        }
    }
}
