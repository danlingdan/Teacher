package com.sqlteacher.application.connection;

import java.time.Duration;
import java.util.Objects;

public record DatabaseConnectionTestResult(
    boolean successful,
    String message,
    String databaseProduct,
    String databaseVersion,
    Duration elapsed
) {
    public DatabaseConnectionTestResult {
        message = requireText(message, "message");
        databaseProduct = Objects.requireNonNull(databaseProduct, "databaseProduct must not be null").trim();
        databaseVersion = Objects.requireNonNull(databaseVersion, "databaseVersion must not be null").trim();
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
