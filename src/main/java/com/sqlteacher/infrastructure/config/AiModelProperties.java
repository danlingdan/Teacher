package com.sqlteacher.infrastructure.config;

import java.net.URI;
import java.time.Duration;

public record AiModelProperties(
    URI ollamaBaseUrl,
    Duration healthTimeout
) {
    public URI tagsEndpoint() {
        return ollamaBaseUrl.resolve("/api/tags");
    }
}
