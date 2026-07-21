package com.sqlteacher.application.exercise;

import com.sqlteacher.domain.exercise.ExerciseDifficulty;

import java.util.Objects;

public record ExerciseSummary(
    String id,
    String title,
    String knowledgePoint,
    ExerciseDifficulty difficulty,
    int version,
    boolean enabled
) {
    public ExerciseSummary {
        id = requireText(id, "id");
        title = requireText(title, "title");
        knowledgePoint = requireText(knowledgePoint, "knowledgePoint");
        difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
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
