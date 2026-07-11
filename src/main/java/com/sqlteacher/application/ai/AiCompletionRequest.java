package com.sqlteacher.application.ai;

import java.time.Duration;
import java.util.Objects;

public record AiCompletionRequest(
    String model,
    String prompt,
    Duration timeout
) {
    public AiCompletionRequest {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }
}