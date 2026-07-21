package com.sqlteacher.application.exercise;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ExerciseProgressItem(
    String exerciseId,
    String title,
    String knowledgePoint,
    int attempts,
    int failedSubmissions,
    boolean passed,
    Instant lastAttemptAt
) {
    public ExerciseProgressItem {
        exerciseId = requireText(exerciseId, "exerciseId");
        title = requireText(title, "title");
        knowledgePoint = requireText(knowledgePoint, "knowledgePoint");
        if (attempts < 0 || failedSubmissions < 0) {
            throw new IllegalArgumentException("attempt counts must not be negative");
        }
    }

    public Optional<Instant> lastAttempt() {
        return Optional.ofNullable(lastAttemptAt);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
