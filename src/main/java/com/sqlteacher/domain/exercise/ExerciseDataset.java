package com.sqlteacher.domain.exercise;

import java.util.Objects;

public record ExerciseDataset(
    String id,
    String name,
    String setupSql,
    int version
) {
    public ExerciseDataset {
        id = requireText(id, "id");
        name = requireText(name, "name");
        setupSql = requireText(setupSql, "setupSql");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
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
