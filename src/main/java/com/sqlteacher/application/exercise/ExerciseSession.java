package com.sqlteacher.application.exercise;

import java.time.Instant;
import java.util.Objects;

public record ExerciseSession(
    String id,
    ExerciseView exercise,
    Instant startedAt,
    int hintsUsed,
    boolean completed
) {
    public ExerciseSession {
        id = requireText(id, "id");
        exercise = Objects.requireNonNull(exercise, "exercise must not be null");
        startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (hintsUsed < 0 || hintsUsed > 3) {
            throw new IllegalArgumentException("hintsUsed must be between 0 and 3");
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
