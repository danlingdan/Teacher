package com.sqlteacher.application.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record AiConfiguration(
    URI ollamaBaseUrl,
    Duration healthTimeout,
    Duration generateTimeout,
    String defaultModel
) {
    public AiConfiguration {
        Objects.requireNonNull(ollamaBaseUrl, "ollamaBaseUrl must not be null");
        Objects.requireNonNull(healthTimeout, "healthTimeout must not be null");
        Objects.requireNonNull(generateTimeout, "generateTimeout must not be null");
        Objects.requireNonNull(defaultModel, "defaultModel must not be null");
        if (healthTimeout.isZero() || healthTimeout.isNegative()) {
            throw new IllegalArgumentException("healthTimeout must be positive");
        }
        if (generateTimeout.isZero() || generateTimeout.isNegative()) {
            throw new IllegalArgumentException("generateTimeout must be positive");
        }
        if (defaultModel.isBlank()) {
            throw new IllegalArgumentException("defaultModel must not be blank");
        }
    }

    public URI tagsEndpoint() {
        return ollamaBaseUrl.resolve("/api/tags");
    }

    public URI generateEndpoint() {
        return ollamaBaseUrl.resolve("/api/generate");
    }
}
