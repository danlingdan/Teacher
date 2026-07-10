package com.sqlteacher.application.error;

import java.util.Objects;

public record ApplicationError(
    String code,
    ApplicationErrorType type,
    String userMessage,
    boolean retryable
) {
    public ApplicationError {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        Objects.requireNonNull(type, "type must not be null");
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage must not be blank");
        }
    }
}
