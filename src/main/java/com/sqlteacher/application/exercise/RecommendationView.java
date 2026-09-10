package com.sqlteacher.application.exercise;

import com.sqlteacher.domain.exercise.ExerciseDifficulty;
import com.sqlteacher.domain.exercise.ExerciseType;

import java.util.Objects;

/**
 * Deterministic "practice next" recommendation computed from the local attempt history.
 * The rule is fixed (knowledge-point accuracy × difficulty buckets), carries no
 * randomness and no AI; the reason is written by the same rule that picks the exercise.
 */
public record RecommendationView(
    String exerciseId,
    String title,
    String knowledgePoint,
    ExerciseDifficulty difficulty,
    ExerciseType exerciseType,
    String reason
) {
    public RecommendationView {
        exerciseId = requireText(exerciseId, "exerciseId");
        title = requireText(title, "title");
        knowledgePoint = Objects.requireNonNull(knowledgePoint, "knowledgePoint must not be null").trim();
        difficulty = Objects.requireNonNull(difficulty, "difficulty must not be null");
        exerciseType = exerciseType == null ? ExerciseType.QUERY : exerciseType;
        reason = requireText(reason, "reason");
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
