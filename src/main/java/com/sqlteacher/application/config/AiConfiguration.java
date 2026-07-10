package com.sqlteacher.application.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record AiConfiguration(
    URI ollamaBaseUrl,
    Duration healthTimeout
) {
    public AiConfiguration {
        Objects.requireNonNull(ollamaBaseUrl, "ollamaBaseUrl must not be null");
        Objects.requireNonNull(healthTimeout, "healthTimeout must not be null");
        if (healthTimeout.isZero() || healthTimeout.isNegative()) {
            throw new IllegalArgumentException("healthTimeout must be positive");
        }
    }

    public URI tagsEndpoint() {
        return ollamaBaseUrl.resolve("/api/tags");
    }
}
